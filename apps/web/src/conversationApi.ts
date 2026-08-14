// Conversation HTTP client：五个后端用例的稳定 JSON 映射与统一错误提取。
// 错误体约定为 {"code": "STABLE_CODE", "message": "用户可理解信息"}。

export type RunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED'

export type Run = {
  id: string
  conversationId: string
  triggerEntryId: string
  status: RunStatus
  errorCode: string | null
  startedAt: string
  endedAt: string | null
}

export type ConversationSummary = {
  id: string
  workspaceId: string
  title: string
  latestRun: Run | null
  createdAt: string
  updatedAt: string
}

export type Conversation = {
  id: string
  workspaceId: string
  title: string
  historyFormatVersion: number
  activeLeafEntryId: string | null
  lastConfirmedSeq: number
  latestCompactionEntryId: string | null
  latestCompactionSeq: number | null
  latestCompactionByteOffset: number | null
  createdAt: string
  updatedAt: string
}

export type EntryType = 'USER_MESSAGE' | 'ASSISTANT_MESSAGE' | 'COMPACTION'

// 类型化 payload：后端按实际类型序列化字段，前端依据 entry.type 读取
export type Entry = {
  formatVersion: number
  conversationId: string
  id: string
  seq: number
  parentId: string | null
  type: EntryType
  createdAt: string
  payload: {
    text?: string
    runId?: string
    provider?: string
    model?: string
    usage?: { promptTokens: number; completionTokens: number; totalTokens: number } | null
  }
}

export type ConversationDetail = {
  conversation: Conversation
  activePath: Entry[]
  pendingRun: Run | null
}

export type ConversationRunResult = {
  conversation: Conversation
  userEntry: Entry
  assistantEntry: Entry
  run: Run
}

// 后端稳定错误；code 用于前端判断可重试等行为
export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) {
    let code = 'INTERNAL_ERROR'
    let message = `请求失败（HTTP ${response.status}）`
    try {
      const body = (await response.json()) as { code?: string; message?: string }
      if (body.code) code = body.code
      if (body.message) message = body.message
    } catch {
      // 非 JSON 错误体时保留默认文案
    }
    throw new ApiError(code, message, response.status)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

export async function fetchConversations(): Promise<ConversationSummary[]> {
  return request('/api/conversations')
}

export async function createConversation(): Promise<ConversationSummary> {
  return request('/api/conversations', { method: 'POST' })
}

export async function fetchConversation(id: string): Promise<ConversationDetail> {
  return request(`/api/conversations/${id}`)
}

export async function sendMessage(id: string, text: string): Promise<ConversationRunResult> {
  return request(`/api/conversations/${id}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  })
}

export async function retryRun(id: string, runId: string): Promise<ConversationRunResult> {
  return request(`/api/conversations/${id}/runs/${runId}/retry`, { method: 'POST' })
}
