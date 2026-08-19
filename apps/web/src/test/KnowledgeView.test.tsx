import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { KnowledgeApiError, type DocumentDetail, type DocumentSummary, type EvidencePage } from '../knowledgeApi.ts'

const api = vi.hoisted(() => ({
  fetchDocuments: vi.fn(),
  fetchDocument: vi.fn(),
  fetchEvidence: vi.fn(),
  fetchUploadPolicy: vi.fn(),
  fetchUploadSession: vi.fn(),
  retryDocument: vi.fn(),
  searchKnowledge: vi.fn(),
  deleteDocument: vi.fn(),
}))

vi.mock('../knowledgeApi.ts', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../knowledgeApi.ts')>()),
  fetchDocuments: api.fetchDocuments,
  fetchDocument: api.fetchDocument,
  fetchEvidence: api.fetchEvidence,
  fetchUploadPolicy: api.fetchUploadPolicy,
  fetchUploadSession: api.fetchUploadSession,
  retryDocument: api.retryDocument,
  searchKnowledge: api.searchKnowledge,
  deleteDocument: api.deleteDocument,
}))

import KnowledgeView from '../KnowledgeView.tsx'

const NOW = '2026-08-18T08:00:00Z'

function documentSummary(
  id: string,
  name: string,
  state: DocumentSummary['state'] = 'READY',
  format: string = 'MARKDOWN',
): DocumentSummary {
  return {
    id,
    workspaceId: 'workspace-1',
    revisionId: `revision-${id}`,
    latestJobId: `job-${id}`,
    name,
    format,
    mediaType: format === 'MARKDOWN' ? 'text/markdown' : 'text/plain',
    sizeBytes: 128,
    sha256: 'a'.repeat(64),
    state,
    retryable: state === 'FAILED',
    evidenceCount: state === 'READY' ? 2 : 0,
    createdAt: NOW,
    updatedAt: NOW,
  }
}

function detailOf(document: DocumentSummary): DocumentDetail {
  return {
    document,
    jobs: [{
      id: document.latestJobId ?? `job-${document.id}`,
      attemptNumber: 1,
      state: document.state,
      retryable: document.retryable,
      errorCode: null,
      errorMessage: null,
      createdAt: NOW,
      updatedAt: NOW,
      startedAt: NOW,
      endedAt: document.state === 'READY' ? NOW : null,
    }],
    pageCount: 1,
    textCharCount: 80,
    metadata: {
      title: null,
      authors: [],
      subject: null,
      description: null,
      language: null,
      createdAt: null,
      modifiedAt: null,
      producer: null,
    },
  }
}

function evidenceOf(format: string = 'MARKDOWN'): EvidencePage {
  return {
    items: [{
      id: 'evidence-1',
      ordinal: 0,
      location: '第 1 节',
      text: format === 'MARKDOWN' ? '| 名称 | 值 |\n| --- | --- |\n| A | B |' : '# 不是标题\n第二行',
      charCount: 32,
    }],
    page: 0,
    size: 20,
    total: 1,
  }
}

