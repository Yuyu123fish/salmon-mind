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

  it('folds each tool independently and shows safe request/outcome details on demand', () => {
    const detailedTrace: RunTraceItem[] = [
      {
        kind: 'TOOL',
        toolCallId: 'call-web',
        toolName: 'search_web_bocha',
        toolStatus: 'COMPLETED',
        safeSummary: 'salmon',
        stableErrorCode: null,
        truncated: false,
        requestDetail: {
          querySummary: 'salmon',
          querySummaryTruncated: false,
          freshness: 'any',
          freshnessDefaulted: true,
          count: 5,
          countDefaulted: true,
        },
        outcomeDetail: {
          provider: 'BOCHA',
          resultStatus: 'DEGRADED',
          stableReasonCode: 'RERANK_UNAVAILABLE',
          sourceCount: 2,
          durationMillis: 18,
          degraded: true,
          resultTruncated: true,
        },
      },
      {
        kind: 'TOOL',
        toolCallId: 'call-failed',
        toolName: 'search_local_knowledge',
        toolStatus: 'FAILED',
        safeSummary: '本地检索失败',
        stableErrorCode: 'RETRIEVAL_UNAVAILABLE',
        truncated: false,
        outcomeDetail: {
          provider: 'LOCAL',
          resultStatus: 'UNAVAILABLE',
          stableReasonCode: 'INDEX_UNAVAILABLE',
          sourceCount: null,
          durationMillis: 9,
          degraded: false,
          resultTruncated: false,
        },
      },
    ]
    render(<RunTracePanel trace={detailedTrace} expanded />)

    expect(screen.queryByText('工具默认')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /工具 #1/ }))
    expect(screen.getByText('时间范围')).toBeVisible()
    expect(screen.getAllByText(/工具默认/)).toHaveLength(2)
    expect(screen.getByText('RERANK_UNAVAILABLE')).toBeVisible()
    expect(screen.getAllByText('是')).toHaveLength(2)
    expect(screen.queryByText('INDEX_UNAVAILABLE')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: /工具 #2/ }))
    expect(screen.getByText('INDEX_UNAVAILABLE')).toBeVisible()
    expect(screen.getByText(/RETRIEVAL_UNAVAILABLE/)).toBeVisible()
  })

  it('labels all codebase tools with safe Chinese summaries', () => {
    const toolNames = [
      'select_local_repository',
      'list_repository_directory',
      'glob_repository_files',
      'grep_repository',
      'read_repository_file',
      'git_repository_status',
      'git_repository_diff',
      'git_repository_log',
      'git_repository_show',
      'git_repository_blame',
    ]
    const codebaseTrace: RunTraceItem[] = toolNames.map((toolName, index) => ({
      kind: 'TOOL',
      toolCallId: `codebase-${index}`,
      toolName,
      toolStatus: 'COMPLETED',
      safeSummary: '只读代码库结果',
      stableErrorCode: null,
      truncated: false,
    }))
    render(<RunTracePanel trace={codebaseTrace} expanded />)

    expect(screen.getByText('选择本地仓库')).toBeVisible()
    expect(screen.getByText('浏览仓库目录')).toBeVisible()
    expect(screen.getAllByText('搜索仓库源码')).toHaveLength(2)
    expect(screen.getByText('读取仓库文件')).toBeVisible()
    expect(screen.getByText('查看 Git 状态')).toBeVisible()
    expect(screen.getByText('查看 Git 差异')).toBeVisible()
    expect(screen.getAllByText('查看 Git 历史')).toHaveLength(3)
  })

})
