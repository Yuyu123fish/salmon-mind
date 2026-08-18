import { describe, expect, it } from 'vitest'
import {
  MAX_REASONING_TRACE_CHARS,
  MAX_TRACE_ITEMS,
  createActiveRunState,
  isActiveRun,
  reduceActiveRun,
  type ActiveRunState,
} from '../runState.ts'
import type { Entry, Run, RunStartedEvent } from '../conversationApi.ts'

const RUN_ID = 'run-1'

describe('run state reducer', () => {
  it('merges consecutive reasoning and updates multiple tools by call id in place', () => {
    let state = startedState()
    state = reasoning(state, '先分析', true)
    state = reasoning(state, '问题', true)
    state = reduceActiveRun(state, {
      type: 'tool_started',
      following: true,
      event: { runId: RUN_ID, toolCallId: 'call-1', toolName: 'search_a', safeSummary: '查询 A' },
    })
    state = reduceActiveRun(state, {
      type: 'tool_started',
      following: true,
      event: { runId: RUN_ID, toolCallId: 'call-2', toolName: 'search_b', safeSummary: '查询 B' },
    })
    state = reduceActiveRun(state, {
      type: 'tool_completed',
      following: true,
      event: {
        runId: RUN_ID,
        toolCallId: 'call-1',
        toolName: 'search_a',
        durationMillis: 8,
        provider: 'A',
        sourceCount: 2,
        truncated: false,
        degraded: false,
        safeSummary: 'A · 2 个来源',
      },
    })
    state = reduceActiveRun(state, {
      type: 'tool_failed',
      following: true,
      event: {
        runId: RUN_ID,
        toolCallId: 'call-2',
        toolName: 'search_b',
        durationMillis: 9,
        stableErrorCode: 'TOOL_FAILED',
        safeMessage: 'B 暂不可用',
      },
    })
    state = reasoning(state, '拿到结果后作答', true)

    expect(state.trace).toEqual([
      { kind: 'REASONING', text: '先分析问题', truncated: false },
      expect.objectContaining({ kind: 'TOOL', toolCallId: 'call-1', toolStatus: 'COMPLETED' }),
      expect.objectContaining({ kind: 'TOOL', toolCallId: 'call-2', toolStatus: 'FAILED' }),
      { kind: 'REASONING', text: '拿到结果后作答', truncated: false },
    ])
  })

  it('bounds reasoning and item count, and tracks only paused current-view content', () => {
    let state = startedState()
    state = reasoning(state, 'x'.repeat(MAX_REASONING_TRACE_CHARS + 9), false)
    expect(state.trace[0]).toEqual({
      kind: 'REASONING',
      text: 'x'.repeat(MAX_REASONING_TRACE_CHARS),
      truncated: true,
    })
    expect(state.newContentCount).toBe(1)

    for (let index = 0; index < MAX_TRACE_ITEMS + 5; index += 1) {
      state = reduceActiveRun(state, {
        type: 'tool_started',
        following: true,
        event: {
          runId: RUN_ID,
          toolCallId: `call-${index}`,
          toolName: 'search',
          safeSummary: '安全摘要',
        },
      })
    }
    expect(state.trace).toHaveLength(MAX_TRACE_ITEMS)
    expect(state.trace.at(-1)?.truncated).toBe(true)

    state = reduceActiveRun(state, {
      type: 'assistant_delta',
      following: false,
      event: { runId: RUN_ID, delta: '回答' },
    })
    expect(state.newContentCount).toBe(2)
    state = reduceActiveRun(state, { type: 'restore_follow' })
    expect(state.newContentCount).toBe(0)
  })

  it('releases the active slot at either terminal state', () => {
    const running = startedState()
    expect(isActiveRun(running)).toBe(true)
    expect(isActiveRun(reduceActiveRun(running, { type: 'run_completed' }))).toBe(false)
    expect(isActiveRun(reduceActiveRun(running, { type: 'run_failed' }))).toBe(false)
  })

  it('folds by default on completion but preserves an explicit run-local choice', () => {
    const running = startedState()
    expect(reduceActiveRun(running, { type: 'run_completed' }).traceExpanded).toBe(false)

    const folded = reduceActiveRun(running, { type: 'toggle_trace' })
    const explicitlyExpanded = reduceActiveRun(folded, { type: 'toggle_trace' })
    expect(reduceActiveRun(explicitlyExpanded, { type: 'run_completed' }).traceExpanded).toBe(true)
  })
})

function reasoning(state: ActiveRunState, delta: string, following: boolean): ActiveRunState {
  return reduceActiveRun(state, {
    type: 'reasoning_delta',
    following,
    event: { runId: RUN_ID, delta },
  })
}

function startedState(): ActiveRunState {
  return reduceActiveRun(createActiveRunState(), { type: 'run_started', event: runStarted() })
}

function runStarted(): RunStartedEvent {
  const run: Run = {
    id: RUN_ID,
    conversationId: 'conversation-1',
    triggerEntryId: 'user-1',
    status: 'RUNNING',
    errorCode: null,
    startedAt: '2026-08-18T00:00:00Z',
    endedAt: null,
  }
  const userEntry: Entry = {
    formatVersion: 1,
    conversationId: 'conversation-1',
    id: 'user-1',
    seq: 1,
    parentId: null,
    type: 'USER_MESSAGE',
    createdAt: '2026-08-18T00:00:00Z',
    payload: { text: '你好', runId: RUN_ID },
  }
  return { conversationId: 'conversation-1', run, userEntry, isRetry: false }
}
