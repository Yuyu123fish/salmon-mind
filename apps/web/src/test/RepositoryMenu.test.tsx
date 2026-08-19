import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  CodebaseApiError,
  addSearchRoot,
  fetchCodebase,
  registerRepository,
  removeSearchRoot,
  setActiveRepository,
  unregisterRepository,
  updateRepository,
} from '../codebaseApi.ts'
import RepositoryMenu from '../RepositoryMenu.tsx'

vi.mock('../codebaseApi.ts', () => ({
  CodebaseApiError: class CodebaseApiError extends Error {
    code: string

    constructor(code: string, message: string) {
      super(message)
      this.code = code
    }
  },
  addSearchRoot: vi.fn(),
  fetchCodebase: vi.fn(),
  registerRepository: vi.fn(),
  removeSearchRoot: vi.fn(),
  setActiveRepository: vi.fn(),
  unregisterRepository: vi.fn(),
  updateRepository: vi.fn(),
}))

const api = {
  fetchCodebase: vi.mocked(fetchCodebase),
  registerRepository: vi.mocked(registerRepository),
  addSearchRoot: vi.mocked(addSearchRoot),
  removeSearchRoot: vi.mocked(removeSearchRoot),
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
  searchRoots: [],
}

describe('RepositoryMenu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchCodebase.mockResolvedValue(emptyCatalog)
    api.registerRepository.mockResolvedValue(repository)
    api.addSearchRoot.mockResolvedValue({ id: 'root-1', path: 'D:\\projects', createdAt: '2026-08-19T00:00:00Z' })
    api.removeSearchRoot.mockResolvedValue(emptyCatalog)
    api.setActiveRepository.mockResolvedValue({ ...emptyCatalog, activeRepositoryId: repository.id, repositories: [repository] })
    api.unregisterRepository.mockResolvedValue(emptyCatalog)
    api.updateRepository.mockResolvedValue(repository)
  })

  afterEach(() => cleanup())

  it('shows the empty state and sends the server-provided path unchanged', async () => {
    render(<RepositoryMenu />)
    const trigger = await screen.findByRole('button', { name: /选择本地仓库/ })
    fireEvent.click(trigger)

    expect(screen.getByRole('dialog', { name: '本地仓库管理' })).toBeVisible()
    const input = screen.getByRole('textbox', { name: '仓库绝对路径' })
    fireEvent.change(input, { target: { value: 'D:/Projects/demo' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))

    await waitFor(() => expect(api.registerRepository).toHaveBeenCalledWith('D:/Projects/demo'))
    expect(input).toHaveValue('')
  })

  it('keeps codebase failures inside the repository panel', async () => {
    api.registerRepository.mockRejectedValue(new CodebaseApiError('PATH_NOT_FOUND', '路径不存在'))
    render(<RepositoryMenu />)
    fireEvent.click(await screen.findByRole('button', { name: /选择本地仓库/ }))
    fireEvent.change(screen.getByRole('textbox', { name: '仓库绝对路径' }), { target: { value: 'D:/missing' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('路径不存在')
    expect(screen.getByRole('dialog', { name: '本地仓库管理' })).toBeVisible()
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
