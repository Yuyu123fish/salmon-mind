import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CodebaseApiError,
  fetchCodebase,
  listCallChains,
  registerRepository,
  setActiveRepository,
  unregisterRepository,
  updateRepository,
} from '../codebaseApi.ts'
import CodebaseView from '../CodebaseView.tsx'

vi.mock('../codebaseApi.ts', () => ({
  CodebaseApiError: class CodebaseApiError extends Error {
    code: string

    constructor(code: string, message: string) {
      super(message)
      this.code = code
    }
  },
  fetchCodebase: vi.fn(),
  listCallChains: vi.fn(),
  registerRepository: vi.fn(),
  setActiveRepository: vi.fn(),
  unregisterRepository: vi.fn(),
  updateRepository: vi.fn(),
}))

const api = {
  fetchCodebase: vi.mocked(fetchCodebase),
  listCallChains: vi.mocked(listCallChains),
  registerRepository: vi.mocked(registerRepository),
  setActiveRepository: vi.mocked(setActiveRepository),
  unregisterRepository: vi.mocked(unregisterRepository),
  updateRepository: vi.mocked(updateRepository),
}

const emptyCatalog = {
  platform: {
    operatingSystem: 'Windows 11',
    pathSeparator: '\\',
    windows: true,
    pathExample: 'D:\\project\\repo',
  },
  gitAvailable: true,
  activeRepositoryId: null,
  repositories: [],
}

describe('CodebaseView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchCodebase.mockResolvedValue(emptyCatalog)
    api.listCallChains.mockResolvedValue([])
    api.registerRepository.mockResolvedValue(repository)
    api.setActiveRepository.mockResolvedValue({ ...emptyCatalog, activeRepositoryId: repository.id, repositories: [repository] })
    api.unregisterRepository.mockResolvedValue(emptyCatalog)
    api.updateRepository.mockResolvedValue(repository)
  })

  afterEach(() => cleanup())

  it('renders as a top-level codebase page and registers an explicit path', async () => {
    render(<CodebaseView />)

    expect(await screen.findByRole('region', { name: 'Codebase' })).toBeVisible()
    const input = screen.getByRole('textbox', { name: '仓库绝对路径' })
    fireEvent.change(input, { target: { value: 'D:/Projects/demo' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))

    await waitFor(() => expect(api.registerRepository).toHaveBeenCalledWith('D:/Projects/demo'))
    expect(input).toHaveValue('')
    expect(screen.queryByText(/Search Root/i)).not.toBeInTheDocument()
  })

  it('selects an existing repository and keeps failures in the page', async () => {
    api.fetchCodebase.mockResolvedValue({ ...emptyCatalog, repositories: [repository] })
    api.setActiveRepository.mockRejectedValue(new CodebaseApiError('PATH_NOT_FOUND', '路径不存在'))
    render(<CodebaseView />)

    fireEvent.click(await screen.findByRole('button', { name: /demo.*main/ }))

    await waitFor(() => expect(api.setActiveRepository).toHaveBeenCalledWith(repository.id))
    expect(await screen.findByRole('alert')).toHaveTextContent('路径不存在')
    expect(screen.getByRole('region', { name: 'Codebase' })).toBeVisible()
  })
})

const repository = {
  id: 'repo-1',
  path: 'D:\\Projects\\demo',
  name: 'demo',
  aliases: ['演示'],
  registered: true,
  status: 'AVAILABLE',
  branch: 'main',
  head: '0123456789abcdef0123456789abcdef01234567',
  dirty: false,
  unborn: false,
  detached: false,
  shallow: false,
  stagedCount: 0,
  unstagedCount: 0,
  untrackedCount: 0,
  sensitiveChangedCount: 0,
  unavailableCode: null,
  createdAt: '2026-08-19T00:00:00Z',
  updatedAt: '2026-08-19T00:00:00Z',
}
