import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { RunTracePanel } from '../RunTracePanel.tsx'
import type { RunTraceItem } from '../conversationApi.ts'

afterEach(cleanup)

const trace: RunTraceItem[] = [
  { kind: 'REASONING', text: '先判断问题', truncated: false },
  {
    kind: 'TOOL',
    toolCallId: 'call-1',
    toolName: 'search_local_knowledge',
    toolStatus: 'COMPLETED',
    safeSummary: '命中 2 条资料',
    stableErrorCode: null,
    truncated: false,
  },
]

describe('RunTracePanel', () => {
  it('starts open while running and preserves a manual fold across new deltas', () => {
    const view = render(<RunTracePanel trace={trace} running />)
    expect(screen.getByText('先判断问题')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: /思考与工具/ }))
    expect(screen.queryByText('先判断问题')).toBeNull()

    view.rerender(
      <RunTracePanel
        running
        trace={[...trace, { kind: 'REASONING', text: '继续分析', truncated: false }]}
      />,
    )
    expect(screen.queryByText('继续分析')).toBeNull()
  })

  it('starts folded for persisted history', () => {
    render(<RunTracePanel trace={trace} />)
    expect(screen.queryByText('先判断问题')).toBeNull()
  })
})
