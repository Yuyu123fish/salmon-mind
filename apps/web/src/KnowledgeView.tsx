import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  deleteDocument,
  fetchDocument,
  fetchDocuments,
  fetchEvidence,
  fetchUploadPolicy,
  fetchUploadSession,
  initUploadSession,
  cancelUploadSession,
  completeUploadSession,
  retryDocument,
  searchKnowledge,
  uploadDocument,
  type DocumentDetail,
  type ParsedDocumentMetadata,
  type DocumentSummary,
  type EvidencePage,
  type KnowledgeState,
  type KnowledgeSearchResult,
  type SearchReason,
  type SearchStage,
} from './knowledgeApi.ts'
import { KnowledgeApiError } from './knowledgeApi.ts'
import { MarkdownRenderer } from './MarkdownRenderer.tsx'
import { quickFileFingerprint, ResumableKnowledgeUploader, type UploadProgress } from './KnowledgeUpload.ts'

const TERMINAL_STATES: KnowledgeState[] = ['READY', 'OCR_REQUIRED', 'FAILED']
const DELETE_ELIGIBLE_STATES: KnowledgeState[] = [...TERMINAL_STATES, 'DELETING']
const PROCESSING_STATES: KnowledgeState[] = [
  'PENDING_DISPATCH', 'QUEUED', 'PARSING', 'EMBEDDING', 'INDEXING',
]

