import type {
  AssistantDeltaEvent,
  Entry,
  ReasoningDeltaEvent,
  RunStartedEvent,
  RunTraceItem,
  ToolCompletedEvent,
  ToolFailedEvent,
  ToolStartedEvent,
  ToolTraceItem,
  ToolOutcomeDetail,
} from './conversationApi.ts'

export const MAX_TRACE_ITEMS = 64
export const MAX_REASONING_TRACE_CHARS = 32_768
export const MAX_TOOL_TRACE_SUMMARY_CHARS = 512

/** 单个 Conversation 当前 Run 的临时状态；durable Assistant 到达后由历史 Entry 取代。 */
export type ActiveRunState = {
  runId: string | null
  userEntry: Entry | null
  assistantText: string
  trace: RunTraceItem[]
  toolIndexes: Record<string, number>
  status: 'starting' | 'running' | 'completed' | 'failed'
  newContentCount: number
  traceExpanded: boolean
  traceManuallyToggled: boolean
}

export type ActiveRunAction =
  | { type: 'run_started'; event: RunStartedEvent }
  | { type: 'reasoning_delta'; event: ReasoningDeltaEvent; following: boolean }
  | { type: 'tool_started'; event: ToolStartedEvent; following: boolean }
  | { type: 'tool_completed'; event: ToolCompletedEvent; following: boolean }
  | { type: 'tool_failed'; event: ToolFailedEvent; following: boolean }
  | { type: 'assistant_delta'; event: AssistantDeltaEvent; following: boolean }
  | { type: 'run_completed' }
  | { type: 'run_failed' }
  | { type: 'toggle_trace' }
  | { type: 'restore_follow' }

export function createActiveRunState(): ActiveRunState {
  return {
    runId: null,
    userEntry: null,
    assistantText: '',
    trace: [],
    toolIndexes: {},
    status: 'starting',
    newContentCount: 0,
    traceExpanded: true,
    traceManuallyToggled: false,
  }
}

/** 只有尚未进入终态的临时状态才占用该 Conversation 的发送槽位。 */
export function isActiveRun(state: ActiveRunState | undefined): boolean {
  return state?.status === 'starting' || state?.status === 'running'
}

/**
 * Run Trace 的唯一前端更新入口：reasoning 连续合并，工具按 Tool Call ID 原位更新，
 * 并在服务端合同之外再守住同一组展示上限。
 */
export function reduceActiveRun(state: ActiveRunState, action: ActiveRunAction): ActiveRunState {
  switch (action.type) {
    case 'run_started':
      return {
        ...state,
        runId: action.event.run.id,
        userEntry: action.event.userEntry,
        status: 'running',
      }
    case 'reasoning_delta': {
      if (!accepts(state, action.event.runId)) return state
      return withNewContent(
        { ...state, trace: appendReasoning(state.trace, action.event.delta) },
        action.following,
      )
    }
    case 'tool_started': {
      if (!accepts(state, action.event.runId)) return state
      const updated = upsertTool(state, action.event.toolCallId, (previous) => {
        const summary = boundText(action.event.safeSummary || '工具执行中', MAX_TOOL_TRACE_SUMMARY_CHARS)
        return {
          kind: 'TOOL',
          toolCallId: action.event.toolCallId,
          toolName: action.event.toolName,
          toolStatus: 'RUNNING',
          safeSummary: summary.text,
          stableErrorCode: null,
          truncated: summary.truncated,
          requestDetail: action.event.requestDetail ?? previous?.requestDetail ?? null,
          outcomeDetail: previous?.outcomeDetail ?? null,
        }
      })
      return withNewContent(updated, action.following)
    }
    case 'tool_completed': {
      if (!accepts(state, action.event.runId)) return state
      const updated = upsertTool(state, action.event.toolCallId, (previous) => {
        const summary = boundText(action.event.safeSummary || '工具执行完成', MAX_TOOL_TRACE_SUMMARY_CHARS)
        return {
          kind: 'TOOL',
          toolCallId: action.event.toolCallId,
          toolName: action.event.toolName,
          toolStatus: 'COMPLETED',
          safeSummary: summary.text,
          stableErrorCode: null,
          truncated:
            (previous?.truncated ?? false) ||
            (action.event.outcomeDetail === undefined && action.event.truncated) ||
            summary.truncated,
          requestDetail: previous?.requestDetail ?? null,
          outcomeDetail:
            action.event.outcomeDetail ?? previous?.outcomeDetail ?? legacyCompletedOutcome(action.event),
        }
      })
      return withNewContent(updated, action.following)
    }
    case 'tool_failed': {
      if (!accepts(state, action.event.runId)) return state
      const updated = upsertTool(state, action.event.toolCallId, (previous) => {
        const summary = boundText(action.event.safeMessage || '工具执行失败', MAX_TOOL_TRACE_SUMMARY_CHARS)
        return {
          kind: 'TOOL',
          toolCallId: action.event.toolCallId,
          toolName: action.event.toolName,
          toolStatus: 'FAILED',
          safeSummary: summary.text,
          stableErrorCode: action.event.stableErrorCode,
          truncated: (previous?.truncated ?? false) || summary.truncated,
          requestDetail: previous?.requestDetail ?? null,
          outcomeDetail: action.event.outcomeDetail ?? previous?.outcomeDetail ?? legacyFailedOutcome(action.event),
        }
      })
      return withNewContent(updated, action.following)
    }
    case 'assistant_delta':
      if (!accepts(state, action.event.runId)) return state
      return withNewContent(
        { ...state, assistantText: state.assistantText + action.event.delta },
        action.following,
      )
    case 'run_completed':
      return {
        ...state,
        status: 'completed',
        traceExpanded: state.traceManuallyToggled ? state.traceExpanded : false,
      }
    case 'run_failed':
      return {
        ...state,
        status: 'failed',
        traceExpanded: state.traceManuallyToggled ? state.traceExpanded : false,
      }
    case 'toggle_trace':
      return {
        ...state,
        traceExpanded: !state.traceExpanded,
        traceManuallyToggled: true,
      }
    case 'restore_follow':
      return state.newContentCount === 0 ? state : { ...state, newContentCount: 0 }
  }
}

