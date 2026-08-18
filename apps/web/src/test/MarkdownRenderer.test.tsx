import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MarkdownRenderer } from '../MarkdownRenderer.tsx'

afterEach(cleanup)

describe('MarkdownRenderer', () => {
  it('renders common GFM structures', () => {
    render(
      <MarkdownRenderer
        text={'| 名称 | 状态 |\n| --- | --- |\n| Trace | 完成 |\n\n- [x] 已完成\n\n~~旧文本~~\n\n> 引用'}
      />,
    )

    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('checkbox')).toBeChecked()
    expect(screen.getByText('旧文本').tagName).toBe('DEL')
    expect(screen.getByText('引用').closest('blockquote')).not.toBeNull()
  })

  it('does not activate raw HTML or dangerous protocols', () => {
    const { container } = render(
      <MarkdownRenderer text={'<script>window.bad = true</script>\n\n[危险链接](javascript:alert(1))'} />,
    )

    expect(container.querySelector('script')).toBeNull()
    expect(screen.getByText('危险链接').closest('a')).toBeNull()
  })

  it('labels and copies fenced code without adding copy controls to inline code', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText },
    })
    render(<MarkdownRenderer text={'行内 `value`\n\n```java\nSystem.out.println("ok");\n```'} />)

    expect(screen.getByText('java')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '复制代码' })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '复制代码' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '复制代码' })).toHaveTextContent('已复制'))
    expect(writeText).toHaveBeenCalledOnce()
    expect(writeText).toHaveBeenCalledWith('System.out.println("ok");')
  })

  it('renders an unfinished streaming fence without throwing', () => {
    expect(() => render(<MarkdownRenderer text={'回答开始\n\n```ts\nconst value = 1'} />)).not.toThrow()
    expect(screen.getByText('const value = 1')).toBeInTheDocument()
  })

  it('links only verified citations in ordinary text and leaves protected nodes unchanged', () => {
    const activate = vi.fn()
    const { container } = render(
      <MarkdownRenderer
        text={'普通 [L1]，未知 [W99]，行内 `[L1]`，已有 [L1](https://example.com)，转义 \\[L1]。\n\n```text\n[L1]\n```'}
        citationIds={new Set(['L1'])}
        onCitationActivate={activate}
      />,
    )

    const citationLink = screen.getByRole('link', { name: '定位来源 [L1]' })
    expect(citationLink).toHaveTextContent('[L1]')
    expect(container.querySelector('a[href="#citation-W99"]')).toBeNull()
    for (const codeNode of screen.getAllByText('[L1]', { selector: 'code' })) {
      expect(codeNode).not.toHaveAttribute('href')
    }
    expect(screen.getByRole('link', { name: 'L1' })).toHaveAttribute('href', 'https://example.com')
    expect(container.querySelectorAll('a[href="#citation-L1"]')).toHaveLength(1)
    expect(container.textContent).toContain('转义 [L1]')

    fireEvent.click(citationLink)
    expect(activate).toHaveBeenCalledWith('L1')
  })
})
