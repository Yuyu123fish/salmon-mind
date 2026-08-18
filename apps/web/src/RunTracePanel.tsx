import { useState } from 'react'
import type { RunTraceItem } from './conversationApi.ts'

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
  const [localExpanded, setLocalExpanded] = useState(running)
  if (trace.length === 0) return null
  const open = expanded ?? localExpanded
  const toggle = () => {
    if (onToggle) onToggle()
    else setLocalExpanded((current) => !current)
    requestAnimationFrame(() => onLayoutChange?.())
  }

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
              <li className="trace-tool" key={item.toolCallId} data-status={item.toolStatus}>
                <span className="trace-kind">工具</span>
                <div>
                  <strong>{toolLabel(item.toolName)}</strong>
                  <p>{item.safeSummary || '暂无安全摘要'}</p>
                  <small>
                    {statusLabel(item.toolStatus)}
                    {item.stableErrorCode ? ` · ${item.stableErrorCode}` : ''}
                    {item.truncated ? ' · 内容已截断' : ''}
                  </small>
                </div>
              </li>
            ),
          )}
        </ol>
      )}
    </section>
  )
}

function toolLabel(toolName: string): string {
  if (toolName === 'search_web_bocha') return '博查网页搜索'
  if (toolName === 'search_web_searchapi') return 'SearchApi.io 网页搜索'
  if (toolName === 'search_local_knowledge') return '本地知识库检索'
  return toolName || '工具调用'
}

function statusLabel(status: 'RUNNING' | 'COMPLETED' | 'FAILED'): string {
  if (status === 'RUNNING') return '进行中'
  if (status === 'COMPLETED') return '已完成'
  return '失败'
}
