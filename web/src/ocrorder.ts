// Scanned PDF pages (PDF book): Tesseract's words put into reading order. Tesseract gives the exact word boxes;
// Gemini looks at the page picture and the numbered words and answers the reading order (blocks of word numbers,
// each a heading / body / caption / other) and corrections of misread words. No browser APIs here (unit-testable).

/** Kind of a block: body text, heading, caption of a picture, other (page numbers, boxes, noise). */
export type Kind = 'b' | 'h' | 'c' | 'o'

/** A word as Tesseract read it: box as parts (0..1) of the page, its line / paragraph in Tesseract's order. */
export interface TWord { t: string; x: number; y: number; w: number; h: number; line: number; para: number; conf: number }

/** A word in reading order, as stored for the page (pdfbook.ts PdfWord, with the kind). */
export interface OWord { t: string; x: number; y: number; w: number; h: number; line: number; block: number; kind?: Kind }

/** Gemini's answer, checked: blocks of word indexes in reading order, and corrected words by index. */
export interface Order { blocks: { kind: Kind; ids: number[] }[]; fix: Map<number, string> }

export const ORDER_PROMPT =
  'The picture is one scanned page of a magazine or book. OCR (Tesseract) found the words listed below: each row is ' +
  'one printed line as OCR read it, every word has its number in front ("12 Wort").\n\n' +
  'Task 1, reading order. Group ALL the numbered words into blocks the way a reader reads this page and list the ' +
  'blocks in reading order: the title and the article first (columns one after another; a paragraph that goes on ' +
  'in the next column stays one block), then the captions and the rest. Each block has a kind:\n' +
  '- "heading": title, subtitle, section heading;\n' +
  '- "body": the running text of the article, also the intro / lead paragraph;\n' +
  '- "caption": text that belongs to a photo or illustration, photo credits;\n' +
  '- "other": page numbers, running headers / footers, ads, labels in pictures and infographics, OCR noise from pictures.\n' +
  'In "ids" give the word numbers of the block in reading order; write a run of consecutive numbers as a pair ' +
  '[first, last], a single word as a number. Every number appears exactly once.\n\n' +
  'Task 2, corrections. Where OCR misread a word (compare with the picture), give the word as printed: ' +
  '"fix": {"17": "Straße"}. A word stays one word (no spaces), punctuation stays attached as printed, a hyphen at ' +
  'the end of a line stays. Leave correct words out.\n\n' +
  'Answer JSON only: {"blocks":[{"kind":"heading","ids":[[0,4]]},{"kind":"body","ids":[[5,120],[131,240]]},' +
  '{"kind":"caption","ids":[[121,130]]}],"fix":{"17":"Straße"}}\n\nWords:\n{words}'

/** Tesseract's words of a picture [W]×[H] px: boxes as parts of the picture, line / paragraph numbers in its order. */
export function tessWords(data: any, W: number, H: number): TWord[] {
  const out: TWord[] = []
  let line = 0, para = 0
  for (const b of data?.blocks ?? []) {
    for (const p of b?.paragraphs ?? []) {
      for (const l of p?.lines ?? []) {
        for (const w of l?.words ?? []) {
          const t = String(w?.text ?? '').trim()
          const bb = w?.bbox
          if (!t || !bb || !(bb.x1 > bb.x0) || !(bb.y1 > bb.y0)) continue
          out.push({ t, x: bb.x0 / W, y: bb.y0 / H, w: (bb.x1 - bb.x0) / W, h: (bb.y1 - bb.y0) / H, line, para, conf: Number(w.confidence) || 0 })
        }
        line++
      }
      para++
    }
  }
  return splitColumns(out)
}

/**
 * Tesseract sometimes reads two columns as one line ("…wächst die | Heute leben hier…"). Inside a paragraph, a gap
 * wider than the line's height that stands at the same place in several lines is a column gutter: the lines are cut
 * there and the columns come one after another (each its own paragraph). A line without a gap there (a heading over
 * both columns) stays whole, in the column where it starts.
 */
