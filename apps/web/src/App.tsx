import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import KnowledgeView from './KnowledgeView.tsx'
import { MarkdownRenderer } from './MarkdownRenderer.tsx'
import { RunTracePanel } from './RunTracePanel.tsx'
import { mergeConversation, mergeConversationDetail } from './conversationState.ts'
import { followModeAfterScroll } from './followMode.ts'
import {
  createActiveRunState,
  isActiveRun,
  reduceActiveRun,
  type ActiveRunAction,
  type ActiveRunState,
} from './runState.ts'
import { fetchCurrentWorkspace, type Workspace } from './workspaceApi.ts'
import {
  createConversation,
  fetchConversation,
  fetchConversations,
  streamRetry,
  streamContinue,
  streamSend,
  type CitationPayload,
  type ConversationDetail,
  type Conversation,
  type ConversationSummary,
  type Entry,
  type RetrievedSourcePayload,
  type Run,
  type RunStreamListener,
  type RunTraceItem,
} from './conversationApi.ts'

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; workspace: Workspace }
  | { status: 'error'; message: string }

// 本地新对话草稿的伪 ID：没有任何 Server UUID，只承载页面内文本/错误。
// 首次非空发送成功 create 后才转为真实 Conversation ID，刷新可丢弃未发送草稿。
const DRAFT_KEY = '__local_draft__'

