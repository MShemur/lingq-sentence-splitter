// Small Markdown renderer for Gemini's chat answers (port of Android Markdown.kt): headings, bold, italic,
// inline code, code blocks, bullet and numbered lists, quotes, horizontal rules. Builds JSX, never HTML strings,
// so nothing in an answer can inject markup.
import type { ComponentChildren } from 'preact'

/** **bold**, __bold__, *italic*, _italic_, `code`, ~~strike~~. */
function inline(t: string, key = 0): ComponentChildren[] {
  const out: ComponentChildren[] = []
  const re = /(`[^`]+`)|(\*\*[^*]+?\*\*|__[^_]+?__)|(~~[^~]+?~~)|(\*[^*\s][^*]*?\*|\b_[^_\s][^_]*?_\b)/g
  let last = 0
  let m: RegExpExecArray | null
  while ((m = re.exec(t))) {
    if (m.index > last) out.push(t.slice(last, m.index))
    const s = m[0]
    const k = `${key}-${m.index}`
    if (m[1]) out.push(<code key={k}>{s.slice(1, -1)}</code>)
    else if (m[2]) out.push(<b key={k}>{inline(s.slice(2, -2), m.index)}</b>)
    else if (m[3]) out.push(<s key={k}>{inline(s.slice(2, -2), m.index)}</s>)
    else out.push(<i key={k}>{inline(s.slice(1, -1), m.index)}</i>)
    last = m.index + s.length
  }
  if (last < t.length) out.push(t.slice(last))
  return out
}

export function Markdown({ text }: { text: string }) {
  const lines = text.replace(/\r/g, '').split('\n')
  const blocks: ComponentChildren[] = []
  let i = 0
  let n = 0
  while (i < lines.length) {
    const line = lines[i]
    const key = n++
    // ``` code block
    if (/^\s*```/.test(line)) {
      const code: string[] = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) code.push(lines[i++])
      i++
      blocks.push(<pre key={key} class="md-code"><code>{code.join('\n')}</code></pre>)
      continue
    }
    if (!line.trim()) { i++; continue }
    const h = line.match(/^\s*(#{1,6})\s+(.*)$/)
    if (h) { blocks.push(<div key={key} class={'md-h md-h' + h[1].length}>{inline(h[2])}</div>); i++; continue }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { blocks.push(<hr key={key} class="md-hr" />); i++; continue }
    // > quote (consecutive lines)
    if (/^\s*>/.test(line)) {
      const q: string[] = []
      while (i < lines.length && /^\s*>/.test(lines[i])) q.push(lines[i++].replace(/^\s*>\s?/, ''))
      blocks.push(<blockquote key={key} class="md-quote"><Markdown text={q.join('\n')} /></blockquote>)
      continue
    }
    // lists (bullets or numbers), nesting by indentation
    const li = /^(\s*)([-*+•]|\d+[.)])\s+(.*)$/
    if (li.test(line)) {
      const items: ComponentChildren[] = []
      while (i < lines.length && li.test(lines[i])) {
        const [, ind, mark, body] = lines[i].match(li)!
        const num = /\d/.test(mark)
        items.push(
          <div key={i} class="md-li" style={{ paddingLeft: Math.min(ind.replace(/\t/g, '  ').length, 8) * 8 + 'px' }}>
            <span class="md-mark">{num ? mark : '•'}</span>
            <span>{inline(body)}</span>
          </div>,
        )
        i++
      }
      blocks.push(<div key={key} class="md-list">{items}</div>)
      continue
    }
    // paragraph: consecutive plain lines
    const para: string[] = []
    while (i < lines.length && lines[i].trim() && !/^\s*(```|#{1,6}\s|>|([-*+•]|\d+[.)])\s)/.test(lines[i])) para.push(lines[i++])
    if (!para.length) { para.push(line); i++ }
    blocks.push(<p key={key} class="md-p">{para.flatMap((l, j) => (j ? [<br key={'b' + j} />, ...inline(l)] : inline(l)))}</p>)
  }
  return <>{blocks}</>
}
