import { useId, useState } from 'react'
import type { RunTraceItem, ToolOutcomeDetail, ToolRequestDetail } from './conversationApi.ts'

type RunTracePanelProps = {
  trace: RunTraceItem[]
  running?: boolean
  expanded?: boolean
  onToggle?: () => void
  onLayoutChange?: () => void
}

/** 运行中与历史共用的 Trace 展示；只呈现后端已经裁剪的安全字段。 */
export function RunTracePanel({
  trace,
  running = false,
  expanded,
  onToggle,
  onLayoutChange,
}: RunTracePanelProps) {
  const panelId = useId().replace(/:/g, '')
  const [localExpanded, setLocalExpanded] = useState(running)
  const [expandedTools, setExpandedTools] = useState<Record<string, boolean>>({})
  if (trace.length === 0) return null
  const open = expanded ?? localExpanded
  const toggle = () => {
    if (onToggle) onToggle()
    else setLocalExpanded((current) => !current)
    requestAnimationFrame(() => onLayoutChange?.())
  }
  const toggleTool = (toolCallId: string) => {
    setExpandedTools((current) => ({
      ...current,
      [toolCallId]: !(current[toolCallId] ?? false),
    }))
    requestAnimationFrame(() => onLayoutChange?.())
  }

  let toolNumber = 0
  return (
    <section className="run-trace" data-running={running}>
      <button type="button" className="trace-toggle" aria-expanded={open} onClick={toggle}>
        <span>{running ? '思考与工具（运行中）' : '思考与工具'}</span>
        <span>{open ? '收起' : `展开 · ${trace.length}`}</span>
      </button>
      {open && (
        <ol className="trace-list">
          {trace.map((item, index) =>
            item.kind === 'REASONING' ? (
              <li className="trace-reasoning" key={`reasoning-${index}`}>
                <span className="trace-kind">思考</span>
                <p>{item.text}</p>
                {item.truncated && <small>内容已按展示上限截断</small>}
              </li>
            ) : (
              (() => {
                toolNumber += 1
                return (
                  <ToolTraceRow
                    key={item.toolCallId}
                    item={item}
                    panelId={panelId}
                    toolNumber={toolNumber}
                    expanded={expandedTools[item.toolCallId] ?? false}
                    onToggle={() => toggleTool(item.toolCallId)}
                  />
                )
              })()
            ),
          )}
        </ol>
      )}
    </section>
  )
}

function ToolTraceRow({
  item,
  panelId,
  toolNumber,
  expanded,
  onToggle,
}: {
  item: Extract<RunTraceItem, { kind: 'TOOL' }>
  panelId: string
  toolNumber: number
  expanded: boolean
  onToggle: () => void
}) {
  const detailId = `trace-tool-detail-${panelId}-${toolNumber}`
  const hasDetail = (item.requestDetail !== null && item.requestDetail !== undefined) ||
    (item.outcomeDetail !== null && item.outcomeDetail !== undefined)
  return (
    <li className="trace-tool" data-status={item.toolStatus}>
      <button
        type="button"
        className="trace-tool-toggle"
        aria-expanded={expanded}
        aria-controls={detailId}
        onClick={onToggle}
      >
        <span className="trace-kind">工具 #{toolNumber}</span>
        <span className="trace-tool-summary">
          <strong>{toolLabel(item.toolName)}</strong>
          <span className="trace-tool-query">{item.safeSummary || '暂无安全摘要'}</span>
          <small>
            {statusLabel(item.toolStatus)}
            {item.stableErrorCode ? ` · ${item.stableErrorCode}` : ''}
            {item.truncated ? ' · 展示摘要已截断' : ''}
          </small>
        </span>
        <span className="trace-tool-chevron" aria-hidden="true">{expanded ? '−' : '+'}</span>
      </button>
      {expanded && (
        <div id={detailId} className="trace-tool-detail">
          {hasDetail ? (
            <>
              {item.requestDetail ? <RequestDetail detail={item.requestDetail} /> : null}
              {item.outcomeDetail ? <OutcomeDetail detail={item.outcomeDetail} /> : null}
            </>
          ) : (
            <small>暂无更多安全详情</small>
          )}
        </div>
      )}
    </li>
  )
}

function RequestDetail({ detail }: { detail: ToolRequestDetail }) {
  return (
    <dl className="tool-detail-list">
      <div>
        <dt>查询</dt>
        <dd>
          {detail.querySummary}
          {detail.querySummaryTruncated ? '（展示已截断）' : ''}
        </dd>
      </div>
      {detail.freshness !== null && detail.freshness !== undefined ? (
        <div>
          <dt>时间范围</dt>
          <dd>{detail.freshness}{detail.freshnessDefaulted ? '（工具默认）' : ''}</dd>
        </div>
      ) : null}
      {detail.count !== null && detail.count !== undefined ? (
        <div>
          <dt>请求数量</dt>
          <dd>{detail.count}{detail.countDefaulted ? '（工具默认）' : ''}</dd>
        </div>
      ) : null}
    </dl>
  )
}

function OutcomeDetail({ detail }: { detail: ToolOutcomeDetail }) {
  return (
    <dl className="tool-detail-list tool-outcome-detail">
      {detail.provider ? (
        <div><dt>Provider</dt><dd>{detail.provider}</dd></div>
      ) : null}
      {detail.resultStatus ? (
        <div><dt>结果状态</dt><dd>{resultStatusLabel(detail.resultStatus)}</dd></div>
      ) : null}
      {detail.stableReasonCode ? (
        <div><dt>稳定原因</dt><dd>{detail.stableReasonCode}</dd></div>
      ) : null}
      {detail.sourceCount !== null && detail.sourceCount !== undefined ? (
        <div><dt>来源数</dt><dd>{detail.sourceCount}</dd></div>
      ) : null}
      <div><dt>耗时</dt><dd>{detail.durationMillis} ms</dd></div>
      <div><dt>降级</dt><dd>{detail.degraded ? '是' : '否'}</dd></div>
      <div><dt>结果裁剪</dt><dd>{detail.resultTruncated ? '是' : '否'}</dd></div>
    </dl>
  )
}

function toolLabel(toolName: string): string {
  if (toolName === 'search_web_bocha') return '博查网页搜索'
  if (toolName === 'search_web_searchapi') return 'SearchApi.io 网页搜索'
  if (toolName === 'search_local_knowledge') return '本地知识库检索'
  if (toolName === 'select_local_repository') return '选择本地仓库'
  if (toolName === 'list_repository_directory') return '浏览仓库目录'
  if (toolName === 'glob_repository_files' || toolName === 'grep_repository') return '搜索仓库源码'
  if (toolName === 'read_repository_file') return '读取仓库文件'
  if (toolName === 'git_repository_status') return '查看 Git 状态'
  if (toolName === 'git_repository_diff') return '查看 Git 差异'
  if (toolName === 'git_repository_log' || toolName === 'git_repository_show' || toolName === 'git_repository_blame') return '查看 Git 历史'
  return toolName || '工具调用'
}

function statusLabel(status: 'RUNNING' | 'COMPLETED' | 'FAILED'): string {
  if (status === 'RUNNING') return '进行中'
  if (status === 'COMPLETED') return '已完成'
  return '失败'
}

function resultStatusLabel(status: ToolOutcomeDetail['resultStatus']): string {
  if (status === 'SUCCESS') return '成功'
  if (status === 'DEGRADED') return '降级'
  if (status === 'EMPTY') return '空结果'
  if (status === 'UNAVAILABLE') return '不可用'
  return ''
}
