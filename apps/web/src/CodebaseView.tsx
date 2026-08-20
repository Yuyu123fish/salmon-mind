import { useCallback, useEffect, useRef, useState } from 'react'
import {
  fetchCodebase,
  listCallChains,
  registerRepository,
  setActiveRepository,
  unregisterRepository,
  updateRepository,
  type CallChainSummary,
  type CodebaseCatalog,
  type Repository,
} from './codebaseApi.ts'
import CallChainView from './CallChainView.tsx'

type LoadState =
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'error'; message: string }

type EditDraft = { name: string; aliases: string }

/**
 * Codebase 一级视图：统一承载具体 Repository 的注册、Active 选择与调用链查看。
 * catalog 是唯一状态来源；每类请求都用序号和 AbortController 隔离过期响应。
 */
export default function CodebaseView() {
  const [catalog, setCatalog] = useState<CodebaseCatalog | null>(null)
  const [loadState, setLoadState] = useState<LoadState>({ status: 'loading' })
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<string | null>(null)
  const [repositoryPath, setRepositoryPath] = useState('')
  const [editDrafts, setEditDrafts] = useState<Record<string, EditDraft>>({})
  const [mutation, setMutation] = useState(false)
  const [mutationError, setMutationError] = useState<string | null>(null)
  const [callChains, setCallChains] = useState<CallChainSummary[]>([])
  const [callChainState, setCallChainState] = useState<LoadState>({ status: 'ready' })
  const [openCallChainId, setOpenCallChainId] = useState<string | null>(null)
  const [callChainRefresh, setCallChainRefresh] = useState(0)
  const loadSequence = useRef(0)
  const mutationSequence = useRef(0)
  const callChainSequence = useRef(0)
  const loadAbort = useRef<AbortController | null>(null)
  const callChainAbort = useRef<AbortController | null>(null)

  const refresh = useCallback(async () => {
    const sequence = ++loadSequence.current
    loadAbort.current?.abort()
    const controller = new AbortController()
    loadAbort.current = controller
    setLoadState((current) => current.status === 'ready' ? current : { status: 'loading' })
    try {
      const next = await fetchCodebase(controller.signal)
      if (sequence !== loadSequence.current) return
      setCatalog(next)
      setLoadState({ status: 'ready' })
    } catch (error: unknown) {
      if (controller.signal.aborted || sequence !== loadSequence.current) return
      setLoadState({ status: 'error', message: errorMessage(error) })
    }
  }, [])

  useEffect(() => {
    void refresh()
    return () => loadAbort.current?.abort()
  }, [refresh])

  useEffect(() => {
    setSelectedRepositoryId((current) => {
      if (current !== null && catalog?.repositories.some((repository) => repository.id === current)) {
        return current
      }
      return catalog?.activeRepositoryId ?? catalog?.repositories[0]?.id ?? null
    })
  }, [catalog])

  const selected = catalog?.repositories.find((repository) => repository.id === selectedRepositoryId) ?? null
  const active = catalog?.repositories.find((repository) => repository.id === catalog?.activeRepositoryId) ?? null

  useEffect(() => {
    const sequence = ++callChainSequence.current
    callChainAbort.current?.abort()
    setOpenCallChainId(null)
    if (selectedRepositoryId === null) {
      setCallChains([])
      setCallChainState({ status: 'ready' })
      return
    }
    const controller = new AbortController()
    callChainAbort.current = controller
    setCallChainState({ status: 'loading' })
    void listCallChains(selectedRepositoryId, controller.signal)
      .then((next) => {
        if (controller.signal.aborted || sequence !== callChainSequence.current) return
        setCallChains(next)
        setCallChainState({ status: 'ready' })
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || sequence !== callChainSequence.current) return
        setCallChainState({ status: 'error', message: errorMessage(error) })
      })
    return () => controller.abort()
  }, [selectedRepositoryId, callChainRefresh])

  const runMutation = useCallback(async (action: () => Promise<void>) => {
    const sequence = ++mutationSequence.current
    setMutation(true)
    setMutationError(null)
    try {
      await action()
      if (sequence === mutationSequence.current) await refresh()
    } catch (error: unknown) {
      if (sequence === mutationSequence.current) setMutationError(errorMessage(error))
    } finally {
      if (sequence === mutationSequence.current) setMutation(false)
    }
  }, [refresh])

  const addRepository = () => {
    const path = repositoryPath.trim()
    if (path === '') {
      setMutationError('请输入绝对路径')
      return
    }
    void runMutation(async () => {
      const repository = await registerRepository(path)
      setRepositoryPath('')
      setSelectedRepositoryId(repository.id)
    })
  }

  const chooseActive = (repositoryId: string) => {
    setSelectedRepositoryId(repositoryId)
    void runMutation(async () => {
      await setActiveRepository(repositoryId)
    })
  }

  const clearActive = () => {
    void runMutation(async () => {
      await setActiveRepository(null)
    })
  }

  const update = (repository: Repository) => {
    const draft = editDrafts[repository.id] ?? { name: repository.name, aliases: repository.aliases.join(', ') }
    void runMutation(async () => {
      await updateRepository(repository.id, draft.name.trim(), splitAliases(draft.aliases))
    })
  }

  const removeRepository = (repository: Repository) => {
    void runMutation(async () => {
      await unregisterRepository(repository.id)
    })
  }

  return (
    <main className="codebase-main">
      <div className="codebase-view">
        <section className="repository-panel codebase-panel" role="region" aria-label="Codebase">
          <header className="repository-panel-head">
            <div>
              <p className="kicker">只读代码库入口</p>
              <h1>Codebase</h1>
            </div>
            <p className="codebase-data-note">只读取目标代码；使用数据写入 SalmonMind 根 data。</p>
          </header>

          {loadState.status === 'loading' && <p className="repository-hint">正在读取仓库 catalog…</p>}
          {loadState.status === 'error' && (
            <div className="repository-error" role="alert">
              <p>{loadState.message}</p>
              <button type="button" onClick={() => void refresh()}>重新加载</button>
            </div>
          )}
          {mutationError !== null && <p className="repository-error" role="alert">{mutationError}</p>}

          {catalog !== null && (
            <div className="repository-panel-body">
              <section className="codebase-active" aria-label="当前 Active Repository">
                <div>
                  <p className="kicker">当前 Active Repository</p>
                  <h2>{active?.name ?? '未选择'}</h2>
                </div>
                {active !== null ? (
                  <div className="codebase-active-meta">
                    <span>{statusLabel(active)}</span>
                    <code>{active.path}</code>
                  </div>
                ) : (
                  <p className="repository-hint">选择一个已注册仓库后，下一次 Agent Run 会以它作为默认代码上下文。</p>
                )}
              </section>

              <p className="repository-platform">
                Server：{catalog.platform.operatingSystem} · 路径示例：<code>{catalog.platform.pathExample}</code>
                {!catalog.gitAvailable && <span className="repository-unavailable"> · Git 不可用</span>}
              </p>

              <div className="repository-section">
                <div className="repository-section-title">
                  <h2>已注册仓库</h2>
                  {active !== null && <button type="button" className="text-button" disabled={mutation} onClick={clearActive}>清空 Active</button>}
                </div>
                {catalog.repositories.length === 0 && <p className="repository-hint">还没有仓库。添加一个 Server 所在机器上的绝对路径。</p>}
                <div className="repository-list">
                  {catalog.repositories.map((repository) => {
                    const draft = editDrafts[repository.id] ?? { name: repository.name, aliases: repository.aliases.join(', ') }
                    const isSelected = repository.id === selectedRepositoryId
                    return (
                      <article key={repository.id} className="repository-card" data-selected={isSelected}>
                        <button
                          type="button"
                          className="repository-choice"
                          aria-pressed={isSelected}
                          disabled={mutation || repository.status === 'UNAVAILABLE'}
                          onClick={() => chooseActive(repository.id)}
                        >
                          <span className="repository-choice-main">
                            <strong>{repository.name}</strong>
                            <small>{repository.id === catalog.activeRepositoryId ? 'Active · ' : ''}{statusLabel(repository)}</small>
                          </span>
                          <code>{repository.path}</code>
                        </button>
                        <div className="repository-edit">
                          <label>
                            名称
                            <input
                              aria-label={`${repository.name} 名称`}
                              value={draft.name}
                              disabled={mutation}
                              onChange={(event) => setEditDrafts((current) => ({
                                ...current,
                                [repository.id]: { ...draft, name: event.target.value },
                              }))}
                            />
                          </label>
                          <label>
                            别名（逗号分隔）
                            <input
                              aria-label={`${repository.name} 别名`}
                              value={draft.aliases}
                              disabled={mutation}
                              onChange={(event) => setEditDrafts((current) => ({
                                ...current,
                                [repository.id]: { ...draft, aliases: event.target.value },
                              }))}
                            />
                          </label>
                          <div className="repository-card-actions">
                            <button type="button" disabled={mutation} onClick={() => update(repository)}>保存资料</button>
                            <button type="button" className="danger-button" disabled={mutation} onClick={() => removeRepository(repository)}>取消注册</button>
                          </div>
                        </div>
                      </article>
                    )
                  })}
                </div>
              </div>

              <div className="repository-section">
                <h2>添加仓库</h2>
                <div className="repository-add-row">
                  <input
                    aria-label="仓库绝对路径"
                    placeholder={catalog.platform.pathExample}
                    value={repositoryPath}
                    disabled={mutation}
                    onChange={(event) => setRepositoryPath(event.target.value)}
                    onKeyDown={(event) => { if (event.key === 'Enter') addRepository() }}
                  />
                  <button type="button" disabled={mutation} onClick={addRepository}>添加</button>
                </div>
                <p className="repository-hint">只保存你明确提供的路径，不会扫描整盘，也不会修改目标仓库。</p>
              </div>

              <div className="repository-section">
                <div className="repository-section-title">
                  <h2>当前选中仓库的调用链</h2>
                  {selected !== null && <button type="button" className="text-button" onClick={() => setCallChainRefresh((current) => current + 1)}>刷新</button>}
                </div>
                {selected === null && <p className="repository-hint">选择一个仓库后，这里会显示已保存的调用链。</p>}
                {selected !== null && callChainState.status === 'loading' && <p className="repository-hint">正在读取调用链…</p>}
                {selected !== null && callChainState.status === 'error' && <p className="repository-error" role="alert">{callChainState.message}</p>}
                {selected !== null && callChainState.status === 'ready' && callChains.length === 0 && <p className="repository-hint">当前仓库还没有已保存的调用链。</p>}
                {callChains.length > 0 && (
                  <div className="call-chain-menu-list">
                    {callChains.map((callChain) => (
                      <article className="call-chain-menu-item" key={callChain.id}>
                        <div>
                          <strong>{callChain.name}</strong>
                          <small>{callChain.nodeCount} 个节点 · {callChain.edgeCount} 条边</small>
                        </div>
                        <button type="button" onClick={() => setOpenCallChainId(callChain.id)}>查看</button>
                      </article>
                    ))}
                  </div>
                )}
                {selected !== null && openCallChainId !== null && (
                  <CallChainView
                    repositoryId={selected.id}
                    callChainId={openCallChainId}
                    fallbackName={callChains.find((callChain) => callChain.id === openCallChainId)?.name ?? '调用链'}
                    onClose={() => {
                      setOpenCallChainId(null)
                      setCallChainRefresh((current) => current + 1)
                    }}
                    onDeleted={() => {
                      setCallChains((current) => current.filter((callChain) => callChain.id !== openCallChainId))
                      setOpenCallChainId(null)
                    }}
                  />
                )}
              </div>
            </div>
          )}
        </section>
      </div>
    </main>
  )
}

function splitAliases(value: string): string[] {
  return value.split(',').map((alias) => alias.trim()).filter((alias) => alias !== '')
}

function statusLabel(repository: Repository): string {
  if (repository.status === 'UNAVAILABLE') return `不可访问${repository.unavailableCode === null ? '' : ` · ${repository.unavailableCode}`}`
  const revision = repository.head === null ? '未提交' : repository.head.slice(0, 7)
  const branch = repository.detached ? 'detached' : repository.branch ?? '未命名分支'
  return `${branch} · ${revision}${repository.dirty ? ' · 有改动' : ' · clean'}`
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '代码库服务请求失败'
}
