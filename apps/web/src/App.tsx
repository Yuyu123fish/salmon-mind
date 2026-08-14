import { useCallback, useEffect, useState } from 'react'
import { fetchCurrentWorkspace, type Workspace } from './workspaceApi.ts'

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; workspace: Workspace }
  | { status: 'error'; message: string }

function formatTime(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return iso
  }
  return date.toLocaleString('zh-CN', { hour12: false })
}

function App() {
  const [state, setState] = useState<LoadState>({ status: 'loading' })

  // 加载工作空间
  const loadWorkspace = useCallback(() => {
    setState({ status: 'loading' })
    fetchCurrentWorkspace()
      .then((workspace) => setState({ status: 'ready', workspace }))
      .catch((error: unknown) => {
        const message = error instanceof Error ? error.message : '无法连接后端'
        setState({ status: 'error', message })
      })
  }, [])

  useEffect(() => {
    loadWorkspace()
  }, [loadWorkspace])

  return (
    <div className="shell" data-status={state.status}>
      <header className="topbar">
        <div className="brand">
          <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 15.5 12 8.5l7 7" />
            <path d="M5 19.5 12 12.5l7 7" />
          </svg>
          <span className="product">SalmonMind</span>
        </div>
        <p className="status">
          {state.status === 'ready' && '已连接'}
          {state.status === 'loading' && '正在连接'}
          {state.status === 'error' && '未连接'}
        </p>
      </header>

      <main className="stage">
        {state.status === 'loading' && (
          <p className="hint">正在读取本机工作空间…</p>
        )}

        {state.status === 'error' && (
          <section className="panel">
            <p className="kicker">后端</p>
            <h1>还没有接通</h1>
            <p className="lede">
              {state.message}。先启动 PostgreSQL 与 server，再打开这个页面。
            </p>
            <button type="button" onClick={loadWorkspace}>
              重新连接
            </button>
          </section>
        )}

        {state.status === 'ready' && (
          <section className="panel">
            <p className="kicker">工作空间</p>
            <h1>{state.workspace.name}</h1>
            <p className="lede">
              这是本机唯一的工作空间。知识、复盘和追问会从这里展开。
            </p>
            <dl className="meta">
              <div>
                <dt>标识</dt>
                <dd>{state.workspace.id}</dd>
              </div>
              <div>
                <dt>创建时间</dt>
                <dd>{formatTime(state.workspace.createdAt)}</dd>
              </div>
            </dl>
          </section>
        )}
      </main>
    </div>
  )
}

export default App
