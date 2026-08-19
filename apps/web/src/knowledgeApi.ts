export type KnowledgeState =
  | 'PENDING_DISPATCH'
  | 'QUEUED'
  | 'PARSING'
  | 'EMBEDDING'
  | 'INDEXING'
  | 'READY'
  | 'OCR_REQUIRED'
  | 'FAILED'
  | 'DELETING'

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

export type ParsedDocumentMetadata = {
  title: string | null
  authors: string[]
  subject: string | null
  description: string | null
  language: string | null
  createdAt: string | null
  modifiedAt: string | null
  producer: string | null
}

export type DocumentDetail = {
  document: DocumentSummary
  jobs: IngestionJob[]
  pageCount: number
  textCharCount: number
  metadata: ParsedDocumentMetadata
}

export type UploadPolicy = {
  resumableEnabled: boolean
  maxObjectBytes: number
  resumableThresholdBytes: number
  partSizeBytes: number
  maxConcurrentParts: number
}

export type UploadInitRequest = {
  fileName: string
  declaredMediaType: string
  sizeBytes: number
  fileFingerprint: string
  lastModifiedMillis: number
}

export type UploadPartReceipt = {
  partNumber: number
  sizeBytes: number
  sha256: string
  confirmedAt: string
}

export type UploadSessionView = {
  sessionId: string
  status: 'UPLOADING' | 'COMPLETING' | 'COMPLETED' | 'FAILED' | 'ABORTED' | 'EXPIRED'
  fileName: string
  declaredMediaType: string
  sizeBytes: number
  partSizeBytes: number
  totalParts: number
  confirmedPartNumbers: number[]
  receipts: UploadPartReceipt[]
  confirmedBytes: number
  expiresAt: string
  hardExpiresAt: string
  documentId: string | null
  failureCode: string | null
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

export type SearchStatus = 'SUCCESS' | 'DEGRADED' | 'EMPTY' | 'UNAVAILABLE'
export type SearchReason =
  | 'NO_READY_DOCUMENTS'
  | 'COMPLETE'
  | 'NO_MATCH'
  | 'VECTOR_UNAVAILABLE'
  | 'RERANK_UNAVAILABLE'
  | 'INDEX_UNAVAILABLE'
  | 'READY_SCOPE_TOO_LARGE'

export type SearchHit = {
  evidenceId: string
  sourceId: string
  revisionId: string
  documentName: string
  location: string
  text: string
  rank: number | null
  score: number | null
}

export type SearchStage = {
  items: SearchHit[]
}

export type KnowledgeSearchResult = {
  policyVersion: string
  status: SearchStatus
  reason: SearchReason
  bm25: SearchStage
  vector: SearchStage
  rrf: SearchStage
  finalResults: SearchStage
  executedStages: string[]
  skippedStages: string[]
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
  if (response.status === 204) return undefined as T
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

export async function fetchUploadPolicy(): Promise<UploadPolicy> {
  return request('/api/knowledge/uploads/policy')
}

export async function initUploadSession(body: UploadInitRequest): Promise<UploadSessionView> {
  return request('/api/knowledge/uploads', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function fetchUploadSession(sessionId: string, fileFingerprint?: string): Promise<UploadSessionView> {
  const headers = fileFingerprint === undefined ? undefined : { 'X-Upload-File-Fingerprint': fileFingerprint }
  return request(`/api/knowledge/uploads/${encodeURIComponent(sessionId)}`, { headers })
}

export async function uploadPart(sessionId: string, partNumber: number, blob: Blob, sha256: string): Promise<UploadSessionView> {
  return request(`/api/knowledge/uploads/${encodeURIComponent(sessionId)}/parts/${partNumber}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/octet-stream',
      'X-Upload-Part-SHA256': sha256,
    },
    body: blob,
  })
}

export async function completeUploadSession(sessionId: string): Promise<DocumentSummary> {
  return request(`/api/knowledge/uploads/${encodeURIComponent(sessionId)}/complete`, { method: 'POST' })
}

export async function cancelUploadSession(sessionId: string): Promise<void> {
  return request(`/api/knowledge/uploads/${encodeURIComponent(sessionId)}`, { method: 'DELETE' })
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

export async function deleteDocument(id: string): Promise<void> {
  return request(`/api/knowledge/documents/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function searchKnowledge(query: string): Promise<KnowledgeSearchResult> {
  return request('/api/knowledge/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query }),
  })
}
