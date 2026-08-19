import { useCallback, useEffect, useRef, useState } from 'react'
import {
  addSearchRoot,
  fetchCodebase,
  registerRepository,
  removeSearchRoot,
  setActiveRepository,
  unregisterRepository,
  updateRepository,
  type CodebaseCatalog,
  type Repository,
} from './codebaseApi.ts'

type LoadState =
  | { status: 'loading' }
  | { status: 'ready' }
  | { status: 'error'; message: string }

type EditDraft = { name: string; aliases: string }

/**
 * 顶部本地仓库入口。catalog 状态独立于 Conversation cache/run state，
 * 旧的加载或 mutation 响应不能覆盖当前 catalog。
 */
export default function RepositoryMenu() {
  const [catalog, setCatalog] = useState<CodebaseCatalog | null>(null)
  const [loadState, setLoadState] = useState<LoadState>({ status: 'loading' })
  const [open, setOpen] = useState(false)
  const [repositoryPath, setRepositoryPath] = useState('')
  const [searchRootPath, setSearchRootPath] = useState('')
  const [editDrafts, setEditDrafts] = useState<Record<string, EditDraft>>({})
  const [mutation, setMutation] = useState(false)
  const [mutationError, setMutationError] = useState<string | null>(null)
  const loadSequence = useRef(0)
  const mutationSequence = useRef(0)
  const loadAbort = useRef<AbortController | null>(null)

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
    if (open) void refresh()
  }, [open, refresh])

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

  const active = catalog?.repositories.find((repository) => repository.id === catalog.activeRepositoryId) ?? null
  const triggerLabel = active === null ? '选择本地仓库' : `本地仓库：${active.name}`

  const addRepository = () => {
    const path = repositoryPath.trim()
    if (path === '') {
      setMutationError('请输入绝对路径')
      return
    }
    void runMutation(async () => {
      await registerRepository(path)
      setRepositoryPath('')
    })
  }

  const addRoot = () => {
    const path = searchRootPath.trim()
    if (path === '') {
      setMutationError('请输入 Search Root 绝对路径')
      return
    }
    void runMutation(async () => {
      await addSearchRoot(path)
      setSearchRootPath('')
    })
  }

  const chooseActive = (repositoryId: string | null) => {
    void runMutation(async () => {
      await setActiveRepository(repositoryId)
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
    <div className="repository-menu">
      <button
        type="button"
        className="repository-trigger"
        aria-expanded={open}
        aria-haspopup="dialog"
        onClick={() => setOpen((current) => !current)}
      >
        <span className="repository-trigger-label">{triggerLabel}</span>
        <span className="repository-trigger-status">{active === null ? '未选择' : statusLabel(active)}</span>
      </button>

      {open && (
        <section className="repository-panel" role="dialog" aria-label="本地仓库管理">
          <header className="repository-panel-head">
            <div>
              <p className="kicker">只读代码库入口</p>
              <h2>本地仓库</h2>
            </div>
            <button type="button" className="repository-close" aria-label="关闭本地仓库管理" onClick={() => setOpen(false)}>
              关闭
            </button>
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
              <p className="repository-platform">
                Server：{catalog.platform.operatingSystem} · 路径示例：<code>{catalog.platform.pathExample}</code>
                {!catalog.gitAvailable && <span className="repository-unavailable"> · Git 不可用</span>}
              </p>

              <div className="repository-section">
                <div className="repository-section-title">
                  <h3>已注册仓库</h3>
                  {active !== null && <button type="button" className="text-button" disabled={mutation} onClick={() => chooseActive(null)}>清空 Active</button>}
                </div>
                {catalog.repositories.length === 0 && <p className="repository-hint">还没有仓库。添加一个 Server 所在机器上的绝对路径。</p>}
                <div className="repository-list">
                  {catalog.repositories.map((repository) => {
                    const draft = editDrafts[repository.id] ?? { name: repository.name, aliases: repository.aliases.join(', ') }
                    const selected = repository.id === catalog.activeRepositoryId
                    return (
                      <article key={repository.id} className="repository-card" data-selected={selected}>
                        <button
                          type="button"
                          className="repository-choice"
                          aria-pressed={selected}
                          disabled={mutation || repository.status === 'UNAVAILABLE'}
                          onClick={() => chooseActive(repository.id)}
                        >
                          <span className="repository-choice-main">
                            <strong>{repository.name}</strong>
                            <small>{statusLabel(repository)}</small>
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
                <h3>添加仓库</h3>
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
                <h3>Search Root</h3>
                <div className="repository-add-row">
                  <input
                    aria-label="Search Root 绝对路径"
                    placeholder={catalog.platform.pathExample}
                    value={searchRootPath}
                    disabled={mutation}
                    onChange={(event) => setSearchRootPath(event.target.value)}
                    onKeyDown={(event) => { if (event.key === 'Enter') addRoot() }}
                  />
                  <button type="button" disabled={mutation} onClick={addRoot}>授权目录</button>
                </div>
                {catalog.searchRoots.length > 0 ? (
                  <ul className="search-root-list">
                    {catalog.searchRoots.map((root) => (
                      <li key={root.id}>
                        <code>{root.path}</code>
                        <button type="button" disabled={mutation} onClick={() => void runMutation(async () => { await removeSearchRoot(root.id) })}>移除</button>
                      </li>
                    ))}
                  </ul>
                ) : <p className="repository-hint">尚未授权发现目录。</p>}
              </div>
            </div>
          )}
        </section>
      )}
    </div>
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
