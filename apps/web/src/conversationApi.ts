// Conversation HTTP client：列表 / 创建 / 打开使用稳定 JSON 映射，
// 发送、重试与继续生成消费 POST SSE Run 流。错误体约定为
// {"code": "STABLE_CODE", "message": "用户可理解信息"}。

export type RunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'INTERRUPTED'
export type RunResultStatus = 'COMPLETE' | 'INCOMPLETE_LENGTH'

export type Run = {
  id: string
  conversationId: string
  triggerEntryId: string
  status: RunStatus
  errorCode: string | null
  startedAt: string
  endedAt: string | null
  resultStatus?: RunResultStatus | null
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

export type LocalCitation = {
  kind: 'local'
  referenceId: string
  evidenceId: string
  revisionId: string
  documentName: string
  location: string
  citationNote?: string | null
}

export type WebCitation = {
  kind: 'web'
  referenceId: string
  provider: string
  title: string
  url: string
  site: string
  dateLabel: string | null
  retrievedAt: string
  citationNote?: string | null
}

export type CitationPayload = LocalCitation | WebCitation

export type ToolResultStatus = 'SUCCESS' | 'DEGRADED' | 'EMPTY' | 'UNAVAILABLE'

export type ToolRequestDetail = {
  querySummary: string
  querySummaryTruncated: boolean
  freshness: string | null
  freshnessDefaulted: boolean
  count: number | null
  countDefaulted: boolean
}

export type ToolOutcomeDetail = {
  provider: string | null
  resultStatus: ToolResultStatus | null
  stableReasonCode: string | null
  sourceCount: number | null
  durationMillis: number
  degraded: boolean
  resultTruncated: boolean
  estimatedResultTokens?: number | null
  remainingInputTokens?: number | null
  contextCleaned?: boolean
}

export type LocalRetrievedSource = {
  kind: 'local'
  referenceId: string
  evidenceId: string
  revisionId: string
  documentName: string
  location: string
  retrievedAt: string
  excerptKind: string
  sourceExcerpt?: string | null
  originToolCallId?: string | null
  resultPosition?: number | null
  providerRank?: number | null
}

export type WebRetrievedSource = {
  kind: 'web'
  referenceId: string
  provider: string
  title: string
  url: string
  site: string
  dateLabel: string | null
  retrievedAt: string
  excerptKind: string
  sourceExcerpt?: string | null
  originToolCallId?: string | null
  resultPosition?: number | null
  providerRank?: number | null
}

export type RetrievedSourcePayload = LocalRetrievedSource | WebRetrievedSource

export type CallChainReference = {
  id: string
  repositoryId: string
  name: string
  nodeCount: number
  edgeCount: number
}

export type ReasoningTraceItem = {
  kind: 'REASONING'
  text: string
  truncated: boolean
}

export type ToolTraceItem = {
  kind: 'TOOL'
  truncated: boolean
  toolCallId: string
  toolName: string
  toolStatus: 'RUNNING' | 'COMPLETED' | 'FAILED'
  safeSummary: string
  stableErrorCode: string | null
  requestDetail?: ToolRequestDetail | null
  outcomeDetail?: ToolOutcomeDetail | null
}

export type RunTraceItem = ReasoningTraceItem | ToolTraceItem

// 类型化 payload：后端按实际类型序列化字段，前端依据 entry.type 读取对应字段
export type EntryPayload = {
  text?: string
  runId?: string
  action?: 'MESSAGE' | 'CONTINUE_GENERATION'
  provider?: string
  model?: string
  usage?: TokenUsage | null
  citations?: CitationPayload[]
  retrievedSources?: RetrievedSourcePayload[]
  trace?: RunTraceItem[]
  completionStatus?: 'COMPLETE' | 'INCOMPLETE_LENGTH'
  completionDetailCode?: string | null
  callChains?: CallChainReference[]
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

export type ReasoningDeltaEvent = {
  runId: string
  delta: string
}

export type ToolStartedEvent = {
  runId: string
  toolCallId: string
  toolName: string
  safeSummary: string
  requestDetail?: ToolRequestDetail | null
}

export type ToolCompletedEvent = {
  runId: string
  toolCallId: string
  toolName: string
  durationMillis: number
  provider: string | null
  sourceCount: number
  truncated: boolean
  degraded: boolean
  safeSummary: string
  outcomeDetail?: ToolOutcomeDetail | null
}

export type ToolFailedEvent = {
  runId: string
  toolCallId: string
  toolName: string
  durationMillis: number
  stableErrorCode: string
  safeMessage: string
  outcomeDetail?: ToolOutcomeDetail | null
}

export type AssistantCompletedEvent = {
  conversationId: string
  assistantEntry: Entry
}

export type TitleUpdatedEvent = {
  conversationId: string
  titleEntry: Entry
  title: string
  conversation: Conversation
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
 * 一次发送 / 重试 / 继续生成的 SSE 事件消费合同；与后端 RunStreamListener 顺序一致：
 * run_started → 可选 compaction_completed → 可交错的 reasoning/tool/assistant delta →
 * 成功时 assistant_completed → 可选 title_updated → 唯一 run_completed / run_failed。
 * 终态事件之后不再有业务事件；run_started 之前的前置错误以 ApiError 抛出（JSON 错误体）。
 */
export type RunStreamListener = {
  onRunStarted(event: RunStartedEvent): void
  onCompactionCompleted(event: CompactionCompletedEvent): void
  onToolStarted(event: ToolStartedEvent): void
  onToolCompleted(event: ToolCompletedEvent): void
  onToolFailed(event: ToolFailedEvent): void
  onReasoningDelta(event: ReasoningDeltaEvent): void
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
 * 消费一次 send / retry / continue 的 SSE Run 流。
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
  const dispatchRunFrame = createRunEventDispatcher(listener)

  // 分发一个完整 SSE 帧：已知事件缺字段仍报协议错误，未来可选中间事件直接忽略。
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
    const result = dispatchRunFrame(eventName, parsed)
    if (result === 'terminal') terminalReceived = true
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

type DispatchResult = 'intermediate' | 'terminal' | 'ignored'

const KNOWN_RUN_EVENTS = [
  'run_started',
  'compaction_completed',
  'reasoning_delta',
  'tool_started',
  'tool_completed',
  'tool_failed',
  'assistant_delta',
  'assistant_completed',
  'title_updated',
  'run_completed',
  'run_failed',
] as const

type KnownRunEventName = (typeof KNOWN_RUN_EVENTS)[number]

type RunEventSequence = {
  conversationId: string | null
  runId: string | null
  assistantCompleted: boolean
  assistantCompletionStatus: RunResultStatus | null
  titleUpdated: boolean
  terminal: boolean
}

/** 为单条 SSE 连接建立严格的单 Run、单终态分发器。 */
export function createRunEventDispatcher(listener: RunStreamListener) {
  const sequence: RunEventSequence = {
    conversationId: null,
    runId: null,
    assistantCompleted: false,
    assistantCompletionStatus: null,
    titleUpdated: false,
    terminal: false,
  }
  return (eventName: string, parsed: unknown): DispatchResult =>
    dispatchRunEvent(eventName, parsed, listener, sequence)
}

/**
 * 类型化分发一个已解析事件。未知事件按向前兼容的可选中间事件忽略；
 * 已知事件的稳定身份/终态字段缺失时仍拒绝，避免静默破坏 Run 隔离。
 */
export function dispatchRunEvent(
  eventName: string,
  parsed: unknown,
  listener: RunStreamListener,
  sequence?: RunEventSequence,
): DispatchResult {
  if (!isKnownRunEvent(eventName)) return 'ignored'
  const value = objectValue(parsed)
  validateKnownRunEvent(eventName, value)
  if (sequence !== undefined) validateRunEventSequence(eventName, value, sequence)

  switch (eventName) {
    case 'run_started':
      listener.onRunStarted(parsed as RunStartedEvent)
      break
    case 'compaction_completed':
      listener.onCompactionCompleted(parsed as CompactionCompletedEvent)
      break
    case 'reasoning_delta':
      listener.onReasoningDelta(parsed as ReasoningDeltaEvent)
      break
    case 'tool_started':
      listener.onToolStarted(parsed as ToolStartedEvent)
      break
    case 'tool_completed':
      listener.onToolCompleted(parsed as ToolCompletedEvent)
      break
    case 'tool_failed':
      listener.onToolFailed(parsed as ToolFailedEvent)
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
      break
    case 'run_failed':
      listener.onRunFailed(parsed as RunFailedEvent)
      break
  }

  if (sequence !== undefined) advanceRunEventSequence(eventName, value, sequence)
  return eventName === 'run_completed' || eventName === 'run_failed' ? 'terminal' : 'intermediate'
}

function objectValue(value: unknown): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new ApiError('BAD_SSE_FRAME', '服务端流事件缺少对象数据', 0)
  }
  return value as Record<string, unknown>
}

function isKnownRunEvent(eventName: string): eventName is KnownRunEventName {
  return (KNOWN_RUN_EVENTS as readonly string[]).includes(eventName)
}

function validateKnownRunEvent(
  eventName: KnownRunEventName,
  value: Record<string, unknown>,
): void {
  switch (eventName) {
    case 'run_started':
      requireString(value, 'conversationId')
      requireRun(value, 'run')
      requireEntry(value, 'userEntry', 'USER_MESSAGE')
      requireBoolean(value, 'isRetry')
      return
    case 'compaction_completed':
      requireString(value, 'conversationId')
      requireEntry(value, 'compactionEntry', 'COMPACTION')
      requireConversation(value, 'conversation')
      return
    case 'reasoning_delta':
    case 'assistant_delta':
      requireString(value, 'runId')
      requireString(value, 'delta')
      return
    case 'tool_started':
      requireToolIdentity(value)
      requireString(value, 'safeSummary')
      requireOptionalRequestDetail(value.requestDetail)
      return
    case 'tool_completed':
      requireToolIdentity(value)
      requireNumber(value, 'durationMillis')
      requireNullableString(value, 'provider')
      requireNumber(value, 'sourceCount')
      requireBoolean(value, 'truncated')
      requireBoolean(value, 'degraded')
      requireString(value, 'safeSummary')
      requireOptionalOutcomeDetail(value.outcomeDetail)
      return
    case 'tool_failed':
      requireToolIdentity(value)
      requireNumber(value, 'durationMillis')
      requireString(value, 'stableErrorCode')
      requireString(value, 'safeMessage')
      requireOptionalOutcomeDetail(value.outcomeDetail)
      return
    case 'assistant_completed':
      requireString(value, 'conversationId')
      requireEntry(value, 'assistantEntry', 'ASSISTANT_MESSAGE')
      return
    case 'title_updated':
      requireString(value, 'conversationId')
      requireEntry(value, 'titleEntry', 'TITLE')
      requireString(value, 'title')
      requireConversation(value, 'conversation')
      return
    case 'run_completed':
      requireString(value, 'conversationId')
      requireRun(value, 'run')
      requireConversation(value, 'conversation')
      return
    case 'run_failed':
      requireString(value, 'conversationId')
      requireString(value, 'errorCode')
      requireString(value, 'message')
      requireRun(value, 'run')
      requireConversation(value, 'conversation')
  }
}

function validateRunEventSequence(
  eventName: KnownRunEventName,
  value: Record<string, unknown>,
  sequence: RunEventSequence,
): void {
  if (sequence.terminal) {
    throwSequenceError('终态之后又收到业务事件')
  }
  if (eventName === 'run_started') {
    if (sequence.runId !== null) throwSequenceError('同一连接重复收到 run_started')
    const run = objectValue(value.run)
    const userEntry = objectValue(value.userEntry)
    const payload = objectValue(userEntry.payload)
    if (run.status !== 'RUNNING') throwSequenceError('run_started 未携带 RUNNING Run')
    if (run.conversationId !== value.conversationId || userEntry.conversationId !== value.conversationId) {
      throwSequenceError('run_started 的 Conversation 身份不一致')
    }
    if (payload.runId !== run.id) throwSequenceError('User Entry 与 Run 身份不一致')
    return
  }
  if (sequence.runId === null || sequence.conversationId === null) {
    throwSequenceError('run_started 之前收到业务事件')
  }

  if (typeof value.runId === 'string' && value.runId !== sequence.runId) {
    throwSequenceError('事件属于其他 Run')
  }
  if (typeof value.conversationId === 'string' && value.conversationId !== sequence.conversationId) {
    throwSequenceError('事件属于其他 Conversation')
  }
  if (sequence.titleUpdated && eventName !== 'run_completed') {
    throwSequenceError('title_updated 之后只允许 run_completed')
  }
  if (
    sequence.assistantCompleted &&
    eventName !== 'title_updated' &&
    eventName !== 'run_completed'
  ) {
    throwSequenceError('assistant_completed 之后收到非法中间事件')
  }

  switch (eventName) {
    case 'compaction_completed': {
      const conversation = objectValue(value.conversation)
      if (conversation.id !== sequence.conversationId) {
        throwSequenceError('Compaction 快照属于其他 Conversation')
      }
      return
    }
    case 'assistant_completed': {
      if (sequence.assistantCompleted) throwSequenceError('重复收到 assistant_completed')
      const entry = objectValue(value.assistantEntry)
      const payload = objectValue(entry.payload)
      if (entry.conversationId !== sequence.conversationId || payload.runId !== sequence.runId) {
        throwSequenceError('Assistant Entry 与当前 Run 身份不一致')
      }
      return
    }
    case 'title_updated': {
      if (!sequence.assistantCompleted) throwSequenceError('标题事件早于 Assistant 持久化')
      if (sequence.titleUpdated) throwSequenceError('重复收到 title_updated')
      const entry = objectValue(value.titleEntry)
      const payload = objectValue(entry.payload)
      const conversation = objectValue(value.conversation)
      if (
        entry.conversationId !== sequence.conversationId ||
        payload.sourceRunId !== sequence.runId ||
        conversation.id !== sequence.conversationId
      ) {
        throwSequenceError('标题事件与当前 Run 身份不一致')
      }
      return
    }
    case 'run_completed': {
      if (!sequence.assistantCompleted) throwSequenceError('run_completed 早于 Assistant 持久化')
      const run = objectValue(value.run)
      const conversation = objectValue(value.conversation)
      if (
        run.id !== sequence.runId ||
        run.conversationId !== sequence.conversationId ||
        run.status !== 'SUCCEEDED' ||
        run.resultStatus !== sequence.assistantCompletionStatus ||
        conversation.id !== sequence.conversationId
      ) {
        throwSequenceError('run_completed 未携带当前 SUCCEEDED Run')
      }
      return
    }
    case 'run_failed': {
      if (sequence.assistantCompleted) throwSequenceError('已持久化 Assistant 的 Run 不能再失败')
      const run = objectValue(value.run)
      const conversation = objectValue(value.conversation)
      if (
        run.id !== sequence.runId ||
        run.conversationId !== sequence.conversationId ||
        (run.status !== 'FAILED' && run.status !== 'INTERRUPTED') ||
        conversation.id !== sequence.conversationId
      ) {
        throwSequenceError('run_failed 未携带当前失败 Run')
      }
      return
    }
    default:
      return
  }
}

function advanceRunEventSequence(
  eventName: KnownRunEventName,
  value: Record<string, unknown>,
  sequence: RunEventSequence,
): void {
  if (eventName === 'run_started') {
    const run = objectValue(value.run)
    sequence.conversationId = value.conversationId as string
    sequence.runId = run.id as string
  } else if (eventName === 'assistant_completed') {
    sequence.assistantCompleted = true
    const entry = objectValue(value.assistantEntry)
    const payload = objectValue(entry.payload)
    sequence.assistantCompletionStatus =
      payload.completionStatus === 'INCOMPLETE_LENGTH' ? 'INCOMPLETE_LENGTH' : 'COMPLETE'
  } else if (eventName === 'title_updated') {
    sequence.titleUpdated = true
  } else if (eventName === 'run_completed' || eventName === 'run_failed') {
    sequence.terminal = true
  }
}

function throwSequenceError(message: string): never {
  throw new ApiError('BAD_SSE_SEQUENCE', `服务端流事件顺序非法：${message}`, 0)
}

function requireString(value: Record<string, unknown>, field: string): void {
  if (typeof value[field] !== 'string') {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件缺少字段：${field}`, 0)
  }
}

function requireNumber(value: Record<string, unknown>, field: string): void {
  if (typeof value[field] !== 'number' || !Number.isFinite(value[field])) {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件缺少数值字段：${field}`, 0)
  }
}

function requireBoolean(value: Record<string, unknown>, field: string): void {
  if (typeof value[field] !== 'boolean') {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件缺少布尔字段：${field}`, 0)
  }
}

function requireNullableString(value: Record<string, unknown>, field: string): void {
  if (value[field] !== null && typeof value[field] !== 'string') {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件字段类型错误：${field}`, 0)
  }
}

