// PDF book: the PDF pages are shown as they are (pdf.js), an invisible layer of the lesson's words lies over them.
// The pages are recognised once (the PDF's own text layer; a scanned page by Gemini) and kept in IndexedDB; the
// recognised text is uploaded to LingQ as an ordinary lesson. When the lesson is opened, its sentences are matched
// word by word with the recognised words, so each word box on a page knows its (sentence, token).
// Port of Android PdfBook.kt: the same data shapes and rules.
//
// IndexedDB "satzloop-pdf", store "files", keys as Android's files (filesDir/pdf):
//   lessons.json            {"<lang>_<lessonId>": {"doc": "<doc id>", "pages": [0-based page numbers]}}
//   <doc>/doc.pdf           the PDF itself (bytes; doc id = first 16 hex chars of its SHA-1)
//   <doc>/page_<n>.json     {"v":1, "page":n, "aspect": height/width,
//                            "words": [[text, x, y, w, h, line, block], ...]}   boxes as parts (0..1) of the page
import { ApiError } from './lingq'
import { prefs } from './settings'
import { ocr } from './gemini'
import { log } from './applog'
import { pdfjsLib } from './importer/pdf'
import { pagesLabel } from './importer/pdf'
import type { Sent, WordRef } from './reader/session'

/** One recognised word of a PDF page: its text and box as parts (0..1) of the page width / height. */
export interface PdfWord { t: string; x: number; y: number; w: number; h: number; line: number; block: number }
export interface PdfPage { index: number; aspect: number; words: PdfWord[] }
/** A lesson made from a PDF book: the doc and the pages the lesson covers (0-based). */
export interface PdfLink { doc: string; pages: number[] }
/** A word of the lesson on a PDF page: its sentence / token and box (0..1 of the page). Hyphenated words have two. */
export interface PdfTok { ref: WordRef; sent: number; token: number; x: number; y: number; w: number; h: number }

/** The lesson's words on the pages of its PDF. */
export class PdfMap {
  /** Sentences on each page (sorted ids). */
  readonly sents = new Map<number, number[]>()
  constructor(readonly pages: number[], readonly aspect: Map<number, number>, readonly toks: Map<number, PdfTok[]>) {
    for (const [p, l] of toks) this.sents.set(p, [...new Set(l.map((t) => t.sent))].sort((a, b) => a - b))
  }

  /** Pager index of the page with sentence [id] (or the first one after it); -1 = none. */
  pageOf(id: number): number {
    const i = this.pages.findIndex((p) => this.sents.get(p)?.includes(id))
    return i >= 0 ? i : this.pages.findIndex((p) => { const s = this.sents.get(p); return (s?.[s.length - 1] ?? -1) >= id })
  }
}

// --- storage ---

let dbp: Promise<IDBDatabase> | null = null
function db(): Promise<IDBDatabase> {
  dbp ??= new Promise((ok, fail) => {
    const r = indexedDB.open('satzloop-pdf', 1)
    r.onupgradeneeded = () => { r.result.createObjectStore('files') }
    r.onsuccess = () => ok(r.result)
    r.onerror = () => { dbp = null; fail(r.error) }
  })
  return dbp
}

async function idb<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  const d = await db()
  return new Promise((ok, fail) => {
    const req = f(d.transaction('files', mode).objectStore('files'))
    req.onsuccess = () => ok(req.result as T)
    req.onerror = () => fail(req.error)
  })
}
const getKey = <T>(k: string) => idb<T | undefined>('readonly', (s) => s.get(k))
const putKey = (k: string, v: unknown) => idb<unknown>('readwrite', (s) => s.put(v, k))
const docKey = (doc: string) => `${doc}/doc.pdf`
const pageKey = (doc: string, i: number) => `${doc}/page_${i}.json`
const LINKS = 'lessons.json'