export function splitColumns(ws: TWord[]): TWord[] {
  const out: TWord[] = []
  let line = 0, para = 0
  for (let a = 0; a < ws.length;) {
    let z = a
    while (z < ws.length && ws[z].para === ws[a].para) z++
    // lines of this paragraph, words left to right; the gaps of each line
    const lines: TWord[][] = []
    for (let k = a; k < z; k++) {
      if (k === a || ws[k].line !== ws[k - 1].line) lines.push([])
      lines[lines.length - 1].push(ws[k])
    }
    const gaps: number[][] = lines.map((l) => {
      l.sort((p, q) => p.x - q.x)
      const h = Math.max(...l.map((w) => w.h))
      const g: number[] = []
      for (let k = 1; k < l.length; k++) {
        const from = l[k - 1].x + l[k - 1].w
        if (l[k].x - from > h * 1.1) g.push((from + l[k].x) / 2)
      }
      return g
    })
    // gutters: gap centres close together (3 % of the page width) in at least 2 lines and a quarter of the lines
    const all = gaps.flat().sort((p, q) => p - q)
    const gutters: number[] = []
    for (let k = 0; k < all.length;) {
      let e = k
      while (e + 1 < all.length && all[e + 1] - all[k] < 0.03) e++
      const n = e - k + 1
      if (n >= 2 && n >= lines.length * 0.25) gutters.push(all.slice(k, e + 1).reduce((p, q) => p + q, 0) / n)
      k = e + 1
    }
    // segments: a line cut at the gutters where it has a gap; a segment's column = gutters left of its start
    const cols: TWord[][][] = gutters.map(() => []).concat([[]])
    lines.forEach((l, li) => {
      const cuts = gutters.filter((g) => gaps[li].some((x) => Math.abs(x - g) < 0.03))
      let seg: TWord[] = []
      const flush = () => { if (seg.length) cols[gutters.filter((g) => g < seg[0].x).length].push(seg); seg = [] }
      l.forEach((w, k) => {
        if (k > 0 && cuts.some((g) => g > l[k - 1].x + l[k - 1].w / 2 && g < w.x + w.w / 2)) flush()
        seg.push(w)
      })
      flush()
    })
    for (const c of cols) {
      if (!c.length) continue
      for (const seg of c) { for (const w of seg) out.push({ ...w, line, para }); line++ }
      para++
    }
    a = z
  }
  return out
}

/** The words for the prompt: one row per Tesseract line, "12 Wort 13 Wort". */
export function wordList(ws: TWord[]): string {
  const rows: string[] = []
  let row: string[] = []
  ws.forEach((w, i) => {
    if (i > 0 && w.line !== ws[i - 1].line) { rows.push(row.join(' ')); row = [] }
    row.push(`${i} ${w.t}`)
  })
  if (row.length) rows.push(row.join(' '))
  return rows.join('\n')
}

export const orderPrompt = (ws: TWord[]) => ORDER_PROMPT.replace('{words}', wordList(ws))

const KINDS: Record<string, Kind> = {
  body: 'b', text: 'b', paragraph: 'b', lead: 'b', intro: 'b',
  heading: 'h', title: 'h', subtitle: 'h', headline: 'h',
  caption: 'c',
  other: 'o',
}

/**
 * Gemini's answer for [n] words, checked; null when it is not usable (no JSON, too many bad numbers, too few words
 * placed). Repeated numbers count once (the first place); words left out go to a last "other" block.
 */