function requireNullableNumber(value: Record<string, unknown>, field: string): void {
  if (value[field] !== null && (typeof value[field] !== 'number' || !Number.isFinite(value[field]))) {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件字段类型错误：${field}`, 0)
  }
}

function requireEnum(
  value: Record<string, unknown>,
  field: string,
  allowed: readonly string[],
): void {
  if (typeof value[field] !== 'string' || !allowed.includes(value[field])) {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件枚举字段非法：${field}`, 0)
  }
}

function requireToolIdentity(value: Record<string, unknown>): void {
  requireString(value, 'runId')
  requireString(value, 'toolCallId')
  requireString(value, 'toolName')
}

function requireRun(value: Record<string, unknown>, field: string): void {
  const run = objectValue(value[field])
  requireString(run, 'id')
  requireString(run, 'conversationId')
  requireString(run, 'triggerEntryId')
  requireEnum(run, 'status', ['RUNNING', 'SUCCEEDED', 'FAILED', 'INTERRUPTED'])
  requireNullableString(run, 'errorCode')
  requireString(run, 'startedAt')
  requireNullableString(run, 'endedAt')
  if (run.status === 'SUCCEEDED') {
    requireEnum(run, 'resultStatus', ['COMPLETE', 'INCOMPLETE_LENGTH'])
  } else if (run.resultStatus !== undefined && run.resultStatus !== null) {
    throw new ApiError('BAD_SSE_FRAME', '非成功 Run 不能携带 resultStatus', 0)
  }
}

