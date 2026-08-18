import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  ConversationDetail,
  ConversationSummary,
  RunStreamListener,
} from '../conversationApi.ts'

const api = vi.hoisted(() => ({
  fetchCurrentWorkspace: vi.fn(),
  fetchConversations: vi.fn(),
  fetchConversation: vi.fn(),
  streamSend: vi.fn(),
  streamRetry: vi.fn(),
}))

vi.mock('../workspaceApi.ts', () => ({ fetchCurrentWorkspace: api.fetchCurrentWorkspace }))
vi.mock('../conversationApi.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../conversationApi.ts')>()),
  fetchConversations: api.fetchConversations,
  fetchConversation: api.fetchConversation,
  streamSend: api.streamSend,
  streamRetry: api.streamRetry,
}))
vi.mock('../KnowledgeView.tsx', () => ({ default: () => null }))

import App from '../App.tsx'

const summary: ConversationSummary = {
  id: 'conversation-1',
  workspaceId: 'workspace-1',
  title: '滚动验收',
  latestRun: null,
  createdAt: '2026-08-18T00:00:00Z',
  updatedAt: '2026-08-18T00:00:00Z',
}

const detail: ConversationDetail = {
  conversation: {
    id: summary.id,
    workspaceId: summary.workspaceId,
    title: summary.title,
    historyFormatVersion: 1,
    activeLeafEntryId: null,
    lastConfirmedSeq: 0,
    latestCompactionEntryId: null,
    latestCompactionSeq: null,
    latestCompactionByteOffset: null,
    createdAt: summary.createdAt,
    updatedAt: summary.updatedAt,
  },
  activePath: [],
  pendingRun: null,
}