/** Keeps the PDF in this browser; its id comes from the content, so the same file is recognised once. */
export async function importFile(bytes: Uint8Array): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest('SHA-1', bytes as BufferSource))
  const id = [...d.slice(0, 8)].map((b) => b.toString(16).padStart(2, '0')).join('')
  if (!(await getKey(docKey(id)))) await putKey(docKey(id), bytes.slice())
  // the browser may clear site data under storage pressure unless it is asked to keep it
  void navigator.storage?.persist?.().catch(() => false)
  return id
}

/** The PDF's bytes (a copy each time: pdf.js takes the buffer over). */
export async function fileBytes(doc: string): Promise<Uint8Array | null> {
  const b = await getKey<Uint8Array | ArrayBuffer>(docKey(doc))
  return b ? new Uint8Array(b instanceof ArrayBuffer ? b.slice(0) : b.slice()) : null
}

const r5 = (f: number) => Math.round(f * 100000) / 100000

async function save(doc: string, p: PdfPage) {
  await putKey(pageKey(doc, p.index), JSON.stringify({
    v: 1, page: p.index, aspect: r5(p.aspect),
    words: p.words.map((w) => [w.t, r5(w.x), r5(w.y), r5(w.w), r5(w.h), w.line, w.block]),
  }))
}

export async function load(doc: string, i: number): Promise<PdfPage | null> {
  try {
    const raw = await getKey<string>(pageKey(doc, i))
    if (!raw) return null
    const o = JSON.parse(raw)
    return {
      index: i, aspect: Number(o.aspect),
      words: (o.words as any[]).map((w) => ({ t: String(w[0]), x: +w[1], y: +w[2], w: +w[3], h: +w[4], line: w[5] | 0, block: w[6] | 0 })),
    }
  } catch { return null }
}

// --- recognition ---

/** Fewer letters than this in the text layer: a scan (as the plain PDF import). */
const SCAN_LETTERS = 80

/**
 * Recognises [pages] (0-based) of the PDF [pdf] (a pdf.js document) kept as [doc], page by page; pages recognised
 * before come from the cache. The text layer is used where there is one; a scanned page goes to Gemini (pages
 * without a Gemini key are left out and named in [scans]).
 */
export async function recognise(doc: string, pdf: any, pages: number[], onStatus: (s: string) => void): Promise<{ pages: PdfPage[]; scans: number[]; ocr: number }> {
  const pdfjs = await pdfjsLib()
  const out: PdfPage[] = []
  const scans: number[] = []
  let ocrCount = 0
  for (let n = 0; n < pages.length; n++) {
    const i = pages[n]
    onStatus(`Распознаю страницу ${i + 1} (${n + 1} из ${pages.length})…`)
    const cached = await load(doc, i)
    if (cached) { out.push(cached); continue }
    const page = await pdf.getPage(i + 1)
    try {
      let p = await fromTextLayer(pdfjs, page, i)
      const letters = p.words.reduce((k, w) => k + (w.t.match(/\p{L}/gu)?.length ?? 0), 0)
      if (letters < SCAN_LETTERS) {
        if (!prefs.geminiKey.value.trim()) { scans.push(i); out.push(p); continue }
        onStatus(`Распознаю скан страницы ${i + 1} с Gemini (${n + 1} из ${pages.length})…`)
        p = await fromGemini(page, i)
        ocrCount++
      }
      await save(doc, p)
      out.push(p)
    } finally { page.cleanup() }
  }
  log('pdf', `PDF-книга ${doc}: стр. ${pagesLabel(pages)}, Gemini OCR: ${ocrCount}, сканов без ключа: ${scans.length}`)
  return { pages: out, scans, ocr: ocrCount }
}

let measureCtx: CanvasRenderingContext2D | null = null

/** Widths of the characters of [s] in [font] (for placing the words inside a text item of the PDF). */
function charWidths(s: string, family: string): number[] {
  measureCtx ??= document.createElement('canvas').getContext('2d')
  const c = measureCtx
  if (!c) return [...s].map(() => 1)
  c.font = `100px ${family || 'sans-serif'}`
  const out: number[] = []
  for (let k = 0; k < s.length; k++) out.push(Math.max(1, c.measureText(s[k]).width))
  return out
}