function requireConversation(value: Record<string, unknown>, field: string): void {
  const conversation = objectValue(value[field])
  requireString(conversation, 'id')
  requireString(conversation, 'workspaceId')
  requireString(conversation, 'title')
  requireNumber(conversation, 'historyFormatVersion')
  requireNullableString(conversation, 'activeLeafEntryId')
  requireNumber(conversation, 'lastConfirmedSeq')
  requireNullableString(conversation, 'latestCompactionEntryId')
  requireNullableNumber(conversation, 'latestCompactionSeq')
  requireNullableNumber(conversation, 'latestCompactionByteOffset')
  requireString(conversation, 'createdAt')
  requireString(conversation, 'updatedAt')
}

function requireEntry(
  value: Record<string, unknown>,
  field: string,
  expectedType: EntryType,
): void {
  const entry = objectValue(value[field])
  requireNumber(entry, 'formatVersion')
  requireString(entry, 'conversationId')
  requireString(entry, 'id')
  requireNumber(entry, 'seq')
  requireNullableString(entry, 'parentId')
  requireEnum(entry, 'type', ['USER_MESSAGE', 'ASSISTANT_MESSAGE', 'COMPACTION', 'TITLE'])
  requireString(entry, 'createdAt')
  if (entry.type !== expectedType) {
    throw new ApiError('BAD_SSE_FRAME', `服务端流事件 Entry 类型错误：${field}`, 0)
  }
  const payload = objectValue(entry.payload)
  if (expectedType === 'USER_MESSAGE' || expectedType === 'ASSISTANT_MESSAGE') {
    requireString(payload, 'text')
    requireString(payload, 'runId')
  }
  if (expectedType === 'ASSISTANT_MESSAGE' && payload.trace !== undefined) {
    requireTrace(payload.trace)
  }
  if (expectedType === 'ASSISTANT_MESSAGE' && payload.completionStatus !== undefined) {
    requireEnum(payload, 'completionStatus', ['COMPLETE', 'INCOMPLETE_LENGTH'])
    if (payload.completionDetailCode !== undefined) {
      requireNullableString(payload, 'completionDetailCode')
    }
  }
  if (expectedType === 'USER_MESSAGE' && payload.action !== undefined) {
    requireEnum(payload, 'action', ['MESSAGE', 'CONTINUE_GENERATION'])
    if (payload.action === 'CONTINUE_GENERATION') {
      requireString(payload, 'sourceAssistantEntryId')
    } else if (payload.sourceAssistantEntryId !== undefined && payload.sourceAssistantEntryId !== null) {
      throw new ApiError('BAD_SSE_FRAME', '普通 User Entry 不能携带继续生成来源', 0)
    }
  }
  if (expectedType === 'ASSISTANT_MESSAGE') {
    if (payload.citations !== undefined) requireCitations(payload.citations)
    if (payload.retrievedSources !== undefined) requireRetrievedSources(payload.retrievedSources)
    if (payload.callChains !== undefined) requireCallChains(payload.callChains)
  }
  if (expectedType === 'TITLE') {
    requireString(payload, 'title')
    requireString(payload, 'sourceRunId')
    requireString(payload, 'sourceAssistantEntryId')
  }
}

