import { useCallback, useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import { fetchCurrentWorkspace, type Workspace } from './workspaceApi.ts'
import {
  createConversation,
  fetchConversation,
  fetchConversations,
  streamRetry,
  streamSend,
  type ConversationDetail,
  type ConversationSummary,
  type Entry,
  type Run,
  type RunStreamListener,
} from './conversationApi.ts'

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; workspace: Workspace }
  | { status: 'error'; message: string }

// 单个 Conversation 的活动 Run 前端状态，按 Conversation ID 隔离。
// 只承载传输中的临时信息（durable User Entry、delta 累积文本），
// 不替代后端权威的 pendingRun；终态事件后立即删除本状态。
type ActiveRunState = {
  /** run_started 前为 null，用于把 delta 与 Run 身份绑定 */
  runId: string | null
  /** run_started 携带的已持久化 User Entry，用于建立 Assistant 占位位置 */
  userEntry: Entry | null
  /** assistant_delta 按顺序累积的临时文本，assistant_completed 后由持久化 Entry 取代 */
  assistantText: string
}

// 可重试的失败 Run（FAILED / INTERRUPTED）；RUNNING 是单进程串行队列之外的残留，不提供操作
function isRetryable(run: Run | null): boolean {
  return run !== null && (run.status === 'FAILED' || run.status === 'INTERRUPTED')
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) {
    return error.message
  }
  return '无法连接后端'
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
  // 按 Conversation ID 保存的详情缓存：后台 Run 事件只更新所属 Conversation，切换时直接展示
  const [caches, setCaches] = useState<Record<string, ConversationDetail>>({})
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)
  // 按 Conversation ID 保存的活动 Run（发送/重试中）、草稿与错误提示
  const [runStates, setRunStates] = useState<Record<string, ActiveRunState>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [sendErrors, setSendErrors] = useState<Record<string, string | null>>({})
  const [sidebarOpen, setSidebarOpen] = useState(false)
  const messagesRef = useRef<HTMLDivElement>(null)
  // 防止快速切换 Conversation 时过期打开请求覆盖新选择的加载/错误状态
  const openedSeqRef = useRef(0)
  // 已占用 Run 槽位的 Conversation ID：同步防止重复点击产生并发 send/retry
  const runSlotsRef = useRef<Set<string>>(new Set())

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

  // 打开选中的 Conversation（含刷新恢复）：已有缓存时静默刷新对齐权威状态
  const openConversation = useCallback(
    async (id: string) => {
      setSelectedId(id)
      rememberSelection(id)
      setDetailError(null)
      const seq = ++openedSeqRef.current
      if (caches[id] === undefined) {
        setDetailLoading(true)
      }
      try {
        const detail = await fetchConversation(id)
        setCaches((current) => ({ ...current, [id]: detail }))
      } catch (error: unknown) {
        if (seq === openedSeqRef.current) {
          setDetailError(errorMessage(error))
        }
      } finally {
        if (seq === openedSeqRef.current) {
          setDetailLoading(false)
        }
      }
    },
    [caches, rememberSelection],
  )

  useEffect(() => {
    if (selectedId !== null) {
      void openConversation(selectedId)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  // 后台重新读取 Conversation 权威状态（失败/流异常后），不改当前选择
  const refreshDetail = useCallback(async (id: string) => {
    try {
      const detail = await fetchConversation(id)
      setCaches((current) => ({ ...current, [id]: detail }))
    } catch (error: unknown) {
      setSendErrors((current) => ({ ...current, [id]: `状态刷新失败：${errorMessage(error)}` }))
    }
  }, [])

  // 打开后或内容变化时滚到底部；依赖提取为变量以保持依赖数组可静态检查
  const scrollDetail = selectedId !== null ? caches[selectedId] : undefined
  const scrollAssistantText = selectedId !== null ? runStates[selectedId]?.assistantText : undefined
  useEffect(() => {
    const el = messagesRef.current
    if (el) {
      el.scrollTop = el.scrollHeight
    }
  }, [selectedId, scrollDetail, scrollAssistantText])

  // 更新侧栏列表项并移到最前
  const updateConversationInList = useCallback(
    (conversation: Pick<ConversationSummary, 'id' | 'title' | 'updatedAt'>, run: Run | null) => {
      setConversations((current) => {
        if (current === null) return current
        const updated: ConversationSummary[] = current.map((item) =>
          item.id === conversation.id
            ? {
                ...item,
                title: conversation.title,
                updatedAt: conversation.updatedAt,
                latestRun: run ?? item.latestRun,
              }
            : item,
        )
        return updated.sort(
          (a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime(),
        )
      })
    },
    [],
  )

  /**
   * 为一次 send / retry 构建事件监听器：所有回调只更新 runStates / caches / drafts /
   * sendErrors 中键为 conversationId 的条目，后台流不会写进其他 Conversation 或当前页面。
   * sentText 用于 run_started 清空草稿前校验草稿未变，避免覆盖用户新输入。
   */
  const makeListener = useCallback(
    (conversationId: string, sentText: string): RunStreamListener => {
      const endRun = () => {
        setRunStates((current) => {
          const next = { ...current }
          delete next[conversationId]
          return next
        })
      }
      return {
        onRunStarted(event) {
          setRunStates((current) => {
            const state = current[conversationId]
            if (state === undefined) return current
            return {
              ...current,
              [conversationId]: { ...state, runId: event.run.id, userEntry: event.userEntry },
            }
          })
          // 收到 run_started 才清空已发送草稿；期间用户新输入的新文本不被覆盖
          setDrafts((current) =>
            current[conversationId] === sentText ? { ...current, [conversationId]: '' } : current,
          )
          // 确认 durable User Entry 进入缓存路径（重试时已有，不重复追加）
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined || detail.activePath.some((e) => e.id === event.userEntry.id)) {
              return current
            }
            return {
              ...current,
              [conversationId]: {
                ...detail,
                activePath: [...detail.activePath, event.userEntry],
                pendingRun: null,
              },
            }
          })
        },
        onCompactionCompleted(event) {
          // Compaction 总是当前新叶子：追加到缓存路径末尾；不展示 payload.summary
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined || detail.activePath.some((e) => e.id === event.compactionEntry.id)) {
              return current
            }
            return {
              ...current,
              [conversationId]: {
                ...detail,
                conversation: event.conversation,
                activePath: [...detail.activePath, event.compactionEntry],
              },
            }
          })
        },
        onAssistantDelta(event) {
          setRunStates((current) => {
            const state = current[conversationId]
            if (state === undefined || state.runId === null || event.runId !== state.runId) {
              return current
            }
            return {
              ...current,
              [conversationId]: { ...state, assistantText: state.assistantText + event.delta },
            }
          })
        },
        onAssistantCompleted(event) {
          // 用持久化 Entry 替换临时文本：只追加一次，避免增量拼接误差成为最终历史
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined || detail.activePath.some((e) => e.id === event.assistantEntry.id)) {
              return current
            }
            return {
              ...current,
              [conversationId]: {
                ...detail,
                activePath: [...detail.activePath, event.assistantEntry],
                pendingRun: null,
              },
            }
          })
          endRun()
        },
        onTitleUpdated(event) {
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined) return current
            return {
              ...current,
              [conversationId]: { ...detail, conversation: { ...detail.conversation, title: event.title } },
            }
          })
          updateConversationInList({ id: conversationId, title: event.title, updatedAt: event.titleEntry.createdAt }, null)
        },
        onRunCompleted(event) {
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined) return current
            return { ...current, [conversationId]: { ...detail, conversation: event.conversation, pendingRun: null } }
          })
          updateConversationInList(event.conversation, event.run)
          endRun()
        },
        onRunFailed(event) {
          setSendErrors((current) => ({ ...current, [conversationId]: event.message }))
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined) return current
            return { ...current, [conversationId]: { ...detail, conversation: event.conversation } }
          })
          updateConversationInList(event.conversation, event.run)
          endRun()
          // 重新读取权威 pendingRun，决定是否展示重试入口
          void refreshDetail(conversationId)
        },
      }
    },
    [refreshDetail, updateConversationInList],
  )

  const handleCreate = useCallback(async () => {
    try {
      const created = await createConversation()
      setConversations((current) => [created, ...(current ?? [])])
      setSelectedId(created.id)
    } catch (error: unknown) {
      setListError(errorMessage(error))
    }
  }, [])

  // 开始一次 send / retry：先占用该 Conversation 的 Run 槽位（防重复点击），再发起 SSE
  const startRun = useCallback(
    async (id: string, start: (listener: RunStreamListener) => Promise<void>) => {
      if (runSlotsRef.current.has(id)) return
      runSlotsRef.current.add(id)
      setRunStates((current) => ({ ...current, [id]: { runId: null, userEntry: null, assistantText: '' } }))
      setSendErrors((current) => ({ ...current, [id]: null }))
      try {
        await start(makeListener(id, drafts[id] ?? ''))
      } catch (error: unknown) {
        // 传输中断或前置 JSON 错误：清除临时 Run 状态，重新读取权威状态，不自动重发
        setRunStates((current) => {
          const next = { ...current }
          delete next[id]
          return next
        })
        setSendErrors((current) => ({ ...current, [id]: errorMessage(error) }))
        await refreshDetail(id)
      } finally {
        runSlotsRef.current.delete(id)
      }
    },
    [drafts, makeListener, refreshDetail],
  )

  const handleSend = useCallback(async () => {
    const id = selectedId
    if (id === null || runStates[id] !== undefined) return
    const text = drafts[id]?.trim() ?? ''
    if (text === '') return
    await startRun(id, (listener) => streamSend(id, text, listener))
  }, [selectedId, runStates, drafts, startRun])

  const handleRetry = useCallback(async () => {
    const id = selectedId
    if (id === null || runStates[id] !== undefined) return
    const pending = caches[id]?.pendingRun ?? null
    if (pending === null || !isRetryable(pending)) return
    await startRun(id, (listener) => streamRetry(id, pending.id, listener))
  }, [selectedId, runStates, caches, startRun])

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

  // 定位最近一次 Compaction：滚动到历史中的压缩标记（不泄露 Summary）
  const scrollToCompaction = useCallback(() => {
    const detail = selectedId !== null ? caches[selectedId] : undefined
    const compactionId = detail?.conversation.latestCompactionEntryId
    if (compactionId === null || compactionId === undefined) return
    document
      .getElementById(`compaction-${compactionId}`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [selectedId, caches])

  const selectedDetail = selectedId !== null ? caches[selectedId] : undefined
  const selectedRun = selectedId !== null ? runStates[selectedId] : undefined
  const pendingRun = selectedDetail?.pendingRun ?? null
  const running = selectedRun !== undefined
  const textareaDisabled = selectedId === null
  const sendDisabled =
    selectedId === null || selectedDetail === undefined || running || (drafts[selectedId]?.trim() ?? '') === ''

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
                data-running={runStates[item.id] !== undefined}
                onClick={() => {
                  setSidebarOpen(false)
                  setSelectedId(item.id)
                }}
              >
                <span className="conv-title">{item.title}</span>
                <span className="conv-time">
                  {runStates[item.id] !== undefined ? '回答中…' : formatTime(item.updatedAt)}
                </span>
              </button>
            ))}
          </nav>
        </aside>

        <main className="chat">
          {selectedId === null && conversations !== null && (
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

          {selectedDetail === undefined && (detailLoading || detailError === null) && (
            <p className="hint">正在打开对话…</p>
          )}

          {selectedDetail === undefined && detailError !== null && (
            <section className="panel">
              <p className="kicker">对话</p>
              <h1>打不开</h1>
              <p className="lede">{detailError}</p>
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

          {selectedDetail !== undefined && selectedId !== null && (
            <section className="conversation">
              <header className="chat-head">
                <h2>{selectedDetail.conversation.title}</h2>
                {running && <span className="run-badge">回答中…</span>}
                {!running && pendingRun !== null && isRetryable(pendingRun) && (
                  <span className="retry-badge">回答失败</span>
                )}
                {selectedDetail.conversation.latestCompactionEntryId !== null && (
                  <button
                    type="button"
                    className="compaction-jump"
                    aria-label="定位最近一次上下文压缩位置"
                    onClick={scrollToCompaction}
                  >
                    已压缩 · 定位
                  </button>
                )}
              </header>

              <div className="messages" ref={messagesRef}>
                {selectedDetail.activePath.length === 0 && (
                  <p className="hint">发送第一条消息开始对话。</p>
                )}
                {selectedDetail.activePath.map((entry) => (
                  <MessageEntry key={entry.id} entry={entry} />
                ))}
                {running && selectedRun.userEntry !== null && (
                  <StreamingAssistant text={selectedRun.assistantText} />
                )}
              </div>

              {!running && pendingRun !== null && isRetryable(pendingRun) && (
                <div className="retry-bar">
                  <p className="retry-text">
                    上一条消息没有完成回答
                    {pendingRun.status === 'INTERRUPTED' ? '（被中断）' : ''}
                    ，可以重试。
                  </p>
                  <button type="button" onClick={() => void handleRetry()}>
                    重试
                  </button>
                </div>
              )}

              {sendErrors[selectedId] !== null && sendErrors[selectedId] !== undefined && (
                <p className="send-error">{sendErrors[selectedId]}</p>
              )}

              <div className="composer">
                <textarea
                  value={drafts[selectedId] ?? ''}
                  onChange={(event) =>
                    setDrafts((current) => ({ ...current, [selectedId]: event.target.value }))
                  }
                  onKeyDown={handleKeyDown}
                  disabled={textareaDisabled}
                  placeholder={
                    running
                      ? '正在等待回答，可先输入下一条草稿…'
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
                  disabled={sendDisabled}
                  onClick={() => void handleSend()}
                >
                  {running ? '回答中…' : '发送'}
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
  if (entry.type === 'COMPACTION') {
    // 压缩标记：只指示位置，不泄露 Summary 内容；id 供「定位压缩点」滚动
    return (
      <div className="msg-row note compaction-marker" id={`compaction-${entry.id}`}>
        <p>（此处有一段已压缩的早期对话，从这里继续）</p>
      </div>
    )
  }
  // TITLE 是 Conversation 级元数据，不进入 Active Path；兜底时不渲染
  return null
}

// 运行中的临时 Assistant：只用于展示 delta，最终以持久化 Entry 替换
function StreamingAssistant({ text }: { text: string }) {
  return (
    <div className="msg-row assistant">
      <div className="bubble assistant-bubble streaming">
        {text === '' ? (
          <p className="thinking">正在思考…</p>
        ) : (
          <div className="markdown">
            <Markdown>{text}</Markdown>
          </div>
        )}
        <p className="model-line">正在生成…</p>
      </div>
    </div>
  )
}

export default App