export function parseOrder(raw: string, words: TWord[]): Order | null {
  const n = words.length
  let o: any
  try { o = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch { return null }
  if (!o || !Array.isArray(o.blocks)) return null
  const seen = new Uint8Array(n)
  let bad = 0, placed = 0
  const blocks: Order['blocks'] = []
  const isId = (v: number) => Number.isInteger(v) && v >= 0 && v < n
  for (const b of o.blocks) {
    if (!b || !Array.isArray(b.ids)) { bad++; continue }
    const kind = KINDS[String(b.kind ?? '').toLowerCase().trim()] ?? 'o'
    const ids: number[] = []
    const add = (v: number) => {
      if (!isId(v)) { bad++; return }
      if (seen[v]) { bad++; return }
      seen[v] = 1; ids.push(v); placed++
    }
    for (const e of b.ids) {
      let a: number, z: number
      if (Array.isArray(e) && e.length === 2) { a = Number(e[0]); z = Number(e[1]) }
      else if (typeof e === 'string' && /^\s*\d+\s*[-–]\s*\d+\s*$/.test(e)) { [a, z] = e.split(/[-–]/).map(Number) }
      else if (typeof e === 'number' || (typeof e === 'string' && /^\s*\d+\s*$/.test(e))) { add(Number(e)); continue }
      else { bad++; continue }
      if (!isId(a) || !isId(z) || z < a) { bad++; continue }
      for (let v = a; v <= z; v++) add(v)
    }
    if (ids.length) blocks.push({ kind, ids })
  }
  if (!n || placed < n * 0.85 || bad > Math.max(3, n * 0.05)) return null
  // words Gemini left out: not lost (they keep their boxes), but not in the text unless all kinds are taken
  const rest: number[] = []
  for (let v = 0; v < n; v++) if (!seen[v]) rest.push(v)
  if (rest.length) blocks.push({ kind: 'o', ids: rest })
  const fix = new Map<number, string>()
  if (o.fix && typeof o.fix === 'object' && !Array.isArray(o.fix)) {
    for (const [k, v] of Object.entries(o.fix)) {
      const id = Number(k)
      if (!/^\d+$/.test(k.trim()) || !isId(id) || typeof v !== 'string') continue
      const t = v.trim()
      const was = words[id].t
      if (!t || /\s/.test(t) || t.length > was.length * 2 + 4 || t === was) continue
      fix.set(id, t)
    }
  }
  return { blocks, fix }
}

/** The text ends a sentence here (". ! ? …" or a closing quote mark). */
export const endsSentence = (t: string) => /[.!?…»«"“”„]$/.test(t)
const isAside = (k?: Kind) => k === 'c' || k === 'o'

/**
 * Captions and other blocks ([kind] c / o) between body blocks: one standing where the body text before it has not
 * ended its sentence (Gemini put it between two halves of a sentence or paragraph) moves after the next body block
 * that does end one, or before the next heading, or to the end of the page. Headings and body keep their order.
 * [last]: the last word of each block. Returns the new order of block indexes (idempotent).
 */
export function asideOrder(kinds: (Kind | undefined)[], last: string[]): number[] {
  const out: number[] = []
  let pending: number[] = []
  let open = false // the body text so far has not ended its sentence
  kinds.forEach((k, i) => {
    if (isAside(k)) { if (open) pending.push(i); else out.push(i); return }
    if (k === 'h') { out.push(...pending, i); pending = []; open = false; return }
    out.push(i)
    open = !endsSentence(last[i] ?? '')
    if (!open) { out.push(...pending); pending = [] }
  })
  out.push(...pending)
  return out
}

/** Word indexes of a page's words (blocks as runs of the same [block]) with captions moved as in asideOrder. */
export function asideWordOrder(ws: { t: string; block: number; kind?: Kind }[]): number[] {
  const runs: number[][] = []
  ws.forEach((w, i) => { if (!i || w.block !== ws[i - 1].block) runs.push([]); runs[runs.length - 1].push(i) })
  const order = asideOrder(runs.map((r) => ws[r[0]].kind), runs.map((r) => ws[r[r.length - 1]].t))
  return order.flatMap((b) => runs[b])
}

/** Words in Gemini's [order] (with corrections and kinds); without one, in Tesseract's order (paragraphs = blocks). */
export function arrange(ws: TWord[], given: Order | null): OWord[] {
  const out: OWord[] = []
  let line = -1
  if (!given) {
    ws.forEach((w, i) => {
      if (i === 0 || w.line !== ws[i - 1].line) line++
      out.push({ t: w.t, x: w.x, y: w.y, w: w.w, h: w.h, line, block: w.para })
    })
    // blocks renumbered 0.. in order
    const ids = new Map<number, number>()
    for (const w of out) { let b = ids.get(w.block); if (b == null) ids.set(w.block, b = ids.size); w.block = b }
    return out
  }
  // captions never between two halves of a sentence: the stored order is the lesson text's order
  const lastOf = (b: Order['blocks'][number]) => { const id = b.ids[b.ids.length - 1]; return given.fix.get(id) ?? ws[id].t }
  const order = { ...given, blocks: asideOrder(given.blocks.map((b) => b.kind), given.blocks.map(lastOf)).map((i) => given.blocks[i]) }
  order.blocks.forEach((b, bi) => {
    let prev = -1
    for (const id of b.ids) {
      const w = ws[id]
      // a new line where Tesseract's line changes (or the block does)
      if (prev < 0 || ws[prev].line !== w.line) line++
      out.push({ t: order.fix.get(id) ?? w.t, x: w.x, y: w.y, w: w.w, h: w.h, line, block: bi, kind: b.kind })
      prev = id
    }
  })
  return out
}

/** Tesseract-only pages: a word with low confidence and no two letters / digits in a row is noise from a picture. */
export const noise = (w: TWord) => w.conf < 45 && !/[\p{L}\p{N}]{2}/u.test(w.t)
