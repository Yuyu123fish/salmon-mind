import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  fetchDocument,
  fetchDocuments,
  fetchEvidence,
  retryDocument,
  uploadDocument,
  type DocumentDetail,
  type DocumentSummary,
  type EvidencePage,
  type KnowledgeState,
} from './knowledgeApi.ts'

const TERMINAL_STATES: KnowledgeState[] = ['READY', 'OCR_REQUIRED', 'FAILED']

const stateLabels: Record<KnowledgeState, string> = {
  PENDING_DISPATCH: '等待排队',
  QUEUED: '排队中',
  PARSING: '解析中',
  EMBEDDING: '生成向量',
  INDEXING: '写入索引',
  READY: '已就绪',
  OCR_REQUIRED: '需要 OCR',
  FAILED: '处理失败',
}

const formatLabels: Record<string, string> = {
  TEXT: 'TXT',
  MARKDOWN: 'MD',
  PDF: 'PDF',
  DOCX: 'DOCX',
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : '知识文档请求失败'
}

function formatTime(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString('zh-CN', { hour12: false })
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function stateClass(state: KnowledgeState): string {
  return state.toLowerCase().replace('_', '-')
}

function KnowledgeView() {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<DocumentDetail | null>(null)
  const [evidence, setEvidence] = useState<EvidencePage | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [evidenceError, setEvidenceError] = useState<string | null>(null)
  const [evidencePageNumber, setEvidencePageNumber] = useState(0)
  const [pageVisible, setPageVisible] = useState(window.document.visibilityState === 'visible')
  const detailRequest = useRef(0)
  const documentListRequest = useRef(0)

  useEffect(() => {
    const updateVisibility = () => setPageVisible(window.document.visibilityState === 'visible')
    window.document.addEventListener('visibilitychange', updateVisibility)
    return () => window.document.removeEventListener('visibilitychange', updateVisibility)
  }, [])

  const refreshDocuments = useCallback(async () => {
    const requestId = ++documentListRequest.current
    setError(null)
    try {
      const next = await fetchDocuments()
      if (requestId !== documentListRequest.current) return
      setDocuments(next)
      setSelectedId((current) => {
        if (current !== null && next.some((item) => item.id === current)) return current
        return next[0]?.id ?? null
      })
    } catch (requestError: unknown) {
      if (requestId === documentListRequest.current) setError(messageOf(requestError))
    } finally {
      if (requestId === documentListRequest.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refreshDocuments()
  }, [refreshDocuments])

  const loadDetail = useCallback(async (id: string, silent = false) => {
    const requestId = ++detailRequest.current
    if (!silent) setDetailLoading(true)
    try {
      const next = await fetchDocument(id)
      if (requestId !== detailRequest.current) return null
      setDetail(next)
      setDocuments((current) =>
        current.map((item) => (item.id === id ? next.document : item)),
      )
      return next
    } catch (requestError: unknown) {
      if (requestId === detailRequest.current) setError(messageOf(requestError))
      return null
    } finally {
      if (!silent && requestId === detailRequest.current) setDetailLoading(false)
    }
  }, [])

  useEffect(() => {
    if (selectedId === null) {
      detailRequest.current += 1
      setDetail(null)
      setEvidence(null)
      return
    }
    setDetail(null)
    setEvidence(null)
    setEvidenceError(null)
    setEvidencePageNumber(0)
    void loadDetail(selectedId)
  }, [loadDetail, selectedId])

  // 非终态持续刷新；页面不可见时降频，进入终态后停止。
  useEffect(() => {
    if (selectedId === null || detail === null || detail.document.id !== selectedId) return
    if (TERMINAL_STATES.includes(detail.document.state)) return
    const timer = window.setInterval(() => {
      void loadDetail(selectedId, true)
    }, pageVisible ? 2000 : 10000)
    return () => window.clearInterval(timer)
  }, [detail, loadDetail, pageVisible, selectedId])

  useEffect(() => {
    if (selectedId === null || detail?.document.state !== 'READY') return
    let cancelled = false
    setEvidence(null)
    setEvidenceError(null)
    void fetchEvidence(selectedId, evidencePageNumber, 20)
      .then((next) => {
        if (!cancelled) setEvidence(next)
      })
      .catch((requestError: unknown) => {
        if (!cancelled) setEvidenceError(messageOf(requestError))
      })
    return () => {
      cancelled = true
    }
  }, [detail?.document.state, evidencePageNumber, selectedId])

  const readyCount = useMemo(() => documents.filter((item) => item.state === 'READY').length, [documents])
  const activeCount = useMemo(
    () => documents.filter((item) => !TERMINAL_STATES.includes(item.state)).length,
    [documents],
  )

  const handleUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file === undefined) return
    setUploading(true)
    setError(null)
    try {
      const accepted = await uploadDocument(file)
      // 上传/重试的本地结果优先；使尚未返回的旧列表请求失效，避免回写旧快照。
      documentListRequest.current += 1
      setDocuments((current) => [accepted, ...current.filter((item) => item.id !== accepted.id)])
      setSelectedId(accepted.id)
    } catch (requestError: unknown) {
      setError(messageOf(requestError))
    } finally {
      setUploading(false)
    }
  }

  const handleRetry = async () => {
    if (selectedId === null || retrying) return
    setRetrying(true)
    setError(null)
    try {
      const next = await retryDocument(selectedId)
      documentListRequest.current += 1
      setDocuments((current) => current.map((item) => (item.id === next.id ? next : item)))
      await loadDetail(selectedId)
    } catch (requestError: unknown) {
      setError(messageOf(requestError))
    } finally {
      setRetrying(false)
    }
  }

  return (
    <section className="knowledge-view" aria-labelledby="knowledge-title">
      <header className="knowledge-hero">
        <div>
          <p className="kicker">本地资料台</p>
          <h1 id="knowledge-title">把资料放在手边，等它变得可读。</h1>
          <p className="lede">
            上传一份 TXT、Markdown、PDF 或 DOCX。页面会持续显示处理进度，只有完整建好索引后才标记为已就绪。
          </p>
        </div>
        <label className="knowledge-upload">
          <span>{uploading ? '正在接收…' : '选择文档'}</span>
          <small>TXT / MD / PDF / DOCX · 最大 50 MiB</small>
          <input
            type="file"
            accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            onChange={(event) => void handleUpload(event)}
            disabled={uploading}
          />
        </label>
      </header>

      {error !== null && (
        <div className="knowledge-alert" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => void refreshDocuments()}>重新读取</button>
        </div>
      )}

      <div className="knowledge-stats" aria-label="知识库概览">
        <div><strong>{documents.length}</strong><span>份资料</span></div>
        <div><strong>{readyCount}</strong><span>已就绪</span></div>
        <div><strong>{activeCount}</strong><span>处理中</span></div>
      </div>

      <div className="knowledge-grid">
        <section className="document-shelf" aria-labelledby="document-list-title">
          <div className="section-heading">
            <div>
              <p className="kicker">资料清单</p>
              <h2 id="document-list-title">最近加入</h2>
            </div>
            <button type="button" className="quiet-button" onClick={() => void refreshDocuments()} disabled={loading}>
              {loading ? '读取中…' : '刷新'}
            </button>
          </div>
          {documents.length === 0 && !loading ? (
            <div className="knowledge-empty">
              <span className="empty-mark">＋</span>
              <p>这里还没有资料。</p>
              <span>从一份短文档开始，先看它如何从排队走到已就绪。</span>
            </div>
          ) : (
            <div className="document-list">
              {documents.map((document) => (
                <button
                  type="button"
                  className="document-row"
                  data-selected={document.id === selectedId}
                  key={document.id}
                  onClick={() => setSelectedId(document.id)}
                >
                  <span className="document-format">{formatLabels[document.format] ?? document.format}</span>
                  <span className="document-copy">
                    <strong>{document.name}</strong>
                    <small>{formatBytes(document.sizeBytes)} · {formatTime(document.updatedAt)}</small>
                  </span>
                  <span className={`state-chip ${stateClass(document.state)}`}>{stateLabels[document.state]}</span>
                </button>
              ))}
            </div>
          )}
        </section>

        <section className="document-detail" aria-labelledby="document-detail-title">
          {selectedId === null ? (
            <div className="detail-placeholder">
              <p className="kicker">资料详情</p>
              <h2>选一份资料</h2>
              <p>状态、解析信息和 Evidence 会在这里出现。</p>
            </div>
          ) : detailLoading && detail === null ? (
            <p className="detail-hint">正在打开资料…</p>
          ) : detail === null ? (
            <div className="detail-placeholder">
              <h2>详情暂时不可用</h2>
              <button type="button" className="quiet-button" onClick={() => void loadDetail(selectedId)}>重新打开</button>
            </div>
          ) : (
            <>
              <header className="detail-header">
                <div>
                  <p className="kicker">资料详情</p>
                  <h2 id="document-detail-title">{detail.document.name}</h2>
                </div>
                <div className="detail-actions">
                  <span className={`state-chip ${stateClass(detail.document.state)}`}>{stateLabels[detail.document.state]}</span>
                  {detail.document.state === 'FAILED' && detail.document.retryable && (
                    <button type="button" className="primary-small" onClick={() => void handleRetry()} disabled={retrying}>
                      {retrying ? '重新排队…' : '重试'}
                    </button>
                  )}
                </div>
              </header>

              <div className="document-meta">
                <div><span>格式</span><strong>{formatLabels[detail.document.format] ?? detail.document.format}</strong></div>
                <div><span>大小</span><strong>{formatBytes(detail.document.sizeBytes)}</strong></div>
                <div><span>字符</span><strong>{detail.textCharCount.toLocaleString('zh-CN')}</strong></div>
                <div><span>页数</span><strong>{detail.pageCount || '—'}</strong></div>
                <div className="meta-wide"><span>SHA-256</span><code>{detail.document.sha256}</code></div>
              </div>

              {detail.document.state === 'FAILED' && detail.jobs[0]?.errorMessage && (
                <div className="failure-note">
                  <strong>{detail.jobs[0].errorCode ?? '处理失败'}</strong>
                  <span>{detail.jobs[0].errorMessage}</span>
                </div>
              )}
              {detail.document.state === 'OCR_REQUIRED' && (
                <div className="failure-note"><strong>需要 OCR</strong><span>当前版本不会伪造扫描文本；这份 PDF 暂未进入可检索状态。</span></div>
              )}

              <section className="job-history" aria-labelledby="job-history-title">
                <div className="section-heading compact"><h3 id="job-history-title">处理轨迹</h3><span>{formatTime(detail.document.updatedAt)}</span></div>
                <ol>
                  {detail.jobs.map((job) => (
                    <li key={job.id}>
                      <span className={`timeline-dot ${stateClass(job.state)}`} />
                      <div><strong>第 {job.attemptNumber} 次处理 · {stateLabels[job.state]}</strong><small>{formatTime(job.updatedAt)}</small></div>
                    </li>
                  ))}
                </ol>
              </section>

              {detail.document.state === 'READY' && (
                <section className="evidence-section" aria-labelledby="evidence-title">
                  <div className="section-heading compact"><h3 id="evidence-title">Evidence 预览</h3><span>{detail.document.evidenceCount} 个片段</span></div>
                  {evidenceError !== null && <p className="detail-error">{evidenceError}</p>}
                  {evidence === null && evidenceError === null && <p className="detail-hint">正在读取片段…</p>}
                  {evidence !== null && evidence.items.length === 0 && <p className="detail-hint">没有可预览的片段。</p>}
                  <div className="evidence-list">
                    {evidence?.items.map((item) => (
                      <article className="evidence-card" key={item.id}>
                        <div><span>{item.location}</span><small>{item.charCount} 字</small></div>
                        <p>{item.text}</p>
                      </article>
                    ))}
                  </div>
                  {evidence !== null && evidence.total > evidence.size && (
                    <div className="evidence-pager" aria-label="Evidence 分页">
                      <button
                        type="button"
                        className="quiet-button"
                        onClick={() => setEvidencePageNumber((page) => Math.max(0, page - 1))}
                        disabled={evidence.page === 0}
                      >
                        上一页
                      </button>
                      <span>第 {evidence.page + 1} / {Math.ceil(evidence.total / evidence.size)} 页</span>
                      <button
                        type="button"
                        className="quiet-button"
                        onClick={() => setEvidencePageNumber((page) => page + 1)}
                        disabled={(evidence.page + 1) * evidence.size >= evidence.total}
                      >
                        下一页
                      </button>
                    </div>
                  )}
                </section>
              )}
            </>
          )}
        </section>
      </div>
    </section>
  )
}

export default KnowledgeView
