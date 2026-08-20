export type RepositoryStatus = 'AVAILABLE' | 'UNAVAILABLE'

export type PlatformView = {
  operatingSystem: string
  pathSeparator: string
  windows: boolean
  pathExample: string
}

export type Repository = {
  id: string
  path: string
  name: string
  aliases: string[]
  registered: boolean
  status: RepositoryStatus | string
  branch: string | null
  head: string | null
  dirty: boolean
  unborn: boolean
  detached: boolean
  shallow: boolean
  stagedCount: number
  unstagedCount: number
  untrackedCount: number
  sensitiveChangedCount: number
  unavailableCode: string | null
  createdAt: string
  updatedAt: string
}

export type SearchRoot = {
  id: string
  path: string
  createdAt: string
}

export type RepositoryObservation = {
  branch: string | null
  head: string | null
  dirty: boolean
  unborn: boolean
  detached: boolean
  shallow: boolean
  stagedCount: number
  unstagedCount: number
  untrackedCount: number
  sensitiveChangedCount: number
  observedAt: string
}

export type CallChainReference = {
  id: string
  repositoryId: string
  name: string
  nodeCount: number
  edgeCount: number
}

export type CallChainSummary = CallChainReference & {
  repositoryName: string
  createdAt: string
  updatedAt: string
}

export type NodeRevisionView = {
  id: string
  parentRevisionId: string | null
  sourceHash: string
  path: string
  startLine: number
  endLine: number
  observation: RepositoryObservation
  observedAt: string
}

export type CallChainNodeDetail = {
  nodeId: string
  revisionId: string
  language: string
  qualifiedSymbol: string
  signature: string
  summary: string
  sourceHash: string
  path: string
  startLine: number
  endLine: number
  source: string
  observation: RepositoryObservation
  revisions: NodeRevisionView[]
}

export type CallChainEdge = {
  fromNodeId: string
  toNodeId: string
}

export type CallChainDetail = CallChainSummary & {
  originConversationId: string
  originAnswerEntryId: string
  createdAt: string
  updatedAt: string
  nodes: CallChainNodeDetail[]
  edges: CallChainEdge[]
}

export type CodebaseCatalog = {
  platform: PlatformView
  gitAvailable: boolean
  activeRepositoryId: string | null
  repositories: Repository[]
  searchRoots: SearchRoot[]
}

export class CodebaseApiError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'CodebaseApiError'
    this.code = code
  }
}

export async function fetchCodebase(signal?: AbortSignal): Promise<CodebaseCatalog> {
  return request<CodebaseCatalog>('/api/codebase', { signal })
}

export async function registerRepository(
  path: string,
  name?: string,
  aliases?: string[],
  signal?: AbortSignal,
): Promise<Repository> {
  return request<Repository>('/api/codebase/repositories', {
    method: 'POST',
    body: JSON.stringify({ path, name: name?.trim() || undefined, aliases }),
    signal,
  })
}

export async function updateRepository(
  id: string,
  name: string,
  aliases: string[],
  signal?: AbortSignal,
): Promise<Repository> {
  return request<Repository>(`/api/codebase/repositories/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body: JSON.stringify({ name, aliases }),
    signal,
  })
}

export async function unregisterRepository(id: string, signal?: AbortSignal): Promise<CodebaseCatalog> {
  return request<CodebaseCatalog>(`/api/codebase/repositories/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    signal,
  })
}

export async function setActiveRepository(
  repositoryId: string | null,
  signal?: AbortSignal,
): Promise<CodebaseCatalog> {
  return request<CodebaseCatalog>('/api/codebase/active-repository', {
    method: 'PUT',
    body: JSON.stringify({ repositoryId }),
    signal,
  })
}

export async function addSearchRoot(path: string, signal?: AbortSignal): Promise<SearchRoot> {
  return request<SearchRoot>('/api/codebase/search-roots', {
    method: 'POST',
    body: JSON.stringify({ path }),
    signal,
  })
}

export async function removeSearchRoot(id: string, signal?: AbortSignal): Promise<CodebaseCatalog> {
  return request<CodebaseCatalog>(`/api/codebase/search-roots/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    signal,
  })
}

export async function listCallChains(repositoryId: string, signal?: AbortSignal): Promise<CallChainSummary[]> {
  return request<CallChainSummary[]>(
    `/api/codebase/repositories/${encodeURIComponent(repositoryId)}/call-chains`,
    { signal },
  )
}

export async function fetchCallChain(
  repositoryId: string,
  callChainId: string,
  signal?: AbortSignal,
): Promise<CallChainDetail> {
  return request<CallChainDetail>(
    `/api/codebase/repositories/${encodeURIComponent(repositoryId)}/call-chains/${encodeURIComponent(callChainId)}`,
    { signal },
  )
}

export async function renameCallChain(
  repositoryId: string,
  callChainId: string,
  name: string,
  signal?: AbortSignal,
): Promise<CallChainDetail> {
  return request<CallChainDetail>(
    `/api/codebase/repositories/${encodeURIComponent(repositoryId)}/call-chains/${encodeURIComponent(callChainId)}`,
    { method: 'PATCH', body: JSON.stringify({ name }), signal },
  )
}

export async function deleteCallChain(
  repositoryId: string,
  callChainId: string,
  signal?: AbortSignal,
): Promise<CallChainDetail> {
  return request<CallChainDetail>(
    `/api/codebase/repositories/${encodeURIComponent(repositoryId)}/call-chains/${encodeURIComponent(callChainId)}`,
    { method: 'DELETE', signal },
  )
}

async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(url, {
      ...init,
      headers: { 'Content-Type': 'application/json', ...(init.headers ?? {}) },
    })
  } catch {
    throw new Error('无法连接代码库服务')
  }
  const payload = await readJson(response)
  if (!response.ok) {
    const error = isErrorPayload(payload) ? payload : { code: 'CODEBASE_INTERNAL_ERROR', message: '代码库服务请求失败' }
    throw new CodebaseApiError(error.code, error.message)
  }
  return payload as T
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

function isErrorPayload(value: unknown): value is { code: string; message: string } {
  if (typeof value !== 'object' || value === null) return false
  const record = value as Record<string, unknown>
  return typeof record.code === 'string' && typeof record.message === 'string'
}