function requireCallChains(raw: unknown): void {
  if (!Array.isArray(raw) || raw.length > 1) {
    throw new ApiError('BAD_SSE_FRAME', '服务端调用链引用不是单项数组', 0)
  }
  for (const rawReference of raw) {
    const reference = objectValue(rawReference)
    requireString(reference, 'id')
    requireString(reference, 'repositoryId')
    requireString(reference, 'name')
    requireNumber(reference, 'nodeCount')
    requireNumber(reference, 'edgeCount')
    if ((reference.name as string).trim() === ''
      || (reference.nodeCount as number) < 2
      || (reference.edgeCount as number) < 1) {
      throw new ApiError('BAD_SSE_FRAME', '服务端调用链引用字段无效', 0)
    }
  }
}

function requireTrace(raw: unknown): void {
  if (!Array.isArray(raw)) {
    throw new ApiError('BAD_SSE_FRAME', '服务端流事件 Trace 不是数组', 0)
  }
  for (const rawItem of raw) {
    const item = objectValue(rawItem)
    requireEnum(item, 'kind', ['REASONING', 'TOOL'])
    requireBoolean(item, 'truncated')
    if (item.kind === 'REASONING') {
      requireString(item, 'text')
    } else {
      requireString(item, 'toolCallId')
      requireString(item, 'toolName')
      requireEnum(item, 'toolStatus', ['RUNNING', 'COMPLETED', 'FAILED'])
      requireString(item, 'safeSummary')
      requireNullableString(item, 'stableErrorCode')
      requireOptionalRequestDetail(item.requestDetail)
      requireOptionalOutcomeDetail(item.outcomeDetail)
    }
  }
}

