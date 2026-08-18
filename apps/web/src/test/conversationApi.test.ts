import { describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  createRunEventDispatcher,
  dispatchRunEvent,
  type Conversation,
  type Entry,
  type Run,
  type RunStreamListener,
} from '../conversationApi.ts'

describe('SSE event dispatch', () => {
  it('ignores an unknown optional event without invoking listeners', () => {
    const listener = listenerStub()
    expect(dispatchRunEvent('future_progress', { value: 1 }, listener)).toBe('ignored')
    expect(listener.onAssistantDelta).not.toHaveBeenCalled()
  })

  it('rejects a malformed known event', () => {
    expect(() => dispatchRunEvent('reasoning_delta', { delta: '分析' }, listenerStub()))
      .toThrowError(ApiError)
    expect(() =>
      dispatchRunEvent(
        'tool_completed',
        { runId: 'run-1', toolCallId: 'call-1', toolName: 'search' },
        listenerStub(),
      ),
    ).toThrowError(ApiError)
  })

  it('accepts one ordered terminal and rejects early, duplicate, or post-terminal events', () => {
    const listener = listenerStub()
    const dispatch = createRunEventDispatcher(listener)
    dispatch('run_started', runStarted())
    expect(() => dispatch('run_completed', runCompleted())).toThrowError(ApiError)

    dispatch('assistant_completed', assistantCompleted())
    expect(dispatch('run_completed', runCompleted())).toBe('terminal')
    expect(() => dispatch('assistant_delta', { runId: 'run-1', delta: 'late' }))
      .toThrowError(ApiError)
    expect(() => dispatch('run_failed', runFailed())).toThrowError(ApiError)
    expect(listener.onRunCompleted).toHaveBeenCalledOnce()
    expect(listener.onRunFailed).not.toHaveBeenCalled()
  })

  it('accepts optional retrieved sources and citation notes on Assistant entries', () => {
    const listener = listenerStub()
    dispatchRunEvent(
      'assistant_completed',
      {
        conversationId: conversation.id,
        assistantEntry: {
          ...assistantCompleted().assistantEntry,
          payload: {
            text: '依据 [L1]',
            runId: 'run-1',
            citations: [
              {
                kind: 'local',
                referenceId: 'L1',
                evidenceId: 'evidence-1',
                revisionId: 'revision-1',
                documentName: 'manual.md',
                location: 'p1',
                citationNote: '支持说明',
              },
            ],
            retrievedSources: [
              {
                kind: 'local',
                referenceId: 'L1',
                evidenceId: 'evidence-1',
                revisionId: 'revision-1',
                documentName: 'manual.md',
                location: 'p1',
                retrievedAt: '2026-08-18T00:00:00Z',
                excerptKind: 'LOCAL_EVIDENCE',
                sourceExcerpt: '摘录',
              },
            ],
          },
        },
      },
      listener,
    )

    expect(listener.onAssistantCompleted).toHaveBeenCalledOnce()
  })

  it('accepts optional tool request/outcome detail and source provenance', () => {
    const listener = listenerStub()
    dispatchRunEvent(
      'tool_started',
      {
        runId: 'run-1',
        toolCallId: 'call-1',
        toolName: 'search_web_bocha',
        safeSummary: 'salmon',
        requestDetail: {
          querySummary: 'salmon',
          querySummaryTruncated: false,
          freshness: 'any',
          freshnessDefaulted: true,
          count: 5,
          countDefaulted: true,
        },
      },
      listener,
    )
    dispatchRunEvent(
      'tool_completed',
      {
        runId: 'run-1',
        toolCallId: 'call-1',
        toolName: 'search_web_bocha',
        durationMillis: 4,
        provider: 'BOCHA',
        sourceCount: 1,
        truncated: false,
        degraded: false,
        safeSummary: '已完成',
        outcomeDetail: {
          provider: 'BOCHA',
          resultStatus: 'SUCCESS',
          stableReasonCode: 'COMPLETE',
          sourceCount: 1,
          durationMillis: 4,
          degraded: false,
          resultTruncated: true,
        },
      },
      listener,
    )
    expect(listener.onToolStarted).toHaveBeenCalledWith(expect.objectContaining({ requestDetail: expect.any(Object) }))
    expect(listener.onToolCompleted).toHaveBeenCalledWith(expect.objectContaining({ outcomeDetail: expect.any(Object) }))
  })
})

const conversation: Conversation = {
  id: 'conversation-1',
  workspaceId: 'workspace-1',
  title: '新对话',
  historyFormatVersion: 1,
  activeLeafEntryId: 'assistant-1',
  lastConfirmedSeq: 2,
  latestCompactionEntryId: null,
  latestCompactionSeq: null,
  latestCompactionByteOffset: null,
  createdAt: '2026-08-18T00:00:00Z',
  updatedAt: '2026-08-18T00:00:02Z',
}

function run(status: Run['status']): Run {
  return {
    id: 'run-1',
    conversationId: conversation.id,
    triggerEntryId: 'user-1',
    status,
    errorCode: status === 'FAILED' ? 'CHAT_MODEL_FAILED' : null,
    startedAt: '2026-08-18T00:00:00Z',
    endedAt: status === 'RUNNING' ? null : '2026-08-18T00:00:02Z',
    resultStatus: status === 'SUCCEEDED' ? 'COMPLETE' : null,
  }
}

function runStarted() {
  const userEntry: Entry = {
    formatVersion: 1,
    conversationId: conversation.id,
    id: 'user-1',
    seq: 1,
    parentId: null,
    type: 'USER_MESSAGE',
    createdAt: '2026-08-18T00:00:00Z',
    payload: { text: '你好', runId: 'run-1' },
  }
  return { conversationId: conversation.id, run: run('RUNNING'), userEntry, isRetry: false }
}

function assistantCompleted() {
  const assistantEntry: Entry = {
    formatVersion: 1,
    conversationId: conversation.id,
    id: 'assistant-1',
    seq: 2,
    parentId: 'user-1',
    type: 'ASSISTANT_MESSAGE',
    createdAt: '2026-08-18T00:00:01Z',
    payload: { text: '回答', runId: 'run-1', trace: [] },
  }
  return { conversationId: conversation.id, assistantEntry }
}

function runCompleted() {
  return { conversationId: conversation.id, run: run('SUCCEEDED'), conversation }
}

function runFailed() {
  return {
    conversationId: conversation.id,
    errorCode: 'CHAT_MODEL_FAILED',
    message: '回答失败',
    run: run('FAILED'),
    conversation,
  }
}

function listenerStub(): RunStreamListener {
  return {
    onRunStarted: vi.fn(),
    onCompactionCompleted: vi.fn(),
    onReasoningDelta: vi.fn(),
    onToolStarted: vi.fn(),
    onToolCompleted: vi.fn(),
    onToolFailed: vi.fn(),
    onAssistantDelta: vi.fn(),
    onAssistantCompleted: vi.fn(),
    onTitleUpdated: vi.fn(),
    onRunCompleted: vi.fn(),
    onRunFailed: vi.fn(),
  }
}