// 新对话草稿的默认标题：仅前端展示，不发送给后端
const DRAFT_TITLE = '新对话'

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
  const [activeView, setActiveView] = useState<'chat' | 'knowledge'>('chat')
  const messagesRef = useRef<HTMLDivElement>(null)
  const selectedIdRef = useRef<string | null>(null)
  const followingRef = useRef(true)
  const programmaticScrollRef = useRef(false)
  const [following, setFollowing] = useState(true)
  // 防止快速切换 Conversation 时过期打开请求覆盖新选择的加载/错误状态
  const openedSeqRef = useRef(0)
  // 已占用 Run 槽位的 Conversation ID：同步防止重复点击产生并发 send/retry
  const runSlotsRef = useRef<Set<string>>(new Set())
  // 本地草稿首次发送槽位：create 进行中重复 Enter/点击不得创建第二个 Conversation
  const draftSendingRef = useRef(false)
  // 首次发送进行中（create/send 前段）的 UI 状态：发送按钮禁用，避免视觉上的可重复点击
  const [firstSending, setFirstSending] = useState(false)
  // SSE 与刷新响应可能乱序到达；记录每个 Conversation 已接收的最新快照供侧栏合并。
  const conversationSnapshotsRef = useRef<Record<string, Conversation>>({})

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
  const openConversation = useCallback(// 当[caches, rememberSelection]的内容没变时，缓存函数本身
    async (id: string) => {
      setSelectedId(id)
      setDetailError(null)// 清除上一次的错误提示
      if (id === DRAFT_KEY) {
        // 本地草稿没有 Server ID：不请求详情、不持久化选择（刷新可丢弃未发送草稿）
        return
      }
      rememberSelection(id)
      const seq = ++openedSeqRef.current
      // 如果缓存中没有该对话，则设置为加载中
      if (caches[id] === undefined) {
        setDetailLoading(true)
      }
      try {
        const detail = await fetchConversation(id)
        setCaches((current) => ({
          ...current,
          [id]: (() => {
            const merged = mergeConversationDetail(current[id], detail)
            conversationSnapshotsRef.current[id] = merged.conversation
            return merged
          })(),
        }))
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
      setCaches((current) => ({
        ...current,
        [id]: (() => {
          const merged = mergeConversationDetail(current[id], detail)
          conversationSnapshotsRef.current[id] = merged.conversation
          return merged
        })(),
      }))
    } catch (error: unknown) {
      setSendErrors((current) => ({ ...current, [id]: `状态刷新失败：${errorMessage(error)}` }))
    }
  }, [])

  const setFollowMode = useCallback((next: boolean) => {
    followingRef.current = next
    setFollowing(next)
  }, [])

  const clearNewContent = useCallback((conversationId: string) => {
    setRunStates((current) => {
      const state = current[conversationId]
      if (state === undefined) return current
      return { ...current, [conversationId]: reduceActiveRun(state, { type: 'restore_follow' }) }
    })
  }, [])

  const scrollToBottom = useCallback((behavior: ScrollBehavior = 'auto') => {
    const el = messagesRef.current
    if (!el) return
    programmaticScrollRef.current = true
    el.scrollTo({ top: el.scrollHeight, behavior })
    requestAnimationFrame(() => {
      programmaticScrollRef.current = false
    })
  }, [])

  const restoreFollow = useCallback(
    (behavior: ScrollBehavior = 'auto') => {
      const id = selectedIdRef.current
      setFollowMode(true)
      if (id !== null) clearNewContent(id)
      scrollToBottom(behavior)
    },
    [clearNewContent, scrollToBottom, setFollowMode],
  )

  useEffect(() => {
    selectedIdRef.current = selectedId
    setFollowMode(true)
    if (selectedId !== null) clearNewContent(selectedId)
    requestAnimationFrame(() => scrollToBottom('auto'))
  }, [selectedId, clearNewContent, scrollToBottom, setFollowMode])

  // 只有当前视图仍处于 Follow Mode 时，流式文本、Trace 或 durable Entry 的高度变化才跟随。
  const scrollPathLength = selectedId !== null ? caches[selectedId]?.activePath.length : undefined
  const scrollAssistantText = selectedId !== null ? runStates[selectedId]?.assistantText : undefined
  const scrollTrace = selectedId !== null ? runStates[selectedId]?.trace : undefined
  const scrollTraceExpanded = selectedId !== null ? runStates[selectedId]?.traceExpanded : undefined
  useEffect(() => {
    if (followingRef.current) requestAnimationFrame(() => scrollToBottom('auto'))
  }, [scrollPathLength, scrollAssistantText, scrollTrace, scrollTraceExpanded, scrollToBottom])

  const handleMessagesScroll = useCallback(() => {
    const el = messagesRef.current
    if (el === null) return
    const next = followModeAfterScroll(
      followingRef.current,
      el,
      programmaticScrollRef.current,
    )
    if (next && !followingRef.current) {
      restoreFollow('auto')
    } else if (!next && followingRef.current) {
      setFollowMode(false)
    }
  }, [restoreFollow, setFollowMode])

  const handleTraceLayoutChange = useCallback(() => {
    if (followingRef.current) requestAnimationFrame(() => scrollToBottom('auto'))
  }, [scrollToBottom])

  const toggleSelectedTrace = useCallback(() => {
    const id = selectedIdRef.current
    if (id === null) return
    setRunStates((current) => {
      const state = current[id]
      if (state === undefined) return current
      return { ...current, [id]: reduceActiveRun(state, { type: 'toggle_trace' }) }
    })
  }, [])

  // 更新侧栏列表项并移到最前
  const updateConversationInList = useCallback(
    (incoming: Conversation, run: Run | null) => {
      const current = conversationSnapshotsRef.current[incoming.id]
      const conversation = current === undefined ? incoming : mergeConversation(current, incoming)
      conversationSnapshotsRef.current[incoming.id] = conversation
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
      const reduceRun = (action: ActiveRunAction) => {
        setRunStates((current) => {
          const state = current[conversationId]
          if (state === undefined) return current
          return { ...current, [conversationId]: reduceActiveRun(state, action) }
        })
      }
      // 后台 Conversation 不累加当前视图提示；只有当前且已退出 Follow Mode 的流记未读。
      const followsCurrentView = () =>
        selectedIdRef.current !== conversationId || followingRef.current

      return {
        onRunStarted(event) {
          reduceRun({ type: 'run_started', event })
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
                conversation: mergeConversation(detail.conversation, event.conversation),
                activePath: [...detail.activePath, event.compactionEntry],
              },
            }
          })
        },
        onReasoningDelta(event) {
          reduceRun({ type: 'reasoning_delta', event, following: followsCurrentView() })
        },
        onToolStarted(event) {
          reduceRun({ type: 'tool_started', event, following: followsCurrentView() })
        },
        onToolCompleted(event) {
          reduceRun({ type: 'tool_completed', event, following: followsCurrentView() })
        },
        onToolFailed(event) {
          reduceRun({ type: 'tool_failed', event, following: followsCurrentView() })
        },
        onAssistantDelta(event) {
          reduceRun({ type: 'assistant_delta', event, following: followsCurrentView() })
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
        },
        onTitleUpdated(event) {
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined) return current
            return {
              ...current,
              [conversationId]: {
                ...detail,
                conversation: mergeConversation(detail.conversation, event.conversation),
              },
            }
          })
          updateConversationInList(event.conversation, null)
        },
        onRunCompleted(event) {
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined) return current
            return {
              ...current,
              [conversationId]: {
                ...detail,
                conversation: mergeConversation(detail.conversation, event.conversation),
                pendingRun: null,
              },
            }
          })
          updateConversationInList(event.conversation, event.run)
          reduceRun({ type: 'run_completed' })
        },
        onRunFailed(event) {
          setSendErrors((current) => ({ ...current, [conversationId]: event.message }))
          setCaches((current) => {
            const detail = current[conversationId]
            if (detail === undefined) return current
            return {
              ...current,
              [conversationId]: {
                ...detail,
                conversation: mergeConversation(detail.conversation, event.conversation),
              },
            }
          })
          updateConversationInList(event.conversation, event.run)
          reduceRun({ type: 'run_failed' })
          // 重新读取权威 pendingRun，决定是否展示重试入口
          void refreshDetail(conversationId)
        },
      }
    },
    [refreshDetail, updateConversationInList],
  )

  // 新对话只进入浏览器本地草稿态：不调用创建 API、不加入侧栏列表、不产生 Server 记录；
  // 首次非空发送时才按 create → send 顺序建立服务端 Conversation
  const handleNewChat = useCallback(() => {
    setDrafts((current) => {
      const next = { ...current }
      delete next[DRAFT_KEY]
      return next
    })
    setSendErrors((current) => {
      const next = { ...current }
      delete next[DRAFT_KEY]
      return next
    })
    setDetailError(null)
    setSelectedId(DRAFT_KEY)
    setSidebarOpen(false)
  }, [])

  // 开始一次 send / retry：先占用该 Conversation 的 Run 槽位（防重复点击），再发起 SSE。
  // sentText 显式传入本次已发送文本，不依赖 React 异步状态把草稿移到新 ID 下。
  const startRun = useCallback(
    async (id: string, sentText: string, start: (listener: RunStreamListener) => Promise<void>) => {
      // 用 Set 占位防止重复点击
      if (runSlotsRef.current.has(id)) return
      runSlotsRef.current.add(id)
      setRunStates((current) => ({
        ...current,
        [id]: createActiveRunState(),
      }))
      setSendErrors((current) => ({ ...current, [id]: null }))
      try {
        await start(makeListener(id, sentText))
      } catch (error: unknown) {
        // 传输中断或前置 JSON 错误：保留已收到的安全 Trace，重新读取权威状态，不自动重发。
        setRunStates((current) => {
          const state = current[id]
          if (state === undefined || state.status === 'completed') return current
          return { ...current, [id]: reduceActiveRun(state, { type: 'run_failed' }) }
        })
        setSendErrors((current) => ({ ...current, [id]: errorMessage(error) }))
        await refreshDetail(id)
      } finally {
        runSlotsRef.current.delete(id)
      }
    },
    [makeListener, refreshDetail],
  )

  /**
   * 本地草稿的首次发送：校验非空并占用本地发送槽位 → POST create 等待真实 ID →
   * 把真实 Conversation 加入列表并建立空详情缓存 → 用同一 sentText 对该 ID 发起
   * POST SSE send → run_started 后由监听器按既有规则清空草稿。create 与 send 严格
   * 串行；重复 Enter/点击不会创建第二个 Conversation。
   */
  const sendFirstMessage = useCallback(
    async (sentText: string) => {
      if (draftSendingRef.current) return
      draftSendingRef.current = true
      setFirstSending(true)
      try {
        const created = await createConversation()
        setConversations((current) => [created, ...(current ?? [])])
        // 把草稿文本迁到真实 ID 下：create 成功而 send 前置失败时保留文本，
        // 重试只调用该 ID 的 send，不重复创建
        setDrafts((current) => {
          const next = { ...current }
          delete next[DRAFT_KEY]
          next[created.id] = sentText
          return next
        })
        setSendErrors((current) => {
          const next = { ...current }
          delete next[DRAFT_KEY]
          return next
        })
        // 先建立真实详情缓存再切换选择：流事件到达时缓存已就绪，切换也不闪 loading
        const detail = await fetchConversation(created.id)
        setCaches((current) => ({ ...current, [created.id]: detail }))
        setSelectedId(created.id)
        await startRun(created.id, sentText, (listener) => streamSend(created.id, sentText, listener))
      } catch (error: unknown) {
        // create 失败：仍停留在本地草稿，保留文本并允许重试，不产生伪 Conversation。
        // create 成功后的 send 前置失败已由 startRun 内部处理并定位到真实 ID，不会冒泡到这里。
        setSendErrors((current) => ({ ...current, [DRAFT_KEY]: errorMessage(error) }))
      } finally {
        draftSendingRef.current = false
        setFirstSending(false)
      }
    },
    [startRun],
  )

  const handleSend = useCallback(async () => {
    const id = selectedId
    if (id === null || isActiveRun(runStates[id])) return
    const text = drafts[id]?.trim() ?? ''
    if (text === '') return
    if (id === DRAFT_KEY) {
      restoreFollow('auto')
      await sendFirstMessage(text)
      return
    }
    restoreFollow('auto')
    await startRun(id, text, (listener) => streamSend(id, text, listener))
  }, [selectedId, runStates, drafts, startRun, sendFirstMessage, restoreFollow])

  const handleRetry = useCallback(async () => {
    const id = selectedId
    if (id === null || isActiveRun(runStates[id])) return
    const pending = caches[id]?.pendingRun ?? null
    if (pending === null || !isRetryable(pending)) return
    restoreFollow('auto')
    await startRun(id, drafts[id] ?? '', (listener) => streamRetry(id, pending.id, listener))
  }, [selectedId, runStates, caches, drafts, startRun, restoreFollow])

  const handleContinue = useCallback(async () => {
    const id = selectedId
    const detail = id === null ? undefined : caches[id]
    const leaf = detail?.activePath.at(-1)
    if (
      id === null ||
      leaf === undefined ||
      leaf.type !== 'ASSISTANT_MESSAGE' ||
      leaf.payload.completionStatus !== 'INCOMPLETE_LENGTH' ||
      isActiveRun(runStates[id])
    ) {
      return
    }
    restoreFollow('auto')
    await startRun(id, '', (listener) => streamContinue(id, leaf.id, listener))
  }, [selectedId, caches, runStates, startRun, restoreFollow])

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
  const running = isActiveRun(selectedRun)
  const durableCurrentAssistant = selectedDetail?.activePath.some(
    (entry) => entry.type === 'ASSISTANT_MESSAGE' && entry.payload.runId === selectedRun?.runId,
  ) ?? false
  const showTransientAssistant =
    selectedRun !== undefined &&
    selectedRun.userEntry !== null &&
    !durableCurrentAssistant &&
    (running ||
      (selectedRun.status === 'failed' &&
        (selectedRun.trace.length > 0 || selectedRun.assistantText !== '')))
  // 本地草稿没有详情缓存：发送条件不要求 detail，但 create 进行中与空白内容不可发送
  const isDraft = selectedId === DRAFT_KEY
  const textareaDisabled = selectedId === null
  const sendDisabled =
    selectedId === null ||
    (!isDraft && selectedDetail === undefined) ||
    running ||
    firstSending ||
    (drafts[selectedId]?.trim() ?? '') === ''

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
        <nav className="view-switch" aria-label="工作区视图">
          <button
            type="button"
            className="view-switch-button"
            aria-pressed={activeView === 'chat'}
            onClick={() => setActiveView('chat')}
          >
            对话
          </button>
          <button
            type="button"
            className="view-switch-button"
            aria-pressed={activeView === 'knowledge'}
            onClick={() => setActiveView('knowledge')}
          >
            Knowledge
          </button>
        </nav>
        <p className="status">
          {workspaceState.status === 'ready' && '已连接'}
          {workspaceState.status === 'loading' && '正在连接'}
          {workspaceState.status === 'error' && '未连接'}
        </p>
      </header>

      <div className="workspace" data-view={activeView}>
        {activeView === 'chat' ? (
          <>
        <aside className="sidebar" data-open={sidebarOpen}>
          <button type="button" className="new-chat" onClick={handleNewChat}>
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
                data-running={isActiveRun(runStates[item.id])}
                onClick={() => {
                  setSidebarOpen(false)
                  setSelectedId(item.id)
                }}
              >
                <span className="conv-title">{item.title}</span>
                <span className="conv-time">
                  {isActiveRun(runStates[item.id]) ? '回答中…' : formatTime(item.updatedAt)}
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

          {selectedDetail === undefined && !isDraft && (detailLoading || detailError === null) && (
            <p className="hint">正在打开对话…</p>
          )}

          {selectedDetail === undefined && !isDraft && detailError !== null && (
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

          {(selectedDetail !== undefined || isDraft) && selectedId !== null && (
            <section className="conversation">
              <header className="chat-head">
                {/* isDraft 时 selectedDetail 一定不存在，非草稿分支已由外层条件保证有缓存 */}
                <h2>{isDraft ? DRAFT_TITLE : selectedDetail!.conversation.title}</h2>
                {running && <span className="run-badge">回答中…</span>}
                {!running && !isDraft && pendingRun !== null && isRetryable(pendingRun) && (
                  <span className="retry-badge">回答失败</span>
                )}
                {!isDraft && selectedDetail!.conversation.latestCompactionEntryId !== null && (
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

              <div className="messages" ref={messagesRef} onScroll={handleMessagesScroll}>
                {isDraft || selectedDetail!.activePath.length === 0 ? (
                  <p className="hint">发送第一条消息开始对话。</p>
                ) : (
                  selectedDetail!.activePath.map((entry) => {
                    const controlsCurrentRun =
                      selectedRun !== undefined &&
                      entry.type === 'ASSISTANT_MESSAGE' &&
                      entry.payload.runId === selectedRun.runId &&
                      selectedRun.status !== 'failed'
                    return (
                      <MessageEntry
                        key={entry.id}
                        entry={entry}
                        isCurrentLeaf={entry.id === selectedDetail!.conversation.activeLeafEntryId}
                        continueDisabled={running}
                        onContinue={handleContinue}
                        trace={entry.payload.trace ?? []}
                        traceExpanded={controlsCurrentRun ? selectedRun.traceExpanded : undefined}
                        traceRunning={controlsCurrentRun && running}
                        onTraceToggle={controlsCurrentRun ? toggleSelectedTrace : undefined}
                        onTraceLayoutChange={handleTraceLayoutChange}
                      />
                    )
                  })
                )}
                {showTransientAssistant && selectedRun !== undefined && (
                  <StreamingAssistant
                    text={selectedRun.assistantText}
                    trace={selectedRun.trace}
                    failed={selectedRun.status === 'failed'}
                    expanded={selectedRun.traceExpanded}
                    onToggle={toggleSelectedTrace}
                    onTraceLayoutChange={handleTraceLayoutChange}
                  />
                )}
              </div>

              {!following && (selectedRun?.newContentCount ?? 0) > 0 && (
                <button
                  type="button"
                  className="new-content-button"
                  onClick={() => restoreFollow('auto')}
                >
                  ↓ 有新内容 · {selectedRun!.newContentCount}
                </button>
              )}

              {!running && !isDraft && pendingRun !== null && isRetryable(pendingRun) && (
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
          </>
        ) : (
          <main className="knowledge-main">
            <KnowledgeView />
          </main>
        )}
      </div>
    </div>
  )
}

function MessageEntry({
  entry,
  isCurrentLeaf,
  continueDisabled,
  onContinue,
  trace,
  traceExpanded,
  traceRunning = false,
  onTraceToggle,
  onTraceLayoutChange,
}: {
  entry: Entry
  isCurrentLeaf: boolean
  continueDisabled: boolean
  onContinue: () => void
  trace: RunTraceItem[]
  traceExpanded?: boolean
  traceRunning?: boolean
  onTraceToggle?: () => void
  onTraceLayoutChange: () => void
}) {
  if (entry.type === 'USER_MESSAGE') {
    return (
      <div className="msg-row user">
        <div className="bubble user-bubble">
          <p className="bubble-text">
            {entry.payload.action === 'CONTINUE_GENERATION' ? '继续生成' : entry.payload.text}
          </p>
          <time>{formatTimeShort(entry.createdAt)}</time>
        </div>
      </div>
    )
  }
  if (entry.type === 'ASSISTANT_MESSAGE') {
    return (
      <div className="msg-row assistant">
        <div className="bubble assistant-bubble">
          <RunTracePanel
            trace={entry.payload.trace ?? []}
            running={traceRunning}
            expanded={traceExpanded}
            onToggle={onTraceToggle}
            onLayoutChange={onTraceLayoutChange}
          />
          <AssistantEvidenceView
            entryId={entry.id}
            citations={entry.payload.citations ?? []}
            retrievedSources={entry.payload.retrievedSources ?? []}
            trace={trace}
            onLayoutChange={onTraceLayoutChange}
          >
            {(activate) => (
              <MarkdownRenderer
                text={entry.payload.text ?? ''}
                citationIds={new Set((entry.payload.citations ?? []).map((citation) => citation.referenceId))}
                onCitationActivate={activate}
              />
            )}
          </AssistantEvidenceView>
          {entry.payload.completionStatus === 'INCOMPLETE_LENGTH' && (
            <div className="incomplete-answer" role="status">
              <span>回答未完成</span>
              {isCurrentLeaf && (
                <button type="button" onClick={onContinue} disabled={continueDisabled}>
                  继续生成
                </button>
              )}
            </div>
          )}
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

/**
 * 管理回答底部的来源核验闭环：引用只负责打开目标来源，详情状态保持单一活动项，
 * 来源展示只读取当前 Assistant Trace 的安全字段；内部定位失败时不改变消息区滚动权威。
 */
function AssistantEvidenceView({
  entryId,
  citations,
  retrievedSources,
  trace,
  onLayoutChange,
  children,
}: {
  entryId: string
  citations: CitationPayload[]
  retrievedSources: RetrievedSourcePayload[]
  trace: RunTraceItem[]
  onLayoutChange: () => void
  children: (activate: (referenceId: string) => void) => ReactNode
}) {
  const [expanded, setExpanded] = useState(false)
  const [unreferencedExpanded, setUnreferencedExpanded] = useState(false)
  const [activeSourceId, setActiveSourceId] = useState<string | null>(null)
  const [pendingFocus, setPendingFocus] = useState<string | null>(null)
  const cardRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const sourceScrollRef = useRef<HTMLDivElement>(null)
  const citedIds = new Set<string>()
  const uniqueCitations: CitationPayload[] = []
  for (const citation of citations) {
    if (citedIds.has(citation.referenceId)) continue
    citedIds.add(citation.referenceId)
    uniqueCitations.push(citation)
  }
  const sourcesById = new Map<string, RetrievedSourcePayload>()
  for (const source of retrievedSources) {
    if (!sourcesById.has(source.referenceId)) sourcesById.set(source.referenceId, source)
  }
  const unreferenced = [...sourcesById.values()].filter((source) => !citedIds.has(source.referenceId))
  const hasDisclosure = uniqueCitations.length > 0 || sourcesById.size > 0
  const disclosureId = `source-disclosure-${entryId}`
  // Retrieved Source 只通过 originToolCallId 关联同一 Trace 的工具序号、名称和白名单查询摘要。
  const toolContexts = new Map<string, SourceToolContext>()
  let toolNumber = 0
  for (const item of trace) {
    if (item.kind === 'TOOL') {
      toolNumber += 1
      const querySummary = item.requestDetail?.querySummary.trim() ?? ''
      toolContexts.set(item.toolCallId, {
        number: toolNumber,
        name: sourceToolLabel(item.toolName),
        querySummary: querySummary === '' ? null : querySummary,
      })
    }
  }

  useEffect(() => {
    if (!expanded || pendingFocus === null) return
    const referenceId = pendingFocus
    const frame = requestAnimationFrame(() => {
      const card = cardRefs.current[referenceId]
      const scrollContainer = sourceScrollRef.current
      if (card === null || card === undefined || scrollContainer === null) return
      card.focus({ preventScroll: true })
      scrollSourceCardIntoView(scrollContainer, card)
      setPendingFocus(null)
    })
    return () => cancelAnimationFrame(frame)
  }, [expanded, unreferencedExpanded, activeSourceId, pendingFocus])

  const activate = (referenceId: string) => {
    if (!citedIds.has(referenceId)) return
    setExpanded(true)
    setActiveSourceId(referenceId)
    setPendingFocus(referenceId)
    onLayoutChange()
  }

  const toggle = () => {
    setExpanded((current) => !current)
    onLayoutChange()
  }

  const toggleUnreferenced = () => {
    setUnreferencedExpanded((current) => !current)
    onLayoutChange()
  }

  const toggleSource = (referenceId: string) => {
    setActiveSourceId((current) => current === referenceId ? null : referenceId)
    onLayoutChange()
  }

  return (
    <>
      {children(activate)}
      {hasDisclosure ? (
        <section className="source-disclosure" aria-label="来源核验">
          <button
            type="button"
            className="source-disclosure-toggle"
            aria-expanded={expanded}
            aria-controls={disclosureId}
            onClick={toggle}
          >
            <span>来源核验</span>
            <small>
              {uniqueCitations.length} 条回答已引用 · {unreferenced.length} 条本轮召回未引用
            </small>
          </button>
          {expanded ? (
            <div id={disclosureId} className="source-disclosure-body">
              <div
                ref={sourceScrollRef}
                className="source-disclosure-scroll"
                role="region"
                aria-label="来源列表与详情"
                tabIndex={0}
              >
                {uniqueCitations.length > 0 ? (
                  <section aria-labelledby={`${disclosureId}-cited`}>
                    <h4 id={`${disclosureId}-cited`}>回答已引用</h4>
                    <div className="citation-list">
                      {uniqueCitations.map((citation) => {
                        const source = sourcesById.get(citation.referenceId)
                        return (
                          <SourceCard
                            key={citation.referenceId}
                            citation={citation}
                            source={source}
                            referenceId={citation.referenceId}
                            entryId={entryId}
                            active={activeSourceId === citation.referenceId}
                            onToggle={() => toggleSource(citation.referenceId)}
                            tool={source === undefined ? undefined : toolContexts.get(source.originToolCallId ?? '')}
                            cardRef={(card) => {
                              cardRefs.current[citation.referenceId] = card
                            }}
                          />
                        )
                      })}
                    </div>
                  </section>
                ) : null}
                {unreferenced.length > 0 ? (
                  <section aria-labelledby={`${disclosureId}-unreferenced`}>
                    <button
                      type="button"
                      className="source-group-toggle"
                      aria-expanded={unreferencedExpanded}
                      aria-controls={`${disclosureId}-unreferenced-body`}
                      onClick={toggleUnreferenced}
                    >
                      <span id={`${disclosureId}-unreferenced`}>本轮召回未引用</span>
                      <small>{unreferencedExpanded ? '收起' : `展开 · ${unreferenced.length}`}</small>
                    </button>
                    {unreferencedExpanded ? (
                      <div id={`${disclosureId}-unreferenced-body`} className="citation-list">
                        {unreferenced.map((source) => (
                          <SourceCard
                            key={source.referenceId}
                            source={source}
                            referenceId={source.referenceId}
                            entryId={entryId}
                            unreferenced
                            active={activeSourceId === source.referenceId}
                            onToggle={() => toggleSource(source.referenceId)}
                            tool={toolContexts.get(source.originToolCallId ?? '')}
                            cardRef={(card) => {
                              cardRefs.current[source.referenceId] = card
                            }}
                          />
                        ))}
                      </div>
                    ) : null}
                  </section>
                ) : null}
              </div>
            </div>
          ) : null}
        </section>
      ) : null}
    </>
  )
}

/** 单条紧凑来源行及其唯一活动详情；缺失历史字段时只省略对应展示项。 */
function SourceCard({
  citation,
  source,
  referenceId,
  entryId,
  unreferenced = false,
  active,
  onToggle,
  tool,
  cardRef,
}: {
  citation?: CitationPayload
  source?: RetrievedSourcePayload
  referenceId: string
  entryId: string
  unreferenced?: boolean
  active: boolean
  onToggle: () => void
  tool?: SourceToolContext
  cardRef: (card: HTMLDivElement | null) => void
}) {
  const local = source?.kind === 'local' ? source : citation?.kind === 'local' ? citation : null
  const web = source?.kind === 'web' ? source : citation?.kind === 'web' ? citation : null
  const safeUrl = web === null ? null : safeHttpUrl(web.url)
  const note = !unreferenced && citation?.citationNote?.trim() ? citation.citationNote : null
  const excerpt = source?.sourceExcerpt?.trim() ? source.sourceExcerpt : null
  const excerptLabel = local !== null ? '本地证据摘录' : '搜索摘要'
  const detailId = `source-detail-${entryId}-${referenceId}`
  const sourcePosition = source?.resultPosition !== null && source?.resultPosition !== undefined &&
    source.resultPosition > 0 ? `结果位置 #${source.resultPosition}` : null
  const providerRank = web !== null && source?.providerRank !== null && source?.providerRank !== undefined &&
    source.providerRank > 0 ? `Provider 位次 #${source.providerRank}` : null
  const recallSummary = [sourcePosition, providerRank].filter((value): value is string => value !== null).join(' · ')
  const retrievedAt = source?.retrievedAt || web?.retrievedAt || null

  return (
    <div
      id={`source-card-${entryId}-${referenceId}`}
      ref={cardRef}
      className="citation-card"
      data-active={active ? 'true' : 'false'}
      data-unreferenced={unreferenced ? 'true' : 'false'}
      tabIndex={-1}
    >
      <span className="citation-ref">[{referenceId}]</span>
      <div className="citation-card-content">
        <div className="citation-card-heading">
          {local !== null ? <strong>{local.documentName}</strong> : null}
          {web !== null ? (
            safeUrl === null ? (
              <strong>{web.title}</strong>
            ) : (
              <a href={safeUrl} target="_blank" rel="noopener noreferrer">
                {web.title}
              </a>
            )
          ) : null}
          <button
            type="button"
            className="source-card-toggle"
            aria-expanded={active}
            aria-controls={detailId}
            aria-label={`${active ? '收起' : '展开'}来源详情 [${referenceId}]`}
            onClick={onToggle}
          >
            {active ? '收起详情' : '查看详情'}
          </button>
          </div>
        {local !== null ? <small>{local.location}</small> : null}
        {web !== null ? (
          <small>{web.site} · {web.provider}</small>
        ) : null}
        {recallSummary !== '' ? <small className="source-provenance">{recallSummary}</small> : null}
        {active ? (
          <div id={detailId} className="source-card-details">
            <dl className="source-detail-list">
              {local !== null ? (
                <>
                  <div><dt>文档</dt><dd>{local.documentName}</dd></div>
                  <div><dt>位置</dt><dd>{local.location}</dd></div>
                </>
              ) : null}
              {web !== null ? (
                <>
                  <div><dt>标题</dt><dd>{web.title}</dd></div>
                  <div><dt>站点</dt><dd>{web.site}</dd></div>
                  <div><dt>Provider</dt><dd>{web.provider}</dd></div>
                  {safeUrl !== null ? (
                    <div><dt>链接</dt><dd><a href={safeUrl} target="_blank" rel="noopener noreferrer">{safeUrl}</a></dd></div>
                  ) : null}
                  {web.dateLabel ? <div><dt>日期</dt><dd>{web.dateLabel}</dd></div> : null}
                </>
              ) : null}
              {tool !== undefined ? <div><dt>首次工具</dt><dd>工具 #{tool.number} · {tool.name}</dd></div> : null}
              {tool?.querySummary !== null && tool?.querySummary !== undefined ? (
                <div><dt>安全查询</dt><dd>{tool.querySummary}</dd></div>
              ) : null}
              {sourcePosition !== null ? <div><dt>结果位置</dt><dd>{sourcePosition}</dd></div> : null}
              {providerRank !== null ? <div><dt>Provider 位次</dt><dd>{providerRank}</dd></div> : null}
              {retrievedAt !== null ? <div><dt>检索时间</dt><dd>{formatTime(retrievedAt)}</dd></div> : null}
            </dl>
            {note !== null ? (
              <p className="source-note">
                <b>Agent 相关性摘要</b>
                {note}
              </p>
            ) : null}
            {excerpt !== null ? (
              <p className="source-excerpt">
                <b>{excerptLabel}</b>
                {excerpt}
              </p>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  )
}

type SourceToolContext = {
  number: number
  name: string
  querySummary: string | null
}

function sourceToolLabel(toolName: string): string {
  if (toolName === 'search_web_bocha') return '博查网页搜索'
  if (toolName === 'search_web_searchapi') return 'SearchApi.io 网页搜索'
  if (toolName === 'search_local_knowledge') return '本地知识库检索'
  return toolName || '工具调用'
}

/** 只调整来源内部容器，避免 Citation 定位把消息区或整页滚到末尾。 */
function scrollSourceCardIntoView(container: HTMLElement, card: HTMLElement): void {
  const containerRect = container.getBoundingClientRect()
  const cardRect = card.getBoundingClientRect()
  if (cardRect.top < containerRect.top) {
    container.scrollTop -= containerRect.top - cardRect.top
  } else if (cardRect.bottom > containerRect.bottom) {
    container.scrollTop += cardRect.bottom - containerRect.bottom
  }
}

function safeHttpUrl(value: string): string | null {
  try {
    const url = new URL(value)
    if ((url.protocol !== 'http:' && url.protocol !== 'https:') || url.username || url.password) {
      return null
    }
    return url.toString()
  } catch {
    return null
  }
}

// 运行中的临时 Assistant：只用于展示 delta，最终以持久化 Entry 替换
function StreamingAssistant({
  text,
  trace,
  failed,
  expanded,
  onToggle,
  onTraceLayoutChange,
}: {
  text: string
  trace: ActiveRunState['trace']
  failed: boolean
  expanded: boolean
  onToggle: () => void
  onTraceLayoutChange: () => void
}) {
  return (
    <div className="msg-row assistant">
      <div className="bubble assistant-bubble streaming">
        <RunTracePanel
          trace={trace}
          running={!failed}
          expanded={expanded}
          onToggle={onToggle}
          onLayoutChange={onTraceLayoutChange}
        />
        {text === '' && !failed ? (
          <p className="thinking">正在思考…</p>
        ) : text !== '' ? (
          <MarkdownRenderer text={text} />
        ) : null}
        <p className="model-line">
          {failed ? '本次回答失败，以上临时内容未持久化' : '正在生成…'}
        </p>
      </div>
    </div>
  )
}

export default App