/** One character of a line of the page with its horizontal extent (page units) and the item's box. */
interface Ch { c: string; x0: number; x1: number; top: number; h: number }

/** The words of a page from the PDF's own text layer: each item is spread over its characters, lines split at spaces. */
async function fromTextLayer(pdfjs: any, page: any, index: number): Promise<PdfPage> {
  const vp = page.getViewport({ scale: 1 })
  const W = vp.width, H = vp.height
  const tc = await page.getTextContent()
  const styles = tc.styles ?? {}
  const lines: Ch[][] = []
  let cur: Ch[] = []
  let last: { base: number; fh: number; right: number } | null = null
  let eol = false
  const endLine = () => { if (cur.length) lines.push(cur); cur = [] }
  for (const it of tc.items as any[]) {
    if (typeof it.str !== 'string') continue
    if (!it.str) { if (it.hasEOL) eol = true; continue }
    const tx = pdfjs.Util.transform(vp.transform, it.transform)
    // only horizontal text (rotated captions etc. are left out)
    if (Math.abs(tx[1]) > Math.abs(tx[0]) * 0.1) continue
    const fh = Math.hypot(tx[2], tx[3]) || Math.abs(it.height) || 10
    const left = tx[4], base = tx[5]
    const width = Math.abs(it.width) * vp.scale || fh * it.str.length * 0.5
    const st = styles[it.fontName] ?? {}
    const asc = st.ascent > 0.5 && st.ascent < 1.2 ? st.ascent : 0.8
    const desc = st.descent < 0 && st.descent > -0.5 ? -st.descent : 0.2
    const top = base - fh * asc, h = fh * (asc + desc)
    if (last && (eol || Math.abs(base - last.base) > last.fh * 0.5 || left < last.right - last.fh)) endLine()
    else if (last && cur.length) {
      // a gap between two items of a line without a space in either: a space
      const gap = left - last.right
      const prev = cur[cur.length - 1].c
      if (gap > fh * 0.15 && !/\s/.test(prev) && !/^\s/.test(it.str)) cur.push({ c: ' ', x0: last.right, x1: left, top, h })
    }
    eol = !!it.hasEOL
    const ws = charWidths(it.str, st.fontFamily)
    const k = width / ws.reduce((a, b) => a + b, 0)
    let x = left
    for (let n = 0; n < it.str.length; n++) {
      const cw = ws[n] * k
      cur.push({ c: it.str[n], x0: x, x1: x + cw, top, h })
      x += cw
    }
    last = { base, fh, right: left + width }
  }
  endLine()
  // words; a new block where the gap above a line is large, the size changes, or the text goes up (a new column)
  const words: PdfWord[] = []
  let block = 0
  let prev: { top: number; bottom: number; h: number } | null = null
  lines.forEach((l, li) => {
    const ws: PdfWord[] = []
    let a = -1
    const flush = (b: number) => {
      if (a < 0) return
      const cs = l.slice(a, b)
      const t = cs.map((c) => c.c).join('')
      const x0 = Math.min(...cs.map((c) => c.x0)), x1 = Math.max(...cs.map((c) => c.x1))
      const top = Math.min(...cs.map((c) => c.top)), bot = Math.max(...cs.map((c) => c.top + c.h))
      ws.push({ t, x: x0 / W, y: top / H, w: (x1 - x0) / W, h: (bot - top) / H, line: li, block: 0 })
      a = -1
    }
    l.forEach((c, k) => { if (/\s/.test(c.c)) flush(k); else if (a < 0) a = k })
    flush(l.length)
    if (!ws.length) return
    const top = Math.min(...ws.map((w) => w.y)) * H
    const bottom = Math.max(...ws.map((w) => w.y + w.h)) * H
    const h = bottom - top
    if (prev && (top - prev.bottom > Math.max(h, prev.h) * 0.8 || Math.abs(h - prev.h) > Math.max(h, prev.h) * 0.25 || top < prev.top - prev.h)) block++
    prev = { top, bottom, h }
    for (const w of ws) { w.block = block; words.push(w) }
  })
  return { index, aspect: H / W, words }
}

