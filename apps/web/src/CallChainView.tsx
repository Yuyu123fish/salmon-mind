import { useEffect, useMemo, useRef, useState } from 'react'
import {
  CodebaseApiError,
  deleteCallChain,
  fetchCallChain,
  renameCallChain,
  type CallChainDetail,
  type CallChainReference,
} from './codebaseApi.ts'

type CallChainViewProps = {
  repositoryId: string
  callChainId: string
  fallbackName: string
  onClose?: () => void
  onDeleted?: () => void
}

type DetailState =
  | { status: 'loading'; detail: null }
  | { status: 'ready'; detail: CallChainDetail }
  | { status: 'error'; detail: null; message: string; code?: string }

/** 回答卡片与仓库菜单共用的调用链详情面板。请求始终绑定 Repository/Chain 身份。 */
export default function CallChainView(props: CallChainViewProps) {
  const [state, setState] = useState<DetailState>({ status: 'loading', detail: null })
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [nameDraft, setNameDraft] = useState(props.fallbackName)
  const [mutation, setMutation] = useState<'rename' | 'delete' | null>(null)
  const [deleteArmed, setDeleteArmed] = useState(false)
  const requestSequence = useRef(0)

  useEffect(() => {
    const sequence = ++requestSequence.current
    const controller = new AbortController()
    setState({ status: 'loading', detail: null })
    setMutation(null)
    setDeleteArmed(false)
    void fetchCallChain(props.repositoryId, props.callChainId, controller.signal)
      .then((detail) => {
        if (sequence !== requestSequence.current) return
        setState({ status: 'ready', detail })
        setNameDraft(detail.name)
        setSelectedNodeId(detail.nodes[0]?.nodeId ?? null)
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || sequence !== requestSequence.current) return
        setState({ status: 'error', detail: null, message: errorMessage(error), code: errorCode(error) })
      })
    return () => controller.abort()
  }, [props.callChainId, props.repositoryId])

  const detail = state.status === 'ready' ? state.detail : null
  const selectedNode = detail?.nodes.find((node) => node.nodeId === selectedNodeId) ?? detail?.nodes[0] ?? null
  const nodeLabels = useMemo(() => new Map(
    detail?.nodes.map((node) => [node.nodeId, node.qualifiedSymbol]) ?? [],
  ), [detail])

  const saveName = () => {
    const name = nameDraft.trim()
    if (name === '' || mutation !== null) return
    const sequence = requestSequence.current
    setMutation('rename')
    void renameCallChain(props.repositoryId, props.callChainId, name)
      .then((next) => {
        if (sequence !== requestSequence.current) return
        setState({ status: 'ready', detail: next })
        setNameDraft(next.name)
      })
      .catch((error: unknown) => {
        if (sequence !== requestSequence.current) return
        setState((current) => current.status === 'ready'
          ? current
          : { status: 'error', detail: null, message: errorMessage(error), code: errorCode(error) })
      })
      .finally(() => {
        if (sequence === requestSequence.current) setMutation(null)
      })
  }

  const remove = () => {
    if (mutation !== null) return
    if (!deleteArmed) {
      setDeleteArmed(true)
      return
    }
    const sequence = requestSequence.current
    setMutation('delete')
    void deleteCallChain(props.repositoryId, props.callChainId)
      .then(() => {
        if (sequence !== requestSequence.current) return
        props.onDeleted?.()
        props.onClose?.()
      })
      .catch((error: unknown) => {
        if (sequence !== requestSequence.current) return
        setDeleteArmed(false)
        setState((current) => current.status === 'ready'
          ? current
          : { status: 'error', detail: null, message: errorMessage(error), code: errorCode(error) })
      })
      .finally(() => {
        if (sequence === requestSequence.current) setMutation(null)
      })
  }

  return (
    <div className="call-chain-view" role="dialog" aria-label={`调用链：${detail?.name ?? props.fallbackName}`}>
      <header className="call-chain-view-head">
        <div>
          <p className="kicker">本次代码理解</p>
          <h3>{detail?.name ?? props.fallbackName}</h3>
          {detail !== null && <small>{detail.repositoryName} · {detail.nodeCount} 个节点 · {detail.edgeCount} 条边</small>}
        </div>
        {props.onClose !== undefined && <button type="button" className="text-button" onClick={props.onClose}>关闭</button>}
      </header>

      {state.status === 'loading' && <p className="repository-hint">正在读取调用链详情…</p>}
      {state.status === 'error' && (
        <div className="repository-error" role="alert">
          <p>{state.code === 'CALL_CHAIN_DELETED' ? '调用链已删除' : state.message}</p>
        </div>
      )}

      {detail !== null && (
        <>
          <div className="call-chain-actions">
            <label>
              名称
              <input value={nameDraft} disabled={mutation !== null} onChange={(event) => setNameDraft(event.target.value)} />
            </label>
            <button type="button" disabled={mutation !== null || nameDraft.trim() === ''} onClick={saveName}>
              {mutation === 'rename' ? '保存中…' : '重命名'}
            </button>
            <button type="button" className="danger-button" disabled={mutation !== null} onClick={remove}>
              {deleteArmed ? '确认删除（只删除 SalmonMind 内部调用链）' : '删除调用链'}
            </button>
          </div>

          <div className="call-chain-layout">
            <section className="call-chain-section" aria-label="调用链节点">
              <h4>节点顺序</h4>
              <div className="call-chain-node-list">
                {detail.nodes.map((node, index) => (
                  <button
                    type="button"
                    key={node.nodeId}
                    className="call-chain-node"
                    data-selected={selectedNode?.nodeId === node.nodeId}
                    onClick={() => setSelectedNodeId(node.nodeId)}
                  >
                    <strong>{index + 1}. {node.qualifiedSymbol}</strong>
                    <small>{node.path}:{node.startLine}-{node.endLine}</small>
                  </button>
                ))}
              </div>
              <h4>调用边</h4>
              <ul className="call-chain-edge-list">
                {detail.edges.map((edge) => (
                  <li key={`${edge.fromNodeId}-${edge.toNodeId}`}>
                    <span>{nodeLabels.get(edge.fromNodeId) ?? edge.fromNodeId}</span>
                    <span aria-hidden="true">→</span>
                    <span>{nodeLabels.get(edge.toNodeId) ?? edge.toNodeId}</span>
                  </li>
                ))}
              </ul>
            </section>

            <section className="call-chain-section call-chain-node-detail" aria-label="节点详情">
              <h4>节点详情</h4>
              {selectedNode === null ? <p className="repository-hint">请选择一个节点。</p> : (
                <>
                  <p className="call-chain-summary">{selectedNode.summary || '未提供说明'}</p>
                  <dl className="source-detail-list">
                    <dt>符号</dt><dd>{selectedNode.qualifiedSymbol}</dd>
                    <dt>签名</dt><dd><code>{selectedNode.signature}</code></dd>
                    <dt>位置</dt><dd><code>{selectedNode.path}:{selectedNode.startLine}-{selectedNode.endLine}</code></dd>
                    <dt>语言</dt><dd>{selectedNode.language}</dd>
                    <dt>Git</dt><dd>{observationLabel(selectedNode.observation)}</dd>
                  </dl>
                  <pre className="call-chain-source"><code>{selectedNode.source}</code></pre>
                  <h5>Revision</h5>
                  <ul className="call-chain-revision-list">
                    {selectedNode.revisions.map((revision) => (
                      <li key={revision.id}>
                        <code>{revision.sourceHash.slice(0, 12)}</code> · {revision.path}:{revision.startLine}-{revision.endLine}
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  )
}

/** Assistant 下方的紧凑引用卡片；详情名以 API 当前权威值为准。 */
export function CallChainCard({ reference }: { reference: CallChainReference }) {
  const [open, setOpen] = useState(false)
  const [deleted, setDeleted] = useState(false)
  return (
    <>
      <article className="call-chain-card">
        <div>
          <strong>{deleted ? '调用链已删除' : reference.name}</strong>
          <small>{reference.nodeCount} 个节点 · {reference.edgeCount} 条边</small>
        </div>
        {!deleted && <button type="button" onClick={() => setOpen(true)}>查看调用链</button>}
      </article>
      {open && !deleted && (
        <div className="call-chain-modal-backdrop" role="presentation">
          <div className="call-chain-modal">
            <CallChainView
              repositoryId={reference.repositoryId}
              callChainId={reference.id}
              fallbackName={reference.name}
              onClose={() => setOpen(false)}
              onDeleted={() => setDeleted(true)}
            />
          </div>
        </div>
      )}
    </>
  )
}

function observationLabel(observation: CallChainDetail['nodes'][number]['observation']): string {
  const revision = observation.head === null ? '未提交' : observation.head.slice(0, 12)
  return `${observation.branch ?? (observation.detached ? 'detached' : '未命名分支')} · ${revision}${observation.dirty ? ' · 有改动' : ' · clean'}`
}

function errorCode(error: unknown): string | undefined {
  return error instanceof CodebaseApiError ? error.code : undefined
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '调用链服务请求失败'
}