function requireOptionalRequestDetail(raw: unknown): void {
  if (raw === undefined || raw === null) return
  const detail = objectValue(raw)
  requireString(detail, 'querySummary')
  requireBoolean(detail, 'querySummaryTruncated')
  if (detail.freshness !== undefined) requireNullableString(detail, 'freshness')
  requireBoolean(detail, 'freshnessDefaulted')
  if (detail.count !== undefined) requireNullableNumber(detail, 'count')
  requireBoolean(detail, 'countDefaulted')
  const count = detail.count
  if (count !== null && count !== undefined) {
    if (typeof count !== 'number' || !Number.isInteger(count) || count < 1) {
      throw new ApiError('BAD_SSE_FRAME', '工具请求数量必须为正整数', 0)
    }
  }
}

function requireOptionalOutcomeDetail(raw: unknown): void {
  if (raw === undefined || raw === null) return
  const detail = objectValue(raw)
  if (detail.provider !== undefined) requireNullableString(detail, 'provider')
  if (detail.resultStatus !== undefined) {
    if (detail.resultStatus !== null) {
      requireEnum(detail, 'resultStatus', ['SUCCESS', 'DEGRADED', 'EMPTY', 'UNAVAILABLE'])
    }
  }
  if (detail.stableReasonCode !== undefined) requireNullableString(detail, 'stableReasonCode')
  if (detail.sourceCount !== undefined) {
    requireNullableNumber(detail, 'sourceCount')
    const sourceCount = detail.sourceCount
    if (sourceCount !== null && sourceCount !== undefined) {
      if (typeof sourceCount !== 'number' || !Number.isInteger(sourceCount) || sourceCount < 0) {
        throw new ApiError('BAD_SSE_FRAME', '工具来源数必须为非负整数', 0)
      }
    }
  }
  requireNumber(detail, 'durationMillis')
  requireBoolean(detail, 'degraded')
  requireBoolean(detail, 'resultTruncated')
  if (detail.estimatedResultTokens !== undefined) {
    requireNullableNumber(detail, 'estimatedResultTokens')
    const estimatedResultTokens = detail.estimatedResultTokens
    if (estimatedResultTokens !== null &&
      (typeof estimatedResultTokens !== 'number' || !Number.isInteger(estimatedResultTokens) || estimatedResultTokens < 0)) {
      throw new ApiError('BAD_SSE_FRAME', '结果 token 估算必须为非负整数', 0)
    }
  }
  if (detail.remainingInputTokens !== undefined) {
    requireNullableNumber(detail, 'remainingInputTokens')
    const remainingInputTokens = detail.remainingInputTokens
    if (remainingInputTokens !== null &&
      (typeof remainingInputTokens !== 'number' || !Number.isInteger(remainingInputTokens) || remainingInputTokens < 0)) {
      throw new ApiError('BAD_SSE_FRAME', '剩余输入 token 必须为非负整数', 0)
    }
  }
  if (detail.contextCleaned !== undefined) requireBoolean(detail, 'contextCleaned')
}