const OCR_PROMPT = 'This is one page of a book. Read ALL text on it, in reading order (columns one after another), exactly ' +
  'as printed: keep the hyphen at the end of a line, keep headings, page numbers and footnotes. Answer JSON only: ' +
  '{"blocks":[{"lines":[{"text":"the line as printed","box":[ymin,xmin,ymax,xmax]}]}]} — a block is a paragraph or ' +
  'heading, a line is one printed line, box is the tight box of the line in whole numbers 0..1000 of the image ' +
  'height (y) and width (x).'

/** A scanned page: rendered as a picture and read by Gemini (lines with boxes; the words share the line box). */
async function fromGemini(page: any, index: number): Promise<PdfPage> {
  const vp1 = page.getViewport({ scale: 1 })
  const vp = page.getViewport({ scale: Math.min(4, 1600 / vp1.width) })
  const c = document.createElement('canvas')
  c.width = Math.ceil(vp.width); c.height = Math.ceil(vp.height)
  const ctx = c.getContext('2d')!
  ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height)
  await page.render({ canvasContext: ctx, viewport: vp, canvas: c } as any).promise
  const b64 = c.toDataURL('image/jpeg', 0.85).split(',')[1]
  c.width = c.height = 0
  const raw = await ocr(OCR_PROMPT, 'image/jpeg', b64)
  let o: any
  try { o = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch { throw new ApiError(`Gemini не прочитал страницу ${index + 1}`) }
  const words: PdfWord[] = []
  let line = 0
  ;(o.blocks ?? []).forEach((b: any, bi: number) => {
    for (const l of b?.lines ?? []) {
      const text = String(l?.text ?? '').trim()
      const box = Array.isArray(l?.box) ? l.box.map(Number) : null
      if (!text || !box || box.length !== 4 || box.some((v: number) => !isFinite(v))) continue
      const [y0, x0, y1, x1] = box.map((v: number) => Math.max(0, Math.min(1000, v)) / 1000)
      if (x1 <= x0 || y1 <= y0) continue
      // the words share the line's width by their length (a space counts as a letter)
      const parts = text.split(/\s+/)
      const total = text.replace(/\s+/g, ' ').length
      let at = 0
      for (const p of parts) {
        words.push({ t: p, x: x0 + ((x1 - x0) * at) / total, y: y0, w: ((x1 - x0) * p.length) / total, h: y1 - y0, line, block: bi })
        at += p.length + 1
      }
      line++
    }
  })
  return { index, aspect: vp1.height / vp1.width, words }
}

// --- lesson text and word matching ---

