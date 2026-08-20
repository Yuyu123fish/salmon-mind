import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteCallChain, fetchCallChain, renameCallChain } from '../codebaseApi.ts'
import CallChainView from '../CallChainView.tsx'

vi.mock('../codebaseApi.ts', () => ({
  CodebaseApiError: class CodebaseApiError extends Error {
    code: string

    constructor(code: string, message: string) {
      super(message)
      this.code = code
    }
  },
  deleteCallChain: vi.fn(),
  fetchCallChain: vi.fn(),
  renameCallChain: vi.fn(),
}))

const api = {
  fetchCallChain: vi.mocked(fetchCallChain),
  renameCallChain: vi.mocked(renameCallChain),
  deleteCallChain: vi.mocked(deleteCallChain),
}

const detail = {
  id: 'chain-1',
  repositoryId: 'repo-1',
  repositoryName: 'demo',
  name: '入口调用链',
  nodeCount: 2,
  edgeCount: 2,
  originConversationId: 'conversation-1',
  originAnswerEntryId: 'answer-1',
  createdAt: '2026-08-20T00:00:00Z',
  updatedAt: '2026-08-20T00:00:00Z',
  nodes: [
    {
      nodeId: 'a'.repeat(64),
      revisionId: 'revision-1',
      language: 'java',
      qualifiedSymbol: 'Demo.entry',
      signature: 'void entry()',
      summary: '入口调用',
      sourceHash: 'b'.repeat(64),
      path: 'src/Demo.java',
      startLine: 1,
      endLine: 2,
      source: 'void entry() {\n  service.run();\n}',
      observation: observation(),
      revisions: [],
    },
    {
      nodeId: 'c'.repeat(64),
      revisionId: 'revision-2',
      language: 'java',
      qualifiedSymbol: 'Service.run',
      signature: 'void run()',
      summary: '服务实现',
      sourceHash: 'd'.repeat(64),
      path: 'src/Service.java',
      startLine: 4,
      endLine: 5,
      source: 'void run() {\n  return;\n}',
      observation: observation(),
      revisions: [],
    },
  ],
  edges: [
    { fromNodeId: 'a'.repeat(64), toNodeId: 'c'.repeat(64) },
    { fromNodeId: 'c'.repeat(64), toNodeId: 'a'.repeat(64) },
  ],
}

describe('CallChainView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchCallChain.mockResolvedValue(detail)
    api.renameCallChain.mockResolvedValue({ ...detail, name: '重命名后的链' })
    api.deleteCallChain.mockResolvedValue(detail)
  })

  afterEach(() => cleanup())

  it('loads nodes and keeps branch or cycle edges visible while switching source', async () => {
    render(<CallChainView repositoryId="repo-1" callChainId="chain-1" fallbackName="入口调用链" />)

    expect(await screen.findByRole('button', { name: /1\. Demo\.entry/ })).toBeVisible()
    expect(screen.getAllByText('Demo.entry', { selector: 'span' })).toHaveLength(2)
    expect(screen.getAllByText('Service.run', { selector: 'span' })).toHaveLength(2)
    expect(screen.getAllByText('→')).toHaveLength(2)
    const nodeDetail = screen.getByRole('region', { name: '节点详情' })
    expect(nodeDetail.querySelector('pre')).toHaveTextContent('void entry()')

    fireEvent.click(screen.getByRole('button', { name: /2\. Service\.run/ }))
    expect(nodeDetail.querySelector('pre')).toHaveTextContent('void run()')
  })

  it('renames and requires a second explicit delete click', async () => {
    render(<CallChainView repositoryId="repo-1" callChainId="chain-1" fallbackName="入口调用链" />)
    await screen.findByRole('button', { name: /1\. Demo\.entry/ })

    fireEvent.change(screen.getByRole('textbox', { name: '名称' }), { target: { value: '重命名后的链' } })
    fireEvent.click(screen.getByRole('button', { name: '重命名' }))
    await waitFor(() => expect(api.renameCallChain).toHaveBeenCalledWith('repo-1', 'chain-1', '重命名后的链'))

    fireEvent.click(screen.getByRole('button', { name: '删除调用链' }))
    expect(screen.getByRole('button', { name: '确认删除（只删除 SalmonMind 内部调用链）' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: '确认删除（只删除 SalmonMind 内部调用链）' }))
    await waitFor(() => expect(api.deleteCallChain).toHaveBeenCalledWith('repo-1', 'chain-1'))
  })
})

function observation() {
  return {
    branch: 'main',
    head: '0123456789abcdef0123456789abcdef01234567',
    dirty: true,
    unborn: false,
    detached: false,
    shallow: false,
    stagedCount: 1,
    unstagedCount: 0,
    untrackedCount: 1,
    sensitiveChangedCount: 0,
    observedAt: '2026-08-20T00:00:00Z',
  }
}
