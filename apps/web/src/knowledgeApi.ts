export type KnowledgeState =
  | 'PENDING_DISPATCH'
  | 'QUEUED'
  | 'PARSING'
  | 'EMBEDDING'
  | 'INDEXING'
  | 'READY'
  | 'OCR_REQUIRED'
  | 'FAILED'

export type DocumentSummary = {
  id: string
  workspaceId: string
  revisionId: string
  latestJobId: string | null
  name: string
  format: string
  mediaType: string
  sizeBytes: number
  sha256: string
  state: KnowledgeState
  retryable: boolean
  evidenceCount: number
  createdAt: string
  updatedAt: string
}

export type IngestionJob = {
  id: string
  attemptNumber: number
  state: KnowledgeState
  retryable: boolean
  errorCode: string | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
  startedAt: string | null
  endedAt: string | null
}

export type DocumentDetail = {
  document: DocumentSummary
  jobs: IngestionJob[]
  pageCount: number
  textCharCount: number
}

export type EvidencePreview = {
  id: string
  ordinal: number
  location: string
  text: string
  charCount: number
}

export type EvidencePage = {
  items: EvidencePreview[]
  page: number
  size: number
  total: number
}

export class KnowledgeApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'KnowledgeApiError'
    this.code = code
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    throw await errorFromResponse(response)
  }
  return (await response.json()) as T
}

async function errorFromResponse(response: Response): Promise<KnowledgeApiError> {
  let code = 'INTERNAL_ERROR'
  let message = `请求失败（HTTP ${response.status}）`
  try {
    const body = (await response.json()) as { code?: string; message?: string }
    if (body.code) code = body.code
    if (body.message) message = body.message
  } catch {
    // 非 JSON 错误体时保留稳定的 HTTP 文案
  }
  return new KnowledgeApiError(code, message, response.status)
}

export async function fetchDocuments(): Promise<DocumentSummary[]> {
  return request('/api/knowledge/documents')
}

export async function uploadDocument(file: File): Promise<DocumentSummary> {
  const body = new FormData()
  body.append('file', file)
  return request('/api/knowledge/documents', { method: 'POST', body })
}

export async function fetchDocument(id: string): Promise<DocumentDetail> {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}`)
}

export async function fetchEvidence(id: string, page = 0, size = 20): Promise<EvidencePage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}/evidence?${params.toString()}`)
}

export async function retryDocument(id: string): Promise<DocumentSummary> {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}/retry`, { method: 'POST' })
}