const HY = new Set(['-', '­', '¬', '‐'])
const WORD = /[\p{L}\p{N}]+(?:[-'’][\p{L}\p{N}]+)*/gu
const isLetter = (c: string | undefined) => !!c && /\p{L}/u.test(c)
const isLower = (c: string | undefined) => !!c && /\p{Ll}/u.test(c)

interface W { page: PdfPage; i: number; w: PdfWord }
const flat = (pages: PdfPage[]): W[] => pages.flatMap((p) => p.words.map((w, i) => ({ page: p, i, w })))

/** Word [k] ends its line with a hyphen and goes on in the next word ("Ent-" / "wicklung"). */
function joinsNext(ws: W[], k: number): boolean {
  const a = ws[k], b = ws[k + 1]
  if (!b) return false
  const t = a.w.t
  if (t.length < 2 || !HY.has(t[t.length - 1]) || !isLetter(t[t.length - 2]) || !isLetter(b.w.t[0])) return false
  return !(a.page === b.page && a.w.line === b.w.line)
}

/** "Nord-" / "Süd": the hyphen stays before a capital letter, "Ent-" / "wicklung" loses it. */
const keepsHyphen = (next: string) => !isLower(next[0])

/** Lesson text of recognised pages: blocks become paragraphs unless a sentence goes on; line-end hyphens joined. */
export function text(pages: PdfPage[]): string {
  const ws = flat(pages)
  let sb = ''
  let paraWords = 0
  for (let k = 0; k < ws.length; k++) {
    const a = ws[k], t = a.w.t
    const b = ws[k + 1]
    if (joinsNext(ws, k)) {
      sb += t.slice(0, -1); if (keepsHyphen(b.w.t)) sb += '-'
      continue
    }
    sb += t; paraWords++
    if (!b) break
    if (a.page === b.page && a.w.block === b.w.block) { sb += ' '; continue }
    // a new block: a new paragraph, unless the sentence goes on (no end mark and a small letter or a long block)
    const ends = '.!?…:;"»«“”)]'.includes(t[t.length - 1])
    const goesOn = !ends && (isLower(b.w.t[0]) || paraWords >= 12)
    if (goesOn) sb += ' '
    else { sb += '\n\n'; paraWords = 0 }
  }
  return sb.trim()
}

const norm = (s: string) => s.toLowerCase().replace(/’/g, "'")

interface Piece { page: PdfPage; word: number; from: number; to: number }
interface Wu { norm: string; parts: Piece[] }

/** The words of the pages as the lesson tokenises them; a word hyphenated at a line end is one unit with two parts. */
function units(pages: PdfPage[]): Wu[] {
  const ws = flat(pages)
  const out: Wu[] = []
  let carry: Wu | null = null
  for (let k = 0; k < ws.length; k++) {
    const a = ws[k]
    const ms = [...a.w.t.matchAll(WORD)]
    const join = joinsNext(ws, k)
    ms.forEach((m, mi) => {
      let u: Wu = { norm: norm(m[0]), parts: [{ page: a.page, word: a.i, from: m.index!, to: m.index! + m[0].length }] }
      if (mi === 0 && carry) {
        u = { norm: carry.norm + (keepsHyphen(m[0]) ? '-' : '') + u.norm, parts: [...carry.parts, ...u.parts] }
        carry = null
      }
      if (join && mi === ms.length - 1) carry = u
      else out.push(u)
    })
    if (carry && !join) { out.push(carry); carry = null }
  }
  if (carry) out.push(carry)
  return out
}

const MAX_A = 200 // PDF words the lesson may lack (page headers, footnotes cleaned away)
const MAX_B = 40 // lesson words the PDF may lack
const MAX_D = 240

const similar = (x: string, y: string) => !!x && !!y && x[0] === y[0] && Math.abs(x.length - y.length) <= 2

/** For each word of [b] the index of the same word in [a], or -1. Greedy, re-synchronising after gaps on both sides. */
function align(a: string[], b: string[], start = 0): Int32Array {
  const ids = new Map<string, number>()
  const id = (s: string) => { let v = ids.get(s); if (v == null) { v = ids.size; ids.set(s, v) } return v }
  const ai = a.map(id), bi = b.map(id)
  const res = new Int32Array(b.length).fill(-1)
  const same = (x: number, y: number) => x < ai.length && y < bi.length && ai[x] === bi[y]
  const sure = (x: number, y: number) => same(x, y) && (x + 1 >= ai.length || y + 1 >= bi.length || same(x + 1, y + 1))
  // the start: where the first words of [b] stand together in [a]
  let i = start
  anchor: for (let j0 = 0; j0 < Math.min(8, bi.length); j0++) {
    for (let x = start; x < ai.length; x++) {
      if (sure(x, j0) && (j0 + 2 >= bi.length || same(x + 2, j0 + 2))) { i = Math.max(start, x - j0); break anchor }
    }
  }
  let j = 0
  while (i < ai.length && j < bi.length) {
    if (ai[i] === bi[j]) { res[j] = i; i++; j++; continue }
    let hk = -1, hl = -1
    search: for (let d = 1; d <= MAX_D; d++) {
      for (let l = 0; l <= Math.min(d, MAX_B); l++) {
        const k = d - l
        if (k > MAX_A) continue
        if (sure(i + k, j + l)) { hk = k; hl = l; break search }
      }
    }
    if (hk < 0) {
      // no way back in sight: a misread word in the same place (OCR), if it looks alike
      if (similar(a[i], b[j])) res[j] = i
      i++; j++
    } else { i += hk; j += hl }
  }
  return res
}

/** Before the upload: the pages each lesson text (in order) covers; null = not found. */
export function ranges(pages: PdfPage[], texts: string[]): (number[] | null)[] {
  const us = units(pages)
  const a = us.map((u) => u.norm)
  let cursor = 0
  return texts.map((t) => {
    const b = [...t.matchAll(WORD)].map((m) => norm(m[0]))
    const res = align(a, b, cursor)
    const hit = [...res].filter((k) => k >= 0)
    if (!hit.length) return null
    cursor = Math.max(...hit) + 1
    const ps = new Set(hit.flatMap((k) => us[k].parts.map((p) => p.page.index)))
    const lo = Math.min(...ps), hi = Math.max(...ps)
    // pages without words between them (pictures) belong to the lesson too
    return pages.map((p) => p.index).filter((p) => p >= lo && p <= hi)
  })
}

/** The lesson's words on its pages ([sents] = the lesson's sentences as the reader has them). */
export async function map(link: PdfLink, sents: Sent[]): Promise<PdfMap> {
  const pages = (await Promise.all(link.pages.map((i) => load(link.doc, i)))).filter((p): p is PdfPage => !!p)
  const us = units(pages)
  const refs: { sent: number; token: number }[] = []
  const b: string[] = []
  for (const st of sents) for (const t of st.tokens) if (t.isWord) { refs.push({ sent: st.id, token: t.index }); b.push(norm(t.text)) }
  const res = align(us.map((u) => u.norm), b)
  const toks = new Map<number, PdfTok[]>()
  let found = 0
  res.forEach((k, j) => {
    if (k < 0) return
    found++
    for (const p of us[k].parts) {
      const w = p.page.words[p.word]
      const n = Math.max(1, w.t.length)
      const r = refs[j]
      let l = toks.get(p.page.index)
      if (!l) toks.set(p.page.index, l = [])
      l.push({ ref: `${r.sent}:${r.token}`, sent: r.sent, token: r.token, x: w.x + (w.w * p.from) / n, y: w.y, w: (w.w * (p.to - p.from)) / n, h: w.h })
    }
  })
  log('pdf', `PDF-урок: слов ${b.length}, найдено на страницах ${found}`)
  return new PdfMap(pages.map((p) => p.index), new Map(pages.map((p) => [p.index, p.aspect])), toks)
}

// --- which lessons are PDF books ---

export async function register(lang: string, lesson: number, doc: string, pages: number[]) {
  const o = (await getKey<Record<string, PdfLink>>(LINKS)) ?? {}
  o[`${lang}_${lesson}`] = { doc, pages }
  await putKey(LINKS, o)
  log('pdf', `Урок ${lesson} — PDF-книга ${doc}, стр. ${pagesLabel(pages)}`)
}

/** The PDF book of a lesson, if it was made from one in this browser (and the PDF is still here). */
export async function link(lang: string, lesson: number): Promise<PdfLink | null> {
  try {
    const o = await getKey<Record<string, PdfLink>>(LINKS)
    const l = o?.[`${lang}_${lesson}`]
    if (!l || !Array.isArray(l.pages)) return null
    const has = await idb<number>('readonly', (s) => s.count(docKey(l.doc)))
    return has ? { doc: String(l.doc), pages: l.pages.map(Number) } : null
  } catch { return null }
}