function searchResult() {
  const stage = { items: [] }
  return {
    policyVersion: 'local-hybrid-v1',
    status: 'EMPTY' as const,
    reason: 'NO_MATCH' as const,
    bm25: stage,
    vector: stage,
    rrf: stage,
    finalResults: stage,
    executedStages: ['READY_SCOPE'],
    skippedStages: ['BM25'],
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

describe('KnowledgeView', () => {
  let first: DocumentSummary
  let second: DocumentSummary

  beforeEach(() => {
    first = documentSummary('document-1', '项目说明.md')
    second = documentSummary('document-2', '保留资料.txt', 'READY', 'TEXT')
    api.fetchDocuments.mockResolvedValue([first, second])
    api.fetchUploadPolicy.mockResolvedValue({
      resumableEnabled: true,
      maxObjectBytes: 50 * 1024 * 1024,
      resumableThresholdBytes: 10 * 1024 * 1024,
      partSizeBytes: 5 * 1024 * 1024,
      maxConcurrentParts: 3,
    })
    api.fetchUploadSession.mockRejectedValue(new KnowledgeApiError('UPLOAD_SESSION_NOT_FOUND', '上传会话不存在', 404))
    api.fetchDocument.mockImplementation((id: string) => {
      const document = id === first.id ? first : second
      return Promise.resolve(detailOf(document))
    })
    api.fetchEvidence.mockResolvedValue(evidenceOf())
    api.retryDocument.mockResolvedValue(first)
    api.searchKnowledge.mockResolvedValue(searchResult())
    api.deleteDocument.mockResolvedValue(undefined)
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('requires confirmation with the exact name and slice count, and cancel sends no request', async () => {
    render(<KnowledgeView />)
    fireEvent.click(await screen.findByRole('button', { name: '删除文档' }))

    expect(screen.getByText('确认删除“项目说明.md”？')).toBeInTheDocument()
    expect(screen.getByText('将清理原件及 2 个切片，删除完成后无法恢复。')).toBeInTheDocument()
    expect(api.deleteDocument).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: '取消' }))
    expect(screen.queryByText('确认删除“项目说明.md”？')).toBeNull()
  })

  it('returns 204, removes the document, and selects the next document', async () => {
    render(<KnowledgeView />)
    fireEvent.click(await screen.findByRole('button', { name: '删除文档' }))
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))

    await waitFor(() => expect(api.deleteDocument).toHaveBeenCalledWith(first.id))
    await waitFor(() => expect(screen.queryByText('项目说明.md')).toBeNull())
    expect(await screen.findByRole('heading', { name: '保留资料.txt' })).toBeInTheDocument()
    expect(screen.getByText('资料详情')).toBeInTheDocument()
  })

  it('does not show deletion for a processing document or count it as ready', async () => {
    const processing = documentSummary('processing', '处理中.txt', 'PARSING', 'TEXT')
    api.fetchDocuments.mockResolvedValue([processing])
    api.fetchDocument.mockResolvedValue(detailOf(processing))

    render(<KnowledgeView />)

    expect(await screen.findByText('解析中')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '删除文档' })).toBeNull()
    expect(screen.getByText('处理中', { selector: 'span' })).toBeInTheDocument()
  })

  it('uses the compact page title and places collapsed diagnostics before the document workspace', async () => {
    render(<KnowledgeView />)

    const title = await screen.findByRole('heading', { level: 1, name: '本地资料台' })
    const stats = screen.getByLabelText('知识库概览')
    const diagnostics = screen.getByText('检索诊断').closest('details')
    const workspace = screen.getByRole('heading', { level: 2, name: '最近加入' }).closest('.knowledge-grid')

    expect(title).toBeInTheDocument()
    expect(screen.queryByText('把资料放在手边，等它变得可读。')).toBeNull()
    expect(screen.queryByText(/上传一份 TXT、Markdown、PDF 或 DOCX/)).toBeNull()
    expect(diagnostics).not.toBeNull()
    expect(diagnostics).not.toHaveAttribute('open')
    expect(stats.compareDocumentPosition(diagnostics as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect((diagnostics as Node).compareDocumentPosition(workspace as Node) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('keeps a failed deletion in DELETING and exposes retry deletion without preview', async () => {
    const deleting = documentSummary(first.id, first.name, 'DELETING')
    api.deleteDocument.mockRejectedValue(new KnowledgeApiError(
      'DOCUMENT_DELETE_INCOMPLETE', '删除未完成', 503,
    ))
    api.fetchDocument
      .mockResolvedValueOnce(detailOf(first))
      .mockResolvedValueOnce(detailOf(deleting))

    render(<KnowledgeView />)
    fireEvent.click(await screen.findByRole('button', { name: '删除文档' }))
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))

    expect(await screen.findByText('删除未完成，文档仍不可检索，请重试删除。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '重试删除' })).toBeInTheDocument()
    expect(screen.queryByText('切片预览')).toBeNull()
  })

  it('renders Markdown as GFM and plain text without interpreting Markdown syntax', async () => {
    api.fetchEvidence.mockImplementation((id: string) => Promise.resolve(
      evidenceOf(id === second.id ? 'TEXT' : 'MARKDOWN'),
    ))
    render(<KnowledgeView />)
    expect(await screen.findByRole('table')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '展开' }))

    fireEvent.click(screen.getByRole('button', { name: /保留资料\.txt/ }))
    const plainText = await screen.findByText(/# 不是标题/)
    expect(plainText).toBeInTheDocument()
    expect(plainText.tagName).toBe('P')
    expect(plainText.textContent).toBe('# 不是标题\n第二行')
    expect(screen.queryByRole('heading', { name: '# 不是标题' })).toBeNull()
  })

  it('resets expanded slices when moving to another page', async () => {
    api.fetchEvidence.mockResolvedValue({ ...evidenceOf(), total: 21 })
    render(<KnowledgeView />)

    expect(await screen.findByRole('table')).toBeInTheDocument()
    const evidenceList = screen.getByRole('region', { name: '切片列表' }) as HTMLDivElement
    evidenceList.scrollTop = 180
    fireEvent.click(screen.getByRole('button', { name: '展开' }))
    expect(evidenceList.scrollTop).toBe(180)
    expect(screen.getByRole('button', { name: '收起' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '下一页' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '展开' })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: '收起' })).toBeNull()
    expect((screen.getByRole('region', { name: '切片列表' }) as HTMLDivElement).scrollTop).toBe(0)
  })

  it('ignores stale list, slice, and search responses after deletion starts', async () => {
    const staleList = deferred<DocumentSummary[]>()
    const staleEvidence = deferred<EvidencePage>()
    const staleSearch = deferred<ReturnType<typeof searchResult>>()
    const deletion = deferred<void>()
    api.fetchDocuments.mockResolvedValueOnce([first, second]).mockReturnValueOnce(staleList.promise)
    api.fetchEvidence.mockReturnValueOnce(staleEvidence.promise).mockResolvedValue({
      items: [], page: 0, size: 20, total: 0,
    })
    api.searchKnowledge.mockReturnValue(staleSearch.promise)
    api.deleteDocument.mockReturnValue(deletion.promise)

    render(<KnowledgeView />)
    await waitFor(() => expect(screen.getByRole('button', { name: '刷新' })).not.toBeDisabled())
    fireEvent.click(screen.getByRole('button', { name: '刷新' }))
    fireEvent.click(screen.getByText('检索诊断'))
    fireEvent.change(screen.getByRole('textbox', { name: '检索查询' }), { target: { value: '旧查询' } })
    fireEvent.click(screen.getByRole('button', { name: '运行检索' }))
    fireEvent.click(await screen.findByRole('button', { name: '删除文档' }))
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))

    deletion.resolve()
    await waitFor(() => expect(screen.queryByText('项目说明.md')).toBeNull())
    staleList.resolve([first, second])
    staleEvidence.resolve(evidenceOf())
    staleSearch.resolve(searchResult())
    await Promise.resolve()

    expect(screen.queryByText('项目说明.md')).toBeNull()
    expect(screen.queryByText('旧查询')).toBeNull()
    expect(screen.queryByText('切片 1')).toBeNull()
  })
})