function accepts(state: ActiveRunState, runId: string): boolean {
  return state.runId !== null && state.runId === runId
}

function withNewContent(state: ActiveRunState, following: boolean): ActiveRunState {
  return following ? state : { ...state, newContentCount: state.newContentCount + 1 }
}

function appendReasoning(trace: RunTraceItem[], raw: string): RunTraceItem[] {
  if (raw === '') return trace
  const used = trace.reduce(
    (total, item) => total + (item.kind === 'REASONING' ? item.text.length : 0),
    0,
  )
  const bounded = boundText(raw, Math.max(0, MAX_REASONING_TRACE_CHARS - used))
  const next = [...trace]
  const last = next.at(-1)
  if (bounded.text !== '') {
    if (last?.kind === 'REASONING') {
      next[next.length - 1] = {
        ...last,
        text: last.text + bounded.text,
        truncated: last.truncated || bounded.truncated,
      }
    } else if (next.length < MAX_TRACE_ITEMS) {
      next.push({ kind: 'REASONING', text: bounded.text, truncated: bounded.truncated })
    } else {
      markLastTruncated(next)
    }
  } else if (raw !== '') {
    const lastReasoning = next.findLastIndex((item) => item.kind === 'REASONING')
    if (lastReasoning >= 0) next[lastReasoning] = { ...next[lastReasoning], truncated: true }
  }
  return next
}

function upsertTool(
  state: ActiveRunState,
  toolCallId: string,
  create: (previous?: ToolTraceItem) => ToolTraceItem,
): ActiveRunState {
  const index = state.toolIndexes[toolCallId]
  if (index !== undefined) {
    const trace = [...state.trace]
    const previous = trace[index]?.kind === 'TOOL' ? trace[index] : undefined
    trace[index] = create(previous)
    return { ...state, trace }
  }
  if (state.trace.length >= MAX_TRACE_ITEMS) {
    const trace = [...state.trace]
    markLastTruncated(trace)
    return { ...state, trace }
  }
  const trace = [...state.trace, create()]
  return {
    ...state,
    trace,
    toolIndexes: { ...state.toolIndexes, [toolCallId]: trace.length - 1 },
  }
}

function markLastTruncated(trace: RunTraceItem[]): void {
  const last = trace.at(-1)
  if (last !== undefined) trace[trace.length - 1] = { ...last, truncated: true }
}

function boundText(raw: string, maximum: number): { text: string; truncated: boolean } {
  if (raw.length <= maximum) return { text: raw, truncated: false }
  if (maximum <= 0) return { text: '', truncated: true }
  let end = maximum
  const code = raw.charCodeAt(end - 1)
  if (code >= 0xd800 && code <= 0xdbff) end -= 1
  return { text: raw.slice(0, end), truncated: true }
}

function legacyCompletedOutcome(event: ToolCompletedEvent): ToolOutcomeDetail {
  return {
    provider: event.provider,
    resultStatus: null,
    stableReasonCode: null,
    sourceCount: event.sourceCount,
    durationMillis: event.durationMillis,
    degraded: event.degraded,
    resultTruncated: event.truncated,
  }
}

function legacyFailedOutcome(event: ToolFailedEvent): ToolOutcomeDetail {
  return {
    provider: null,
    resultStatus: null,
    stableReasonCode: null,
    sourceCount: null,
    durationMillis: event.durationMillis,
    degraded: false,
    resultTruncated: false,
  }
}
