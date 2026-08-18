import {
  Children,
  isValidElement,
  useState,
  type ComponentPropsWithoutRef,
  type ReactNode,
} from 'react'
import Markdown, { defaultUrlTransform } from 'react-markdown'
import remarkGfm from 'remark-gfm'

type MarkdownRendererProps = {
  text: string
  citationIds?: ReadonlySet<string>
  onCitationActivate?: (referenceId: string) => void
}

/** 流式与历史 Assistant 共用的安全 GFM Renderer；原始 HTML 保持 react-markdown 默认禁用。 */
export function MarkdownRenderer({
  text,
  citationIds = new Set<string>(),
  onCitationActivate,
}: MarkdownRendererProps) {
  return (
    <div className="markdown">
      <Markdown
        remarkPlugins={[remarkGfm, citationRemarkPlugin(citationIds, text)]}
        urlTransform={defaultUrlTransform}
        components={{
          a: (props) => (
            <CitationAwareLink {...props} onCitationActivate={onCitationActivate} />
          ),
          pre: CodeBlock,
        }}
      >
        {text}
      </Markdown>
    </div>
  )
}

type MarkdownNode = {
  type: string
  value?: string
  url?: string
  children?: MarkdownNode[]
  position?: {
    start?: { offset?: number }
  }
}

const citationPattern = /(?<![A-Za-z0-9_])\[(L|W)([1-9][0-9]*)](?![A-Za-z0-9_])/g
const markdownEscapablePunctuation = new Set('\\`*{}[]()#+-.!_>~|')
const astBlockedTypes = new Set([
  'code',
  'inlineCode',
  'link',
  'linkReference',
  'image',
  'imageReference',
  'definition',
  'html',
])

/** 在 Markdown AST 的普通 Text 节点中拆分当前 Assistant 已核验的引用。 */
function citationRemarkPlugin(citationIds: ReadonlySet<string>, sourceText: string) {
  return () => (tree: unknown) => {
    if (tree === null || tree === undefined) return
    transformCitationNodes(tree as MarkdownNode, [], sourceText, citationIds)
  }
}

function transformCitationNodes(
  node: MarkdownNode,
  ancestors: readonly string[],
  source: string,
  citationIds: ReadonlySet<string>,
): void {
  if (!node.children || astBlockedTypes.has(node.type)) return
  const nextAncestors = [...ancestors, node.type]
  node.children = node.children.flatMap((child) => {
    if (child.type === 'text' && typeof child.value === 'string') {
      return splitCitationText(child, source, citationIds)
    }
    transformCitationNodes(child, nextAncestors, source, citationIds)
    return [child]
  })
}

function splitCitationText(
  node: MarkdownNode,
  source: string,
  citationIds: ReadonlySet<string>,
): MarkdownNode[] {
  const value = node.value ?? ''
  const startOffset = node.position?.start?.offset ?? -1
  const result: MarkdownNode[] = []
  let plainStart = 0
  citationPattern.lastIndex = 0
  let match: RegExpExecArray | null
  while ((match = citationPattern.exec(value)) !== null) {
    const referenceId = `${match[1]}${match[2]}`
    const escaped = isEscaped(source, startOffset, match.index)
    if (!citationIds.has(referenceId) || escaped) continue
    if (match.index > plainStart) {
      result.push({ type: 'text', value: value.slice(plainStart, match.index) })
    }
    result.push({
      type: 'link',
      url: `#citation-${referenceId}`,
      children: [{ type: 'text', value: match[0] }],
    })
    plainStart = match.index + match[0].length
  }
  citationPattern.lastIndex = 0
  if (result.length === 0) return [node]
  if (plainStart < value.length) {
    result.push({ type: 'text', value: value.slice(plainStart) })
  }
  return result
}

function isEscaped(source: string, nodeStartOffset: number, localOffset: number): boolean {
  if (nodeStartOffset < 0) return false

  // mdast 的 Text value 已经移除了 Markdown 转义符，不能直接用 value 的索引定位原文。
  // 这里按原文重新计算解码后的偏移，只判断引用标记的 `[` 是否由反斜杠转义。
  let decodedOffset = 0
  for (let rawOffset = nodeStartOffset; rawOffset < source.length; rawOffset++) {
    const current = source[rawOffset]
    const next = source[rawOffset + 1]
    if (current === '\\' && next !== undefined && markdownEscapablePunctuation.has(next)) {
      if (decodedOffset === localOffset) return next === '['
      decodedOffset++
      rawOffset++
      continue
    }
    if (decodedOffset === localOffset) return false
    decodedOffset++
  }
  return false
}

function CitationAwareLink({
  onCitationActivate,
  node,
  ...props
}: ComponentPropsWithoutRef<'a'> & {
  node?: unknown
  onCitationActivate?: (referenceId: string) => void
}) {
  void node
  const referenceId = citationReferenceFromHref(props.href)
  if (referenceId !== null) {
    return (
      <a
        {...props}
        className="citation-inline-link"
        href={props.href}
        onClick={(event) => {
          event.preventDefault()
          onCitationActivate?.(referenceId)
        }}
        aria-label={`定位来源 [${referenceId}]`}
      />
    )
  }
  return <SafeLink {...props} />
}

function citationReferenceFromHref(href: string | undefined): string | null {
  const match = /^#citation-([LW][1-9][0-9]*)$/.exec(href ?? '')
  return match?.[1] ?? null
}

function SafeLink({ href, children }: ComponentPropsWithoutRef<'a'>) {
  if (!href) return <span className="unsafe-link">{children}</span>
  const external = /^https?:\/\//i.test(href)
  return (
    <a
      href={href}
      target={external ? '_blank' : undefined}
      rel={external ? 'noreferrer noopener' : undefined}
    >
      {children}
    </a>
  )
}

function CodeBlock({ children }: ComponentPropsWithoutRef<'pre'>) {
  const child = Children.count(children) === 1 ? Children.only(children) : null
  if (!isValidElement<{ className?: string; children?: ReactNode }>(child)) {
    return <pre>{children}</pre>
  }
  const className = child.props.className ?? ''
  const language = /(?:^|\s)language-([^\s]+)/.exec(className)?.[1] ?? 'text'
  const code = textOf(child.props.children).replace(/\n$/, '')
  return <CopyableCodeBlock language={language} code={code} className={className} />
}

function CopyableCodeBlock({
  language,
  code,
  className,
}: {
  language: string
  code: string
  className: string
}) {
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
  }

  return (
    <div className="code-block">
      <div className="code-toolbar">
        <span>{language}</span>
        <button type="button" onClick={() => void copy()} aria-label="复制代码">
          {copyState === 'copied' ? '已复制' : copyState === 'failed' ? '复制失败' : '复制'}
        </button>
      </div>
      <pre>
        <code className={className}>{code}</code>
      </pre>
    </div>
  )
}

function textOf(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textOf).join('')
  if (isValidElement<{ children?: ReactNode }>(node)) return textOf(node.props.children)
  return ''
}
