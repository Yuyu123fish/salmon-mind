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
}

/** 流式与历史 Assistant 共用的安全 GFM Renderer；原始 HTML 保持 react-markdown 默认禁用。 */
export function MarkdownRenderer({ text }: MarkdownRendererProps) {
  return (
    <div className="markdown">
      <Markdown
        remarkPlugins={[remarkGfm]}
        urlTransform={defaultUrlTransform}
        components={{
          a: SafeLink,
          pre: CodeBlock,
        }}
      >
        {text}
      </Markdown>
    </div>
  )
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
