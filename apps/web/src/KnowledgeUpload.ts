import {
  completeUploadSession,
  type DocumentSummary,
  type UploadSessionView,
  uploadPart,
} from './knowledgeApi.ts'

export type UploadProgress = {
  session: UploadSessionView
  phase: 'UPLOADING' | 'PAUSED' | 'COMPLETING' | 'COMPLETED' | 'FAILED'
  activePart: number | null
  error: Error | null
}

export type UploadTransport = {
  uploadPart: typeof uploadPart
  complete: typeof completeUploadSession
}

const defaultTransport: UploadTransport = {
  uploadPart,
  complete: completeUploadSession,
}

/** 快速文件指纹只用于阻止明显的错误续传；已确认 part 仍会逐段复核 checksum。 */
export function quickFileFingerprint(file: File): string {
  return `${file.name}|${file.size}|${file.lastModified}`
}

export async function sha256Blob(blob: Blob): Promise<string> {
  const bytes = await blob.arrayBuffer()
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('')
}

/**
 * 可测试的浏览器上传器：只把 Server 返回的 confirmed bytes 写入进度，
 * 并把 Blob/hash/并发/重试与 KnowledgeView 解耦。
 */
export class ResumableKnowledgeUploader {
  private session: UploadSessionView
  private readonly file: File
  private readonly transport: UploadTransport
  private readonly onProgress: (progress: UploadProgress) => void
  private readonly retryLimit: number
  private paused = false
  private cancelled = false
  private generation = 0
  private running: Promise<DocumentSummary> | null = null
  private resumeWaiter: (() => void) | null = null

  constructor(
    file: File,
    session: UploadSessionView,
    onProgress: (progress: UploadProgress) => void,
    options: { transport?: UploadTransport; retryLimit?: number; maxConcurrency?: number } = {},
  ) {
    this.file = file
    this.session = session
    this.transport = options.transport ?? defaultTransport
    this.onProgress = onProgress
    this.retryLimit = Math.max(0, Math.min(5, options.retryLimit ?? 2))
    this.maxConcurrency = Math.max(1, Math.min(16, options.maxConcurrency ?? 3))
  }

  private readonly maxConcurrency: number

  get currentSession(): UploadSessionView {
    return this.session
  }

  pause(): void {
    if (this.session.status !== 'UPLOADING') return
    this.paused = true
    this.generation += 1
    this.emit('PAUSED', null, null)
  }

  resume(): Promise<DocumentSummary> {
    this.paused = false
    this.resumeWaiter?.()
    this.resumeWaiter = null
    return this.start()
  }

  cancel(): void {
    this.cancelled = true
    this.paused = false
    this.generation += 1
    this.resumeWaiter?.()
    this.resumeWaiter = null
  }

  start(): Promise<DocumentSummary> {
    if (this.running !== null) return this.running
    this.running = this.run().finally(() => { this.running = null })
    return this.running
  }

  private async run(): Promise<DocumentSummary> {
    try {
      await this.verifyConfirmedParts()
      const missing = Array.from({ length: this.session.totalParts }, (_, index) => index + 1)
        .filter((part) => !this.session.confirmedPartNumbers.includes(part))
      let cursor = 0
      const workers = Array.from({ length: Math.max(1, Math.min(this.maxConcurrency, this.session.totalParts)) }, async () => {
        while (!this.cancelled) {
          await this.waitIfPaused()
          if (this.cancelled) throw new Error('上传已取消')
          const index = cursor++
          if (index >= missing.length) return
          await this.sendPart(missing[index])
        }
      })
      await Promise.all(workers)
      await this.waitIfPaused()
      if (this.cancelled) throw new Error('上传已取消')
      this.emit('COMPLETING', null, null)
      const completed = await this.transport.complete(this.session.sessionId)
      this.session = {
        ...this.session,
        status: 'COMPLETED',
        documentId: completed.id,
        confirmedBytes: this.session.sizeBytes,
      }
      this.emit('COMPLETED', null, null)
      return completed
    } catch (error) {
      if (!this.cancelled) this.emit('FAILED', null, error instanceof Error ? error : new Error('上传失败'))
      throw error
    }
  }

  private async verifyConfirmedParts(): Promise<void> {
    for (const receipt of this.session.receipts) {
      const start = (receipt.partNumber - 1) * this.session.partSizeBytes
      const blob = this.file.slice(start, start + receipt.sizeBytes)
      const checksum = await sha256Blob(blob)
      if (checksum.toLowerCase() !== receipt.sha256.toLowerCase()) {
        throw new Error('所选文件与已有上传会话不一致')
      }
    }
  }

  private async sendPart(partNumber: number): Promise<void> {
    const start = (partNumber - 1) * this.session.partSizeBytes
    const blob = this.file.slice(start, start + this.expectedPartLength(partNumber))
    const checksum = await sha256Blob(blob)
    let lastError: unknown = null
    for (let attempt = 0; attempt <= this.retryLimit; attempt += 1) {
      await this.waitIfPaused()
      const generation = this.generation
      this.emit('UPLOADING', partNumber, null)
      try {
        const next = await this.transport.uploadPart(this.session.sessionId, partNumber, blob, checksum)
        if (generation !== this.generation || this.paused || this.cancelled) return
        this.acceptServerSession(next)
        this.emit('UPLOADING', null, null)
        return
      } catch (error) {
        lastError = error
        if (attempt < this.retryLimit) await new Promise((resolve) => window.setTimeout(resolve, 20 * (attempt + 1)))
      }
    }
    throw lastError instanceof Error ? lastError : new Error('分片上传失败')
  }

  private expectedPartLength(partNumber: number): number {
    const start = (partNumber - 1) * this.session.partSizeBytes
    return Math.min(this.session.partSizeBytes, this.session.sizeBytes - start)
  }

  private acceptServerSession(next: UploadSessionView): void {
    // 并行 part 的响应可能乱序；Receipt 只会增加，合并后 UI 的服务端确认进度不会回退。
    const receipts = new Map(this.session.receipts.map((receipt) => [receipt.partNumber, receipt]))
    for (const receipt of next.receipts) receipts.set(receipt.partNumber, receipt)
    const mergedReceipts = [...receipts.values()].sort((left, right) => left.partNumber - right.partNumber)
    this.session = {
      ...next,
      receipts: mergedReceipts,
      confirmedPartNumbers: mergedReceipts.map((receipt) => receipt.partNumber),
      confirmedBytes: Math.max(this.session.confirmedBytes, next.confirmedBytes),
    }
  }

  private waitIfPaused(): Promise<void> {
    if (!this.paused) return Promise.resolve()
    return new Promise((resolve) => { this.resumeWaiter = resolve })
  }

  private emit(phase: UploadProgress['phase'], activePart: number | null, error: Error | null): void {
    this.onProgress({ session: this.session, phase, activePart, error })
  }
}