describe('App follow mode', () => {
  let listener: RunStreamListener | undefined
  let scrollTo: ReturnType<typeof vi.fn>

  beforeEach(() => {
    localStorage.clear()
    listener = undefined
    api.fetchCurrentWorkspace.mockResolvedValue({
      id: 'workspace-1',
      name: '默认工作区',
      createdAt: summary.createdAt,
    })
    api.fetchConversations.mockResolvedValue([summary])
    api.fetchConversation.mockResolvedValue(detail)
    api.streamSend.mockImplementation(
      (_conversationId: string, _text: string, received: RunStreamListener) => {
        listener = received
        return new Promise<void>(() => undefined)
      },
    )
    scrollTo = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
      configurable: true,
      value: scrollTo,
    })
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: vi.fn(),
    })
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0)
      return 1
    })
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  it('does not scroll for paused deltas and returns once when the prompt is clicked', async () => {
    const view = render(<App />)
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '开始流式回答' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(listener).toBeDefined())

    act(() => {
      listener!.onRunStarted({
        conversationId: summary.id,
        run: {
          id: 'run-1',
          conversationId: summary.id,
          triggerEntryId: 'user-1',
          status: 'RUNNING',
          errorCode: null,
          startedAt: summary.createdAt,
          endedAt: null,
        },
        userEntry: {
          formatVersion: 1,
          conversationId: summary.id,
          id: 'user-1',
          seq: 1,
          parentId: null,
          type: 'USER_MESSAGE',
          createdAt: summary.createdAt,
          payload: { text: '开始流式回答', runId: 'run-1' },
        },
        isRetry: false,
      })
    })

    const messages = view.container.querySelector<HTMLElement>('.messages')!
    Object.defineProperties(messages, {
      scrollHeight: { configurable: true, value: 1_000 },
      clientHeight: { configurable: true, value: 300 },
      scrollTop: { configurable: true, writable: true, value: 100 },
    })
    fireEvent.scroll(messages)
    scrollTo.mockClear()

    act(() => {
      listener!.onAssistantDelta({ runId: 'run-1', delta: '第一段' })
      listener!.onAssistantDelta({ runId: 'run-1', delta: '第二段' })
    })

    const prompt = await screen.findByRole('button', { name: /有新内容 · 2/ })
    expect(scrollTo).not.toHaveBeenCalled()
    fireEvent.click(prompt)
    expect(scrollTo).toHaveBeenCalledOnce()
    expect(screen.queryByRole('button', { name: /有新内容/ })).toBeNull()

    act(() => {
      listener!.onReasoningDelta({ runId: 'run-1', delta: '失败前的安全轨迹' })
      listener!.onRunFailed({
        conversationId: summary.id,
        errorCode: 'CHAT_MODEL_FAILED',
        message: '回答失败',
        run: completedRun('FAILED'),
        conversation: detail.conversation,
      })
    })
    expect(await screen.findByText('本次回答失败，以上临时内容未持久化')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /思考与工具/ }))
    expect(screen.getByText('失败前的安全轨迹')).toBeVisible()
  })

  it('preserves an explicit trace choice when the durable Assistant replaces streaming UI', async () => {
    const view = render(<App />)
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '完成回答' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(listener).toBeDefined())
    act(() => {
      listener!.onRunStarted(runStartedEvent('完成回答'))
      listener!.onReasoningDelta({ runId: 'run-1', delta: '保留展开状态' })
    })

    const toggle = screen.getByRole('button', { name: /思考与工具/ })
    fireEvent.click(toggle)
    fireEvent.click(toggle)

    act(() => {
      listener!.onAssistantCompleted({
        conversationId: summary.id,
        assistantEntry: {
          formatVersion: 1,
          conversationId: summary.id,
          id: 'assistant-1',
          seq: 2,
          parentId: 'user-1',
          type: 'ASSISTANT_MESSAGE',
          createdAt: '2026-08-18T00:00:01Z',
          payload: {
            text: '完成',
            runId: 'run-1',
            trace: [{ kind: 'REASONING', text: '保留展开状态', truncated: false }],
          },
        },
      })
      listener!.onRunCompleted({
        conversationId: summary.id,
        run: completedRun('SUCCEEDED'),
        conversation: { ...detail.conversation, activeLeafEntryId: 'assistant-1', lastConfirmedSeq: 2 },
      })
    })

    expect(await screen.findByText('保留展开状态')).toBeVisible()
    expect(view.container.querySelector('.streaming')).toBeNull()
  })

  it('keeps evidence collapsed and focuses the cited source after activation', async () => {
    const view = render(<App />)
    const input = await screen.findByRole('textbox', { name: '消息输入' })
    fireEvent.change(input, { target: { value: '查询本地资料' } })
    fireEvent.click(screen.getByRole('button', { name: '发送' }))
    await waitFor(() => expect(listener).toBeDefined())

    act(() => {
      listener!.onRunStarted(runStartedEvent('查询本地资料'))
      listener!.onAssistantCompleted({
        conversationId: summary.id,
        assistantEntry: {
          formatVersion: 1,
          conversationId: summary.id,
          id: 'assistant-1',
          seq: 2,
          parentId: 'user-1',
          type: 'ASSISTANT_MESSAGE',
          createdAt: '2026-08-18T00:00:01Z',
          payload: {
            text: '结论 [L1]',
            runId: 'run-1',
            citations: [
              {
                kind: 'local',
                referenceId: 'L1',
                evidenceId: 'evidence-1',
                revisionId: 'revision-1',
                documentName: '项目手册',
                location: '第一章',
                citationNote: '与结论直接相关',
              },
            ],
            retrievedSources: [
              {
                kind: 'local',
                referenceId: 'L1',
                evidenceId: 'evidence-1',
                revisionId: 'revision-1',
                documentName: '项目手册',
                location: '第一章',
                retrievedAt: '2026-08-18T00:00:00Z',
                excerptKind: 'LOCAL_EVIDENCE',
                sourceExcerpt: '本地资料摘录',
              },
              {
                kind: 'web',
                referenceId: 'W1',
                provider: 'bocha',
                title: '外部参考',
                url: 'https://example.com/reference',
                site: 'example.com',
                dateLabel: null,
                retrievedAt: '2026-08-18T00:00:00Z',
                excerptKind: 'WEB_SEARCH_SUMMARY',
                sourceExcerpt: '外部搜索摘要',
              },
            ],
          },
        },
      })
      listener!.onRunCompleted({
        conversationId: summary.id,
        run: completedRun('SUCCEEDED'),
        conversation: { ...detail.conversation, activeLeafEntryId: 'assistant-1', lastConfirmedSeq: 2 },
      })
    })

    const disclosure = await screen.findByRole('button', { name: /来源核验/ })
    expect(disclosure).toHaveAttribute('aria-expanded', 'false')
    expect(disclosure).toHaveTextContent('1 条回答已引用 · 1 条本轮召回未引用')
    expect(screen.queryByText('项目手册')).toBeNull()

    fireEvent.click(screen.getByRole('link', { name: '定位来源 [L1]' }))

    await waitFor(() => expect(disclosure).toHaveAttribute('aria-expanded', 'true'))
    expect(screen.getByText('回答已引用')).toBeVisible()
    expect(screen.getByText('本轮召回未引用')).toBeVisible()
    expect(screen.getByText('Agent 相关性摘要')).toBeVisible()
    expect(screen.getByText('本地证据摘录')).toBeVisible()
    expect(view.container.querySelector('#source-card-assistant-1-L1')).toHaveFocus()
  })
})

function runStartedEvent(text: string) {
  return {
    conversationId: summary.id,
    run: {
      id: 'run-1',
      conversationId: summary.id,
      triggerEntryId: 'user-1',
      status: 'RUNNING' as const,
      errorCode: null,
      startedAt: summary.createdAt,
      endedAt: null,
    },
    userEntry: {
      formatVersion: 1,
      conversationId: summary.id,
      id: 'user-1',
      seq: 1,
      parentId: null,
      type: 'USER_MESSAGE' as const,
      createdAt: summary.createdAt,
      payload: { text, runId: 'run-1' },
    },
    isRetry: false,
  }
}

function completedRun(status: 'SUCCEEDED' | 'FAILED') {
  return {
    id: 'run-1',
    conversationId: summary.id,
    triggerEntryId: 'user-1',
    status,
    errorCode: status === 'FAILED' ? 'CHAT_MODEL_FAILED' : null,
    startedAt: summary.createdAt,
    endedAt: '2026-08-18T00:00:02Z',
  }
}