const stateLabels: Record<KnowledgeState, string> = {
  PENDING_DISPATCH: '等待排队',
  QUEUED: '排队中',
  PARSING: '解析中',
  EMBEDDING: '生成向量',
  INDEXING: '写入索引',
  READY: '已就绪',
  OCR_REQUIRED: '需要 OCR',
  FAILED: '处理失败',
  DELETING: '删除未完成',
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

function deleteMessageOf(error: unknown): string {
  if (error instanceof KnowledgeApiError) {
    if (error.code === 'DOCUMENT_DELETE_NOT_ALLOWED') return '当前文档状态不允许删除。'
    if (error.code === 'DOCUMENT_DELETE_INCOMPLETE') return '删除未完成，文档仍不可检索，请重试删除。'
    if (error.code === 'DOCUMENT_NOT_FOUND') return '文档已不存在，正在刷新资料列表。'
  }
  return '删除未完成，请重试删除。'
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

const UPLOAD_SESSION_STORAGE_KEY = 'salmon:knowledge:upload-session:v1'

function metadataItems(metadata: ParsedDocumentMetadata | undefined): Array<[string, string]> {
  if (metadata === undefined) return []
  const items: Array<[string, string | null | undefined]> = [
    ['标题', metadata.title],
    ['作者', metadata.authors.length > 0 ? metadata.authors.join('、') : null],
    ['主题', metadata.subject],
    ['描述', metadata.description],
    ['语言', metadata.language],
    ['创建时间', metadata.createdAt ? formatTime(metadata.createdAt) : null],
    ['修改时间', metadata.modifiedAt ? formatTime(metadata.modifiedAt) : null],
    ['生成应用', metadata.producer],
  ]
  return items.flatMap(([label, value]) => value !== null && value !== undefined && value.length > 0
    ? [[label, value] as [string, string]] : [])
}

const searchStageLabels: Array<[keyof Pick<KnowledgeSearchResult, 'bm25' | 'vector' | 'rrf' | 'finalResults'>, string]> = [
  ['bm25', 'BM25 Top 40'],
  ['vector', 'Vector Top 40'],
  ['rrf', 'RRF Top 20'],
  ['finalResults', 'Final Top 5'],
]

const searchReasonLabels: Record<SearchReason, string> = {
  NO_READY_DOCUMENTS: '当前没有已发布的 READY 资料',
  COMPLETE: '混合检索与精排完成',
  NO_MATCH: '两路检索都没有命中',
  VECTOR_UNAVAILABLE: '向量检索不可用，已降级为 BM25',
  RERANK_UNAVAILABLE: '精排不可用，已降级为 RRF',
  INDEX_UNAVAILABLE: '检索索引暂不可用',
  READY_SCOPE_TOO_LARGE: 'READY 范围超过安全过滤上限',
}

function KnowledgeView() {
  const [documents, setDocuments] = useState<DocumentSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [detail, setDetail] = useState<DocumentDetail | null>(null)
  const [evidence, setEvidence] = useState<EvidencePage | null>(null)
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [uploadPolicy, setUploadPolicy] = useState<Awaited<ReturnType<typeof fetchUploadPolicy>> | null>(null)
  const [uploadProgress, setUploadProgress] = useState<UploadProgress | null>(null)
  const uploadSessionRef = useRef<Awaited<ReturnType<typeof fetchUploadSession>> | null>(null)
  const uploaderRef = useRef<ResumableKnowledgeUploader | null>(null)
  const [retrying, setRetrying] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteConfirming, setDeleteConfirming] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [evidenceError, setEvidenceError] = useState<string | null>(null)
  const [evidencePageNumber, setEvidencePageNumber] = useState(0)
  const [searchQuery, setSearchQuery] = useState('')
  const [searching, setSearching] = useState(false)
  const [searchResult, setSearchResult] = useState<KnowledgeSearchResult | null>(null)
  const [searchError, setSearchError] = useState<string | null>(null)
  const [expandedChunks, setExpandedChunks] = useState<Set<string>>(new Set())
  const evidenceListRef = useRef<HTMLDivElement>(null)
  const [pageVisible, setPageVisible] = useState(window.document.visibilityState === 'visible')
  const detailRequest = useRef(0)
  const documentListRequest = useRef(0)
  const evidenceRequest = useRef(0)
  const searchRequest = useRef(0)
  const mutationGeneration = useRef(0)

  useEffect(() => {
    const updateVisibility = () => setPageVisible(window.document.visibilityState === 'visible')
    window.document.addEventListener('visibilitychange', updateVisibility)
    return () => window.document.removeEventListener('visibilitychange', updateVisibility)
  }, [])

  const refreshDocuments = useCallback(async () => {
    const requestId = ++documentListRequest.current
    const generation = mutationGeneration.current
    setError(null)
    try {
      const next = await fetchDocuments()
      if (requestId !== documentListRequest.current || generation !== mutationGeneration.current) return
      setDocuments(next)
      setSelectedId((current) => {
        if (current !== null && next.some((item) => item.id === current)) return current
        return next[0]?.id ?? null
      })
    } catch (requestError: unknown) {
      if (requestId === documentListRequest.current && generation === mutationGeneration.current) {
        setError(messageOf(requestError))
      }
    } finally {
      if (requestId === documentListRequest.current && generation === mutationGeneration.current) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void refreshDocuments()
  }, [refreshDocuments])

  useEffect(() => {
    let cancelled = false
    void fetchUploadPolicy().then((policy) => {
      if (!cancelled) setUploadPolicy(policy)
    }).catch(() => {
      // 选择文件时仍会再次读取策略；策略失败不阻塞普通资料列表。
    })
    const savedSessionId = window.localStorage.getItem(UPLOAD_SESSION_STORAGE_KEY)
    if (savedSessionId !== null) {
      void (async () => {
        try {
          const session = await fetchUploadSession(savedSessionId)
          if (cancelled) return
          if (session.status === 'COMPLETED') {
            window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
            void refreshDocuments()
            return
          }
          if (session.status === 'COMPLETING') {
            uploadSessionRef.current = session
            setUploadProgress({ session, phase: 'COMPLETING', activePart: null, error: null })
            try {
              const accepted = await completeUploadSession(session.sessionId)
              if (cancelled) return
              window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
              uploadSessionRef.current = null
              setUploadProgress(null)
              setDocuments((current) => [accepted, ...current.filter((item) => item.id !== accepted.id)])
              setSelectedId(accepted.id)
              void refreshDocuments()
            } catch (completeError: unknown) {
              if (!cancelled) setUploadProgress((current) => current === null ? null : {
                ...current,
                phase: 'COMPLETING',
                error: completeError instanceof Error ? completeError : new Error('完成提交失败'),
              })
            }
            return
          }
          if (session.status === 'FAILED' || session.status === 'EXPIRED' || session.status === 'ABORTED') {
            window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
            setUploadProgress({
              session,
              phase: 'FAILED',
              activePart: null,
              error: new Error(session.failureCode === null ? `上传已结束（${session.status}）` : `上传已结束：${session.failureCode}`),
            })
            return
          }
          uploadSessionRef.current = session
          setUploadProgress({ session, phase: 'PAUSED', activePart: null, error: null })
        } catch (requestError: unknown) {
          if (requestError instanceof KnowledgeApiError && requestError.code === 'UPLOAD_SESSION_NOT_FOUND') {
            window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
          }
        }
      })()
    }
    return () => { cancelled = true }
  }, [refreshDocuments])

  const loadDetail = useCallback(async (id: string, silent = false) => {
    const requestId = ++detailRequest.current
    const generation = mutationGeneration.current
    if (!silent) setDetailLoading(true)
    try {
      const next = await fetchDocument(id)
      if (requestId !== detailRequest.current || generation !== mutationGeneration.current) return null
      setDetail(next)
      setDocuments((current) =>
        current.map((item) => (item.id === id ? next.document : item)),
      )
      return next
    } catch (requestError: unknown) {
      if (requestId === detailRequest.current && generation === mutationGeneration.current) {
        setError(messageOf(requestError))
      }
      return null
    } finally {
      if (!silent && requestId === detailRequest.current && generation === mutationGeneration.current) {
        setDetailLoading(false)
      }
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
    setExpandedChunks(new Set())
    setDeleteConfirming(false)
    setDeleteError(null)
    void loadDetail(selectedId)
  }, [loadDetail, selectedId])

  // 非终态持续刷新；页面不可见时降频，进入终态后停止。
  useEffect(() => {
    if (selectedId === null || detail === null || detail.document.id !== selectedId) return
    if (TERMINAL_STATES.includes(detail.document.state) || detail.document.state === 'DELETING') return
    const timer = window.setInterval(() => {
      void loadDetail(selectedId, true)
    }, pageVisible ? 2000 : 10000)
    return () => window.clearInterval(timer)
  }, [detail, loadDetail, pageVisible, selectedId])

  useEffect(() => {
    const requestId = ++evidenceRequest.current
    const generation = mutationGeneration.current
    if (selectedId === null || detail?.document.state !== 'READY') {
      setEvidence(null)
      setExpandedChunks(new Set())
      return
    }
    let cancelled = false
    setEvidence(null)
    setEvidenceError(null)
    void fetchEvidence(selectedId, evidencePageNumber, 20)
      .then((next) => {
        if (!cancelled && requestId === evidenceRequest.current && generation === mutationGeneration.current) {
          setEvidence(next)
        }
      })
      .catch((requestError: unknown) => {
        if (!cancelled && requestId === evidenceRequest.current && generation === mutationGeneration.current) {
          setEvidenceError(messageOf(requestError))
        }
      })
    return () => {
      cancelled = true
    }
  }, [detail?.document.state, evidencePageNumber, selectedId])

  useEffect(() => {
    // 只有文档或页码真正变化时才清空展开项并归零内部滚动；展开单片不抢用户当前阅读位置。
    setExpandedChunks(new Set())
    if (evidenceListRef.current !== null) evidenceListRef.current.scrollTop = 0
  }, [evidencePageNumber, selectedId])

  const readyCount = useMemo(() => documents.filter((item) => item.state === 'READY').length, [documents])
  const activeCount = useMemo(
    () => documents.filter((item) => PROCESSING_STATES.includes(item.state)).length,
    [documents],
  )

  const handleUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (file === undefined) return
    mutationGeneration.current += 1
    setError(null)
    let policy = uploadPolicy
    try {
      if (policy === null) {
        policy = await fetchUploadPolicy()
        setUploadPolicy(policy)
      }
      let activeSession = uploadSessionRef.current
      if (activeSession !== null) {
        // Server 不把客户端指纹放进 Session View；重选时用一次轻量 Header 比较，
        // 再由上传器逐段复核已确认 checksum，避免零已确认 part 时误接续另一文件。
        activeSession = await fetchUploadSession(activeSession.sessionId, quickFileFingerprint(file))
        uploadSessionRef.current = activeSession
      }
      if (policy.resumableEnabled && (activeSession !== null || file.size > policy.resumableThresholdBytes)) {
        if (activeSession !== null && (activeSession.fileName !== file.name || activeSession.sizeBytes !== file.size)) {
          throw new Error('所选文件与已有上传会话不一致，请先取消原会话。')
        }
        const session = activeSession ?? await initUploadSession({
          fileName: file.name,
          declaredMediaType: file.type || 'application/octet-stream',
          sizeBytes: file.size,
          fileFingerprint: quickFileFingerprint(file),
          lastModifiedMillis: file.lastModified,
        })
        uploadSessionRef.current = session
        window.localStorage.setItem(UPLOAD_SESSION_STORAGE_KEY, session.sessionId)
        setUploadProgress({ session, phase: 'UPLOADING', activePart: null, error: null })
        setUploading(true)
        const uploader = new ResumableKnowledgeUploader(file, session, (progress) => {
          setUploadProgress(progress)
        }, { maxConcurrency: policy?.maxConcurrentParts })
        uploaderRef.current = uploader
        const accepted = await uploader.start()
        uploadSessionRef.current = null
        uploaderRef.current = null
        window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
        setUploadProgress(null)
        documentListRequest.current += 1
        setDocuments((current) => [accepted, ...current.filter((item) => item.id !== accepted.id)])
        setSelectedId(accepted.id)
        void refreshDocuments()
        return
      }
      setUploading(true)
      const accepted = await uploadDocument(file)
      // 上传/重试的本地结果优先；使尚未返回的旧列表请求失效，避免回写旧快照。
      documentListRequest.current += 1
      setDocuments((current) => [accepted, ...current.filter((item) => item.id !== accepted.id)])
      setSelectedId(accepted.id)
      void refreshDocuments()
    } catch (requestError: unknown) {
      setUploadProgress((current) => current === null ? null : { ...current, phase: 'FAILED', error: requestError instanceof Error ? requestError : new Error('上传失败') })
      setError(messageOf(requestError))
    } finally {
      setUploading(false)
    }
  }

  const handlePauseUpload = () => uploaderRef.current?.pause()
  const handleResumeUpload = () => {
    if (uploaderRef.current !== null) uploaderRef.current.resume()
  }

  const handleRetryCompleting = async () => {
    const session = uploadSessionRef.current
    if (session === null || session.status !== 'COMPLETING') return
    try {
      const accepted = await completeUploadSession(session.sessionId)
      uploadSessionRef.current = null
      window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
      setUploadProgress(null)
      setDocuments((current) => [accepted, ...current.filter((item) => item.id !== accepted.id)])
      setSelectedId(accepted.id)
      void refreshDocuments()
    } catch (requestError: unknown) {
      setUploadProgress((current) => current === null ? null : {
        ...current,
        phase: 'COMPLETING',
        error: requestError instanceof Error ? requestError : new Error('完成提交失败'),
      })
    }
  }

  const handleCancelUpload = async () => {
    const session = uploadSessionRef.current
    if (session === null || session.status === 'COMPLETING') return
    try {
      await cancelUploadSession(session.sessionId)
      uploaderRef.current?.cancel()
      uploaderRef.current = null
      uploadSessionRef.current = null
      window.localStorage.removeItem(UPLOAD_SESSION_STORAGE_KEY)
      setUploadProgress(null)
      setError(null)
    } catch (requestError: unknown) {
      setError(messageOf(requestError))
    }
  }

  const handleRetry = async () => {
    if (selectedId === null || retrying) return
    mutationGeneration.current += 1
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

  const handleDelete = async () => {
    if (selectedId === null || deleting) return
    const deletingId = selectedId
    mutationGeneration.current += 1
    detailRequest.current += 1
    evidenceRequest.current += 1
    searchRequest.current += 1
    setDeleting(true)
    setLoading(false)
    setDetailLoading(false)
    setSearching(false)
    setDeleteConfirming(false)
    setDeleteError(null)
    setError(null)
    setSearchResult(null)
    setEvidence(null)
    setEvidenceError(null)
    setExpandedChunks(new Set())
    try {
      await deleteDocument(deletingId)
      const removedIndex = documents.findIndex((item) => item.id === deletingId)
      const remaining = documents.filter((item) => item.id !== deletingId)
      const nextId = remaining[removedIndex]?.id ?? remaining[removedIndex - 1]?.id ?? null
      setDocuments(remaining)
      setSelectedId(nextId)
      setDetail(null)
      setEvidence(null)
      setEvidenceError(null)
      setExpandedChunks(new Set())
      setEvidencePageNumber(0)
    } catch (requestError: unknown) {
      setDeleteError(deleteMessageOf(requestError))
      if (requestError instanceof KnowledgeApiError && requestError.code === 'DOCUMENT_NOT_FOUND') {
        void refreshDocuments()
      } else {
        await loadDetail(deletingId)
      }
    } finally {
      setDeleting(false)
    }
  }

  const handleSearch = async () => {
    const query = searchQuery.trim()
    if (query === '' || searching) return
    const requestId = ++searchRequest.current
    const generation = mutationGeneration.current
    setSearching(true)
    setSearchError(null)
    setSearchResult(null)
    try {
      const next = await searchKnowledge(query)
      if (requestId === searchRequest.current && generation === mutationGeneration.current) setSearchResult(next)
    } catch (requestError: unknown) {
      if (requestId === searchRequest.current && generation === mutationGeneration.current) {
        setSearchError(messageOf(requestError))
      }
    } finally {
      if (requestId === searchRequest.current && generation === mutationGeneration.current) setSearching(false)
    }
  }

  return (
    <section className="knowledge-view" aria-labelledby="knowledge-title">
      <header className="knowledge-hero">
        <div>
          <h1 id="knowledge-title">本地资料台</h1>
        </div>
        <label className="knowledge-upload">
          <span>{uploading ? '正在接收…' : '选择文档'}</span>
          <small>TXT / MD / PDF / DOCX · {uploadPolicy === null ? '读取服务端策略…' : `最大 ${formatBytes(uploadPolicy.maxObjectBytes)}，超过 ${formatBytes(uploadPolicy.resumableThresholdBytes)} 可恢复`}</small>
          <input
            type="file"
            accept=".txt,.md,.markdown,.pdf,.docx,text/plain,text/markdown,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            onChange={(event) => void handleUpload(event)}
            disabled={uploading}
          />
        </label>
      </header>

      {uploadProgress !== null && (
        <section className="knowledge-upload-card" aria-label="可恢复上传" role="status">
          <div>
            <strong>{uploadProgress.session.fileName}</strong>
            <span>{uploadProgress.session.confirmedBytes.toLocaleString('zh-CN')} / {uploadProgress.session.sizeBytes.toLocaleString('zh-CN')} 字节（{Math.round(uploadProgress.session.confirmedBytes / uploadProgress.session.sizeBytes * 100)}%）</span>
            <small>已确认 {uploadProgress.session.confirmedPartNumbers.length} / {uploadProgress.session.totalParts} 段 · {uploadProgress.phase === 'COMPLETING' ? '正在校验并提交' : uploadProgress.phase === 'PAUSED' ? '已暂停，重新选择同一文件可继续' : uploadProgress.phase === 'FAILED' ? `已结束（${uploadProgress.session.status}）` : '上传中'}</small>
          </div>
          <div className="detail-actions">
            {uploadProgress.phase === 'UPLOADING' && uploaderRef.current !== null && (
              <button type="button" className="quiet-button" onClick={handlePauseUpload}>暂停</button>
            )}
            {uploadProgress.phase === 'PAUSED' && uploaderRef.current !== null && (
              <button type="button" className="primary-small" onClick={handleResumeUpload}>继续</button>
            )}
            {uploadProgress.phase === 'COMPLETING' && uploadSessionRef.current !== null && (
              <button type="button" className="quiet-button" onClick={() => void handleRetryCompleting()}>重试提交</button>
            )}
            {uploadProgress.phase !== 'COMPLETING' && uploadSessionRef.current !== null && <button type="button" className="danger-small" onClick={() => void handleCancelUpload()}>取消</button>}
          </div>
          {uploadProgress.error !== null && <p className="detail-error">{uploadProgress.error.message}</p>}
        </section>
      )}

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

      <details className="retrieval-diagnostics">
        <summary className="retrieval-summary">
          <div>
            <p className="kicker">检索诊断</p>
            <h2 id="retrieval-title">看看一条查询如何穿过资料</h2>
          </div>
          <span>{searchResult?.policyVersion ?? '按需展开'}</span>
        </summary>
        <div className="retrieval-diagnostics-body" aria-labelledby="retrieval-title">
          <form className="retrieval-form" onSubmit={(event) => { event.preventDefault(); void handleSearch() }}>
            <input
              value={searchQuery}
              onChange={(event) => setSearchQuery(event.target.value)}
              placeholder="输入中文或英文查询"
              maxLength={2000}
              aria-label="检索查询"
            />
            <button type="submit" className="primary-small" disabled={searching || searchQuery.trim() === ''}>
              {searching ? '检索中…' : '运行检索'}
            </button>
          </form>
          {searchError !== null && <p className="detail-error">{searchError}</p>}
          {searchResult !== null && (
            <>
              <div className={`retrieval-status ${searchResult.status.toLowerCase()}`} role="status">
                <strong>{searchResult.status}</strong>
                <span>{searchReasonLabels[searchResult.reason]}</span>
                <small>已执行：{searchResult.executedStages.join(' → ') || '—'} · 跳过：{searchResult.skippedStages.join('、') || '—'}</small>
              </div>
              <div className="retrieval-stages">
                {searchStageLabels.map(([key, label]) => {
                  const stage: SearchStage = searchResult[key]
                  return (
                    <section className="retrieval-stage" key={key}>
                      <div className="section-heading compact"><h3>{label}</h3><span>{stage.items.length} 条</span></div>
                      {stage.items.length === 0 ? <p className="detail-hint">没有候选</p> : (
                        <ol>
                          {stage.items.map((item) => (
                            <li key={`${key}-${item.evidenceId}`}>
                              <div>
                                <strong>#{item.rank ?? '—'} · {item.documentName}</strong>
                                <small>{item.location} · 诊断分数 {item.score === null ? '—' : item.score.toFixed(4)}</small>
                              </div>
                              <p>{item.text}</p>
                            </li>
                          ))}
                        </ol>
                      )}
                    </section>
                  )
                })}
              </div>
            </>
          )}
        </div>
      </details>

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
              <p>状态、解析信息和切片会在这里出现。</p>
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
                  {DELETE_ELIGIBLE_STATES.includes(detail.document.state) && (
                    <button
                      type="button"
                      className="danger-small"
                      onClick={() => setDeleteConfirming(true)}
                      disabled={deleting || retrying}
                    >
                      {deleting ? '正在删除…' : detail.document.state === 'DELETING' ? '重试删除' : '删除文档'}
                    </button>
                  )}
                  {detail.document.state === 'FAILED' && detail.document.retryable && (
                    <button type="button" className="primary-small" onClick={() => void handleRetry()} disabled={retrying || deleting}>
                      {retrying ? '重新排队…' : '重试'}
                    </button>
                  )}
                </div>
              </header>

              {deleteConfirming && (
                <div className="delete-confirm" role="alert">
                  <div>
                    <strong>确认删除“{detail.document.name}”？</strong>
                    <span>将清理原件及 {detail.document.evidenceCount} 个切片，删除完成后无法恢复。</span>
                  </div>
                  <div className="detail-actions">
                    <button type="button" className="quiet-button" onClick={() => setDeleteConfirming(false)} disabled={deleting}>取消</button>
                    <button type="button" className="danger-small" onClick={() => void handleDelete()} disabled={deleting}>确认删除</button>
                  </div>
                </div>
              )}
              {deleting && <p className="delete-progress" role="status">正在删除“{detail.document.name}”，请不要重复操作。</p>}
              {deleteError !== null && <p className="delete-error" role="alert">{deleteError}</p>}

              <div className="document-meta">
                <div><span>格式</span><strong>{formatLabels[detail.document.format] ?? detail.document.format}</strong></div>
                <div><span>大小</span><strong>{formatBytes(detail.document.sizeBytes)}</strong></div>
                <div><span>字符</span><strong>{detail.textCharCount.toLocaleString('zh-CN')}</strong></div>
                <div><span>页数</span><strong>{detail.pageCount || '—'}</strong></div>
                <div className="meta-wide"><span>SHA-256</span><code>{detail.document.sha256}</code></div>
              </div>

              {metadataItems(detail.metadata).length > 0 && (
                <section className="parsed-metadata" aria-labelledby="parsed-metadata-title">
                  <div className="section-heading compact"><h3 id="parsed-metadata-title">文档元信息</h3></div>
                  <dl>
                    {metadataItems(detail.metadata).map(([label, value]) => (
                      <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
                    ))}
                  </dl>
                </section>
              )}

              {detail.document.state === 'FAILED' && detail.jobs[0]?.errorMessage && (
                <div className="failure-note">
                  <strong>{detail.jobs[0].errorCode ?? '处理失败'}</strong>
                  <span>{detail.jobs[0].errorMessage}</span>
                </div>
              )}
              {detail.document.state === 'OCR_REQUIRED' && (
                <div className="failure-note"><strong>需要 OCR</strong><span>当前版本不会伪造扫描文本；这份 PDF 暂未进入可检索状态。</span></div>
              )}
              {detail.document.state === 'DELETING' && !deleting && (
                <div className="failure-note delete-note"><strong>删除未完成</strong><span>文档已退出未来检索范围；可使用“重试删除”继续清理。</span></div>
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

              {detail.document.state === 'READY' && !deleting && (
                <section className="evidence-section" aria-labelledby="evidence-title">
                  <div className="section-heading compact"><h3 id="evidence-title">切片预览</h3><span>{detail.document.evidenceCount} 个切片</span></div>
                  {evidenceError !== null && <p className="detail-error">{evidenceError}</p>}
                  {evidence === null && evidenceError === null && <p className="detail-hint">正在读取切片…</p>}
                  {evidence !== null && evidence.items.length === 0 && <p className="detail-hint">没有可预览的切片。</p>}
                  <div
                    ref={evidenceListRef}
                    className="evidence-list"
                    role="region"
                    aria-label="切片列表"
                    tabIndex={0}
                  >
                    {evidence?.items.map((item) => (
                      <article
                        className="evidence-card"
                        data-expanded={expandedChunks.has(`${selectedId}:${evidence.page}:${item.id}`)}
                        key={item.id}
                      >
                        <div className="evidence-card-heading">
                          <div>
                            <strong>切片 {item.ordinal + 1}</strong>
                            <span>{item.location}</span>
                          </div>
                          <div>
                            <small>{item.charCount} 字符</small>
                            <button
                              type="button"
                              className="chunk-toggle"
                              aria-expanded={expandedChunks.has(`${selectedId}:${evidence.page}:${item.id}`)}
                              onClick={() => setExpandedChunks((current) => {
                                const key = `${selectedId}:${evidence.page}:${item.id}`
                                const next = new Set(current)
                                if (next.has(key)) next.delete(key)
                                else next.add(key)
                                return next
                              })}
                            >
                              {expandedChunks.has(`${selectedId}:${evidence.page}:${item.id}`) ? '收起' : '展开'}
                            </button>
                          </div>
                        </div>
                        <div className="evidence-card-body">
                          {detail.document.format === 'MARKDOWN'
                            ? <MarkdownRenderer text={item.text} />
                            : <p>{item.text}</p>}
                        </div>
                      </article>
                    ))}
                  </div>
                  {evidence !== null && evidence.total > evidence.size && (
                    <div className="evidence-pager" aria-label="切片分页">
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
