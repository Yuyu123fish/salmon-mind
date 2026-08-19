import { describe, expect, it, vi } from 'vitest'
import { ResumableKnowledgeUploader, quickFileFingerprint, type UploadTransport } from '../KnowledgeUpload.ts'
import type { UploadSessionView } from '../knowledgeApi.ts'

const session = (): UploadSessionView => ({
  sessionId: 'session-1', status: 'UPLOADING', fileName: 'large.txt', declaredMediaType: 'text/plain',
  sizeBytes: 6, partSizeBytes: 2, totalParts: 3, confirmedPartNumbers: [], receipts: [], confirmedBytes: 0,
  expiresAt: '2026-08-19T01:00:00Z', hardExpiresAt: '2026-08-20T01:00:00Z', documentId: null, failureCode: null,
})

describe('ResumableKnowledgeUploader', () => {
  it('uses server-confirmed receipts and skips already confirmed parts', async () => {
    const uploads: number[] = []
    const transport: UploadTransport = {
      uploadPart: vi.fn(async (_id, part, _blob, sha) => {
        uploads.push(part)
        const current = session()
        current.confirmedPartNumbers.push(...uploads)
        current.confirmedBytes = uploads.length * 2
        return { ...current, confirmedPartNumbers: [...new Set(uploads)], confirmedBytes: uploads.length * 2,
          receipts: uploads.map((partNumber) => ({ partNumber, sizeBytes: 2, sha256: sha, confirmedAt: '2026-08-19T00:00:00Z' })) }
      }),
      complete: vi.fn(async () => ({ id: 'document-1' } as never)),
    }
    const progress: number[] = []
    const file = new File(['abcdef'], 'large.txt', { type: 'text/plain', lastModified: 12 })
    const uploader = new ResumableKnowledgeUploader(file, session(), (item) => progress.push(item.session.confirmedBytes), { transport })
    await uploader.start()
    expect(uploads.sort()).toEqual([1, 2, 3])
    expect(progress).toContain(6)
    expect(quickFileFingerprint(file)).toBe('large.txt|6|12')
  })
})