function requireCitations(raw: unknown): void {
  if (!Array.isArray(raw)) {
    throw new ApiError('BAD_SSE_FRAME', '服务端流事件 Citation 不是数组', 0)
  }
  for (const rawCitation of raw) {
    const citation = objectValue(rawCitation)
    requireEnum(citation, 'kind', ['local', 'web'])
    requireString(citation, 'referenceId')
    if (citation.citationNote !== undefined) requireNullableString(citation, 'citationNote')
    if (citation.kind === 'local') {
      requireString(citation, 'evidenceId')
      requireString(citation, 'revisionId')
      requireString(citation, 'documentName')
      requireString(citation, 'location')
    } else {
      requireString(citation, 'provider')
      requireString(citation, 'title')
      requireString(citation, 'url')
      requireString(citation, 'site')
      if (citation.dateLabel !== undefined) requireNullableString(citation, 'dateLabel')
      requireString(citation, 'retrievedAt')
    }
  }
}

function requireRetrievedSources(raw: unknown): void {
  if (!Array.isArray(raw)) {
    throw new ApiError('BAD_SSE_FRAME', '服务端流事件 Retrieved Source 不是数组', 0)
  }
  for (const rawSource of raw) {
    const source = objectValue(rawSource)
    requireEnum(source, 'kind', ['local', 'web'])
    requireString(source, 'referenceId')
    requireString(source, 'retrievedAt')
    requireString(source, 'excerptKind')
    if (source.sourceExcerpt !== undefined) requireNullableString(source, 'sourceExcerpt')
    if (source.originToolCallId !== undefined) requireNullableString(source, 'originToolCallId')
    if (source.resultPosition !== undefined) {
      requireNullableNumber(source, 'resultPosition')
      const position = source.resultPosition
      if (position !== null && position !== undefined) {
        if (typeof position !== 'number' || !Number.isInteger(position) || position < 1) {
          throw new ApiError('BAD_SSE_FRAME', '来源结果位置必须为正整数', 0)
        }
      }
    }
    if (source.providerRank !== undefined) {
      requireNullableNumber(source, 'providerRank')
      const providerRank = source.providerRank
      if (providerRank !== null && providerRank !== undefined) {
        if (typeof providerRank !== 'number' || !Number.isInteger(providerRank) || providerRank < 1) {
          throw new ApiError('BAD_SSE_FRAME', 'Provider 位次必须为正整数', 0)
        }
      }
    }
    if (source.kind === 'local') {
      requireString(source, 'evidenceId')
      requireString(source, 'revisionId')
      requireString(source, 'documentName')
      requireString(source, 'location')
    } else {
      requireString(source, 'provider')
      requireString(source, 'title')
      requireString(source, 'url')
      requireString(source, 'site')
      if (source.dateLabel !== undefined) requireNullableString(source, 'dateLabel')
    }
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

export function streamContinue(
  conversationId: string,
  assistantEntryId: string,
  listener: RunStreamListener,
): Promise<void> {
  return streamRun(
    `/api/conversations/${conversationId}/entries/${assistantEntryId}/continue`,
    { method: 'POST' },
    listener,
  )
}
