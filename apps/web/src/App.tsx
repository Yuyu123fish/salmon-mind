import { useCallback, useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import { fetchCurrentWorkspace, type Workspace } from './workspaceApi.ts'
import {
  ApiError,
  createConversation,
  fetchConversation,
  fetchConversations,
  retryRun,
  sendMessage,
  type ConversationDetail,
  type ConversationRunResult,
  type ConversationSummary,
  type Entry,
  type Run,
} from './conversationApi.ts'

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; workspace: Workspace }
  | { status: 'error'; message: string }

type DetailState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; detail: ConversationDetail }

// 可重试的失败 Run（FAILED / INTERRUPTED）；RUNNING 是单进程串行队列之外的残留，不提供操作
function isRetryable(run: Run | null): boolean {
  return run !== null && (run.status === 'FAILED' || run.status === 'INTERRUPTED')
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    return error.message
  }
  return error instanceof Error ? error.message : '无法连接后端'
}

function formatTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatTimeShort(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleTimeString('zh-CN', { hour12: false, hour: '2-digit', minute: '2-digit' })
}

function App() {
  const [workspaceState, setWorkspaceState] = useState<LoadState>({ status: 'loading' })
  const [conversations, setConversations] = useState<ConversationSummary[] | null>(null)
  const [listError, setListError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<DetailState>({ status: 'loading' })
  // 正在发送/重试的 Conversation ID；发送期间禁用该 Conversation 的重复发送，其他仍可查看
  const [busyId, setBusyId] = useState<string | null>(null)
  const [sendError, setSendError] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const messagesRef = useRef<HTMLDivElement>(null)

  // 加载工作空间与对话列表
  useEffect(() => {
    fetchCurrentWorkspace()
      .then((workspace) => setWorkspaceState({ status: 'ready', workspace }))
      .catch((error: unknown) =>
        setWorkspaceState({ status: 'error', message: errorMessage(error) }),
      )
  }, [])

  useEffect(() => {
    loadConversations()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const rememberSelection = useCallback((id: string) => {
    try {
      localStorage.setItem('salmon-mind:selected-conversation', id)
    } catch {
      // 隐私模式等场景下忽略存储失败，仅失去刷新恢复
    }
  }, [])

  const loadConversations = useCallback(async () => {
    setListError(null)
    try {
      const list = await fetchConversations()
      setConversations(list)
      // 刷新恢复：优先恢复上次选中的 Conversation，否则打开最近更新的
      let remembered: string | null = null
      try {
        remembered = localStorage.getItem('salmon-mind:selected-conversation')
      } catch {
        // 存储不可用时退回默认选择
      }
      const target = list.some((item) => item.id === remembered)
        ? remembered
        : (list[0]?.id ?? null)
      if (target !== null) {
        rememberSelection(target)
      }
      setSelectedId(target)
    } catch (error: unknown) {
      setListError(errorMessage(error))
    }
  }, [rememberSelection])

  // 打开选中的 Conversation（含刷新恢复）
  const openConversation = useCallback(
    async (id: string) => {
      setSelectedId(id)
      rememberSelection(id)
      setDetail({ status: 'loading' })
      setSendError(null)
      try {
        const detail = await fetchConversation(id)
        setDetail({ status: 'ready', detail })
      } catch (error: unknown) {
        setDetail({ status: 'error', message: errorMessage(error) })
      }
    },
    [rememberSelection],
  )

  useEffect(() => {
    if (selectedId !== null) {
      void openConversation(selectedId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  // 打开后滚到底部
  useEffect(() => {
    const el = messagesRef.current
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  }, [detail])

  const reloadDetail = useCallback(async (id: string) => {
    try {
      const fresh = await fetchConversation(id)
      setDetail({ status: 'ready', detail: fresh })
    } catch (error: unknown) {
      setDetail({ status: 'error', message: errorMessage(error) })
    }
  }, [])

  const updateListFromResult = useCallback((result: ConversationRunResult) => {
    const { conversation } = result
    setConversations((current) => {
      if (current === null) return current
      const updated: ConversationSummary[] = current.map((item) =>
        item.id === conversation.id
          ? { ...item, title: conversation.title, updatedAt: conversation.updatedAt, latestRun: result.run }
          : item,
      )
      // 发送后把该对话移到最前
      return updated.sort(
        (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
      )
    })
  }, [])

  const handleCreate = useCallback(async () => {
    try {
      const created = await createConversation()
      setConversations((current) => [created, ...(current ?? [])])
      setSelectedId(created.id)
    } catch (error: unknown) {
      setListError(errorMessage(error))
    }
  }, [])

  const handleSend = useCallback(async () => {
    const text = draft.trim()
    if (selectedId === null || busyId !== null || text === '') return
    setBusyId(selectedId)
    setSendError(null)
    try {
      const result = await sendMessage(selectedId, text)
      setDetail((current) =>
        current.status === 'ready'
          ? {
              status: 'ready',
              detail: {
                conversation: result.conversation,
                activePath: [
                  ...current.detail.activePath,
                  result.userEntry,
                  result.assistantEntry,
                ],
                pendingRun: null,
              },
            }
          : current,
      )
      setDraft('')
      updateListFromResult(result)
    } catch (error: unknown) {
      setSendError(errorMessage(error))
      // 失败时 Run 已标记 FAILED：重新读取详情获得可重试的 pendingRun
      await reloadDetail(selectedId)
    } finally {
      setBusyId(null)
    }
  }, [draft, selectedId, busyId, reloadDetail, updateListFromResult])

  const handleRetry = useCallback(async () => {
    if (selectedId === null || busyId !== null) return
    const pending = detail.status === 'ready' ? detail.detail.pendingRun : null
    if (pending === null || !isRetryable(pending)) return
    setBusyId(selectedId)
    setSendError(null)
    try {
      const result = await retryRun(selectedId, pending.id)
      setDetail((current) =>
        current.status === 'ready'
          ? {
              status: 'ready',
              detail: {
                conversation: result.conversation,
                activePath: [...current.detail.activePath, result.assistantEntry],
                pendingRun: null,
              },
            }
          : current,
      )
      updateListFromResult(result)
    } catch (error: unknown) {
      setSendError(errorMessage(error))
      await reloadDetail(selectedId)
    } finally {
      setBusyId(null)
    }
  }, [selectedId, busyId, detail, reloadDetail, updateListFromResult])

  // Enter 发送、Shift+Enter 换行；输入法组合中不触发发送
  const handleKeyDown = useCallback(
    (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
        event.preventDefault()
        void handleSend()
      }
    },
    [handleSend],
  )

  const readyDetail = detail.status === 'ready' ? detail.detail : null
  const pendingRun = readyDetail?.pendingRun ?? null
  const inputDisabled = busyId !== null || selectedId === null

  return (
    <div className="shell" data-status={workspaceState.status}>
      <header className="topbar">
        <div className="brand">
          <button
            type="button"
            className="sidebar-toggle"
            aria-label="切换对话列表"
            aria-expanded={sidebarOpen}
            onClick={() => setSidebarOpen((open) => !open)}
          >
            <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M4 6.5h16M4 12h16M4 17.5h10" />
            </svg>
          </button>
          <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 15.5 12 8.5l7 7" />
            <path d="M5 19.5 12 12.5l7 7" />
          </svg>
          <span className="product">SalmonMind</span>
        </div>
        <p className="status">
          {workspaceState.status === 'ready' && '已连接'}
          {workspaceState.status === 'loading' && '正在连接'}
          {workspaceState.status === 'error' && '未连接'}
        </p>
      </header>

      <div className="workspace">
        <aside className="sidebar" data-open={sidebarOpen}>
          <button type="button" className="new-chat" onClick={() => void handleCreate()}>
            ＋ 新对话
          </button>
          {listError !== null && <p className="side-error">{listError}</p>}
          <nav className="conv-list" aria-label="对话列表">
            {conversations === null && <p className="side-hint">正在读取对话…</p>}
            {conversations !== null && conversations.length === 0 && (
              <p className="side-hint">还没有对话，创建一个开始吧。</p>
            )}
            {conversations?.map((item) => (
              <button
                key={item.id}
                type="button"
                className="conv-item"
                data-selected={item.id === selectedId}
                onClick={() => {
                  setSidebarOpen(false)
                  setSelectedId(item.id)
                }}
              >
                <span className="conv-title">{item.title}</span>
                <span className="conv-time">{formatTime(item.updatedAt)}</span>
              </button>
            ))}
          </nav>
        </aside>

        <main className="chat">
          {detail.status === 'loading' && selectedId === null && conversations !== null && (
            <section className="panel">
              <p className="kicker">对话</p>
              <h1>{conversations.length === 0 ? '还没有对话' : '选择一个对话'}</h1>
              <p className="lede">
                {conversations.length === 0
                  ? '在左侧创建一个新对话，开始你的第一轮问答。'
                  : '在左侧选择一个对话查看历史，或创建一个新的。'}
              </p>
            </section>
          )}

          {detail.status === 'loading' && selectedId !== null && <p className="hint">正在打开对话…</p>}

          {detail.status === 'error' && (
            <section className="panel">
              <p className="kicker">对话</p>
              <h1>打不开</h1>
              <p className="lede">{detail.message}</p>
              <button
                type="button"
                onClick={() => {
                  if (selectedId !== null) void openConversation(selectedId)
                }}
              >
                重新加载
              </button>
            </section>
          )}

          {readyDetail !== null && (
            <section className="conversation">
              <header className="chat-head">
                <h2>{readyDetail.conversation.title}</h2>
              {pendingRun !== null && isRetryable(pendingRun) && (
                  <span className="retry-badge">回答失败</span>
                )}
              </header>

              <div className="messages" ref={messagesRef}>
                {readyDetail.activePath.length === 0 && (
                  <p className="hint">发送第一条消息开始对话。</p>
                )}
                {readyDetail.activePath.map((entry) => (
                  <MessageEntry key={entry.id} entry={entry} />
                ))}
              </div>

              {pendingRun !== null && isRetryable(pendingRun) && (
                <div className="retry-bar">
                  <p className="retry-text">
                    上一条消息没有完成回答
                    {pendingRun.status === 'INTERRUPTED' ? '（被中断）' : ''}
                    ，可以重试。
                  </p>
                  <button type="button" disabled={busyId !== null} onClick={() => void handleRetry()}>
                    {busyId === selectedId ? '正在重试…' : '重试'}
                  </button>
                </div>
              )}

              {sendError !== null && <p className="send-error">{sendError}</p>}

              <div className="composer">
                <textarea
                  value={draft}
                  onChange={(event) => setDraft(event.target.value)}
                  onKeyDown={handleKeyDown}
                  disabled={inputDisabled}
                  placeholder={
                    busyId !== null
                      ? '正在等待回答…'
                      : selectedId === null
                        ? '先创建一个对话'
                        : '输入消息，Enter 发送，Shift+Enter 换行'
                  }
                  rows={3}
                  aria-label="消息输入"
                />
                <button
                  type="button"
                  className="send-button"
                  disabled={inputDisabled || draft.trim() === ''}
                  onClick={() => void handleSend()}
                >
                  {busyId === selectedId ? '发送中…' : '发送'}
                </button>
              </div>
            </section>
          )}
        </main>
      </div>
    </div>
  )
}

function MessageEntry({ entry }: { entry: Entry }) {
  if (entry.type === 'USER_MESSAGE') {
    return (
      <div className="msg-row user">
        <div className="bubble user-bubble">
          <p className="bubble-text">{entry.payload.text}</p>
          <time>{formatTimeShort(entry.createdAt)}</time>
        </div>
      </div>
    )
  }
  if (entry.type === 'ASSISTANT_MESSAGE') {
    return (
      <div className="msg-row assistant">
        <div className="bubble assistant-bubble">
          <div className="markdown">
            <Markdown>{entry.payload.text ?? ''}</Markdown>
          </div>
          <p className="model-line">
            {entry.payload.model ?? '模型'} · {formatTimeShort(entry.createdAt)}
          </p>
        </div>
      </div>
    )
  }
  // COMPACTION：本 Feature 不生成，仅作为历史页面兜底展示
  return (
    <div className="msg-row note">
      <p>（此处有一段已压缩的早期对话）</p>
    </div>
  )
}

export default App
