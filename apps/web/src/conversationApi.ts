// Conversation HTTP client：列表 / 创建 / 打开使用稳定 JSON 映射，
// 发送与重试消费 POST SSE Run 流。错误体约定为 {"code": "STABLE_CODE", "message": "用户可理解信息"}。

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

export type EntryType = 'USER_MESSAGE' | 'ASSISTANT_MESSAGE' | 'COMPACTION' | 'TITLE'

export type TokenUsage = {
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
}

// 类型化 payload：后端按实际类型序列化字段，前端依据 entry.type 读取对应字段
export type EntryPayload = {
  text?: string
  runId?: string
  provider?: string
  model?: string
  usage?: TokenUsage | null
  // TITLE
  title?: string
  sourceRunId?: string
  sourceAssistantEntryId?: string
  // COMPACTION
  summary?: string
  coveredThroughEntryId?: string
  retainedTail?: Entry[]
  tokensBefore?: number | null
}

export type Entry = {
  formatVersion: number
  conversationId: string
  id: string
  seq: number
  parentId: string | null
  type: EntryType
  createdAt: string
  payload: EntryPayload
}

export type ConversationDetail = {
  conversation: Conversation
  activePath: Entry[]
  pendingRun: Run | null
}

// ---- SSE Run 流事件数据；字段名与后端 RunStreamListener 事件 JSON 一致 ----
export type RunStartedEvent = {
  conversationId: string
  run: Run
  userEntry: Entry
  isRetry: boolean
}

export type CompactionCompletedEvent = {
  conversationId: string
  compactionEntry: Entry
  conversation: Conversation
}

export type AssistantDeltaEvent = {
  runId: string
  delta: string
}

export type AssistantCompletedEvent = {
  conversationId: string
  assistantEntry: Entry
}

export type TitleUpdatedEvent = {
  conversationId: string
  titleEntry: Entry
  title: string
}

export type RunCompletedEvent = {
  conversationId: string
  run: Run
  conversation: Conversation
}

export type RunFailedEvent = {
  conversationId: string
  errorCode: string
  message: string
  run: Run
  conversation: Conversation
}

/**
 * 一次发送 / 重试的 SSE 事件消费合同；与后端 RunStreamListener 顺序一致：
 * run_started → 可选 compaction_completed → 零到多条 assistant_delta →
 * 成功时 assistant_completed → 可选 title_updated → 唯一 run_completed / run_failed。
 * 终态事件之后不再有业务事件；run_started 之前的前置错误以 ApiError 抛出（JSON 错误体）。
 */
export type RunStreamListener = {
  onRunStarted(event: RunStartedEvent): void
  onCompactionCompleted(event: CompactionCompletedEvent): void
  onAssistantDelta(event: AssistantDeltaEvent): void
  onAssistantCompleted(event: AssistantCompletedEvent): void
  onTitleUpdated(event: TitleUpdatedEvent): void
  onRunCompleted(event: RunCompletedEvent): void
  onRunFailed(event: RunFailedEvent): void
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
    throw await errorFromResponse(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

async function errorFromResponse(response: Response): Promise<ApiError> {
  let code = 'INTERNAL_ERROR'
  let message = `请求失败（HTTP ${response.status}）`
  try {
    const body = (await response.json()) as { code?: string; message?: string }
    if (body.code) code = body.code
    if (body.message) message = body.message
  } catch {
    // 非 JSON 错误体时保留默认文案
  }
  return new ApiError(code, message, response.status)
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

// ---- POST SSE：fetch + ReadableStream 解析标准 SSE 帧，不支持原生 EventSource ----

/**
 * 消费一次 send / retry 的 SSE Run 流。
 * 事件按 {@link RunStreamListener} 顺序回调；run_started 之前的前置错误抛 ApiError。
 * 传输中断或流结束仍未收到终态事件时抛 ApiError，由调用方重新读取权威状态，不自动重发。
 */
async function streamRun(
  path: string,
  init: RequestInit | undefined,
  listener: RunStreamListener,
): Promise<void> {
  const response = await fetch(path, init)
  if (!response.ok) {
    throw await errorFromResponse(response)
  }
  if (response.body === null) {
    throw new ApiError('STREAM_UNAVAILABLE', '当前浏览器不支持响应流', 0)
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  const dataLines: string[] = []
  let terminalReceived = false

  // 分发一个完整 SSE 帧（空行结束）：解析 JSON 后回调对应事件；未知事件视为协议错误
  const dispatch = () => {
    if (eventName === '' || dataLines.length === 0) {
      return
    }
    const data = dataLines.join('\n')
    let parsed: unknown
    try {
      parsed = JSON.parse(data)
    } catch {
      throw new ApiError('BAD_SSE_FRAME', '服务端返回了无法解析的流事件', 0)
    }
    switch (eventName) {
      case 'run_started':
        listener.onRunStarted(parsed as RunStartedEvent)
        break
      case 'compaction_completed':
        listener.onCompactionCompleted(parsed as CompactionCompletedEvent)
        break
      case 'assistant_delta':
        listener.onAssistantDelta(parsed as AssistantDeltaEvent)
        break
      case 'assistant_completed':
        listener.onAssistantCompleted(parsed as AssistantCompletedEvent)
        break
      case 'title_updated':
        listener.onTitleUpdated(parsed as TitleUpdatedEvent)
        break
      case 'run_completed':
        listener.onRunCompleted(parsed as RunCompletedEvent)
        terminalReceived = true
        break
      case 'run_failed':
        listener.onRunFailed(parsed as RunFailedEvent)
        terminalReceived = true
        break
      default:
        throw new ApiError('BAD_SSE_FRAME', `未知流事件：${eventName}`, 0)
    }
    eventName = ''
    dataLines.length = 0
  }

  // 处理单行：'' 结束一帧，event/data 行累积；注释行与空 data 被忽略
  const consumeLine = (line: string) => {
    if (line === '') {
      dispatch()
    } else if (line.startsWith('event:')) {
      eventName = line.slice(6).trim()
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5).trimStart())
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      let newlineIndex: number
      while ((newlineIndex = buffer.indexOf('\n')) !== -1) {
        const raw = buffer.slice(0, newlineIndex)
        buffer = buffer.slice(newlineIndex + 1)
        consumeLine(raw.endsWith('\r') ? raw.slice(0, -1) : raw)
      }
    }
    // 流结束时 flush：解码器残余的多字节序列与没有尾部换行的最后一帧；
    // 完整行已在主循环消费，残余内容不包含换行符
    buffer += decoder.decode()
    if (buffer !== '') {
      consumeLine(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer)
    }
    if (!terminalReceived) {
      throw new ApiError('STREAM_ENDED_EARLY', '回答流意外结束，请刷新查看状态', 0)
    }
  } finally {
    reader.releaseLock()
  }
}

export function streamSend(
  conversationId: string,
  text: string,
  listener: RunStreamListener,
): Promise<void> {
  return streamRun(
    `/api/conversations/${conversationId}/messages`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ text }),
    },
    listener,
  )
}

export function streamRetry(
  conversationId: string,
  runId: string,
  listener: RunStreamListener,
): Promise<void> {
  return streamRun(`/api/conversations/${conversationId}/runs/${runId}/retry`, { method: 'POST' }, listener)
}
