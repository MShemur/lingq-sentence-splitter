// Reading .txt/.md/.epub/.pdf files picked on the iPad. Port of Android Sources.kt (Epub) and PdfText (text layer only:
// there is no OCR on the web, scanned pages are reported as such; page choice in pdf.ts).
import { ApiError } from '../lingq'
import { openPdf, type PdfDoc } from './pdf'
import { paraKey, type BookPic } from '../bookpics'

/** A piece of text that becomes one LingQ lesson (or several, if it is long). */
export interface SourceText { title: string; text: string
  /** EPUB: the chapter's pictures, tied to its paragraphs (kept in this browser, shown in the lesson). */
  pics?: BookPic[]
}

const baseName = (name: string) => name.replace(/\.[^.]+$/, '')

/** The file's display name (without extension) and its texts (one per EPUB chapter). */
export async function readFile(file: File, onStatus: (s: string) => void): Promise<{ name: string; texts: SourceText[]; pdf?: PdfDoc; cover?: Blob }> {
  const bytes = new Uint8Array(await file.arrayBuffer())
  const head = String.fromCharCode(...bytes.subarray(0, 4))
  if (/\.pdf$/i.test(file.name) || head === '%PDF') {
    onStatus('Открываю PDF…')
    const pdf = await openPdf(bytes)
    // several pages: the user picks which (the caller shows the picker); one page is read at once
    if (pdf.pages > 1) return { name: baseName(file.name), texts: [], pdf }
    try {
      const { text } = await pdf.text([0], onStatus)
      return { name: baseName(file.name), texts: [{ title: baseName(file.name), text }] }
    } finally { pdf.close() }
  }
  if (/\.epub$/i.test(file.name) || (bytes[0] === 0x50 && bytes[1] === 0x4b)) {
    onStatus('Читаю EPUB…')
    const book = await readEpub(bytes)
    return { name: book.title, texts: book.chapters, cover: book.cover }
  }
  const text = new TextDecoder('utf-8').decode(bytes).replace(/^﻿/, '').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  return { name: baseName(file.name), texts: [{ title: baseName(file.name), text }] }
}

// --- EPUB: OPF spine order, XHTML -> plain text with paragraphs; pictures kept aside (bookpics.ts) ---

async function readEpub(bytes: Uint8Array): Promise<{ title: string; chapters: SourceText[]; cover?: Blob }> {
  const { unzipSync, strFromU8 } = await import('fflate')
  let files: Record<string, Uint8Array>
  try { files = unzipSync(bytes) } catch { throw new ApiError('файл не открылся как EPUB (zip)') }
  const read = (p: string) => (files[p] ? strFromU8(files[p]) : null)
  const container = read('META-INF/container.xml')
  if (!container) throw new ApiError('это не EPUB (нет META-INF/container.xml)')
  const opfPath = /full-path\s*=\s*"([^"]+)"/.exec(container)?.[1]
  if (!opfPath) throw new ApiError('EPUB без OPF')
  const opf = read(opfPath)
  if (!opf) throw new ApiError(`EPUB: нет ${opfPath}`)
  const base = opfPath.includes('/') ? opfPath.slice(0, opfPath.lastIndexOf('/')) : ''

  const t = /<dc:title[^>]*>([\s\S]*?)<\/dc:title>/i.exec(opf)?.[1]
  const title = (t && clean(stripTags(t))) || 'Книга'

  const manifest = new Map<string, string>()
  for (const m of opf.match(/<item\b[^>]*>/g) ?? []) {
    const id = attr(m, 'id'), href = attr(m, 'href'), type = attr(m, 'media-type') ?? ''
    if (id && href && type.includes('html')) manifest.set(id, href)
  }
  const spine = (opf.match(/<itemref\b[^>]*>/g) ?? []).map((m) => attr(m, 'idref')).filter(Boolean) as string[]
  const cv = coverItem(opf)
  const coverPath = cv ? resolve(base, decoded(cv.href)) : null
  const coverType = cv?.type ?? 'image/jpeg'
  const chapters: SourceText[] = []
  for (const id of spine) {
    const href = manifest.get(id)
    if (!href) continue
    let path = href.split('#')[0]
    try { path = decodeURIComponent(path) } catch { /* keep */ }
    const full = resolve(base, path)
    const html = read(full)
    if (!html) continue
    const withPics = await chapterPics(html, files, full, coverPath)
    const text = htmlToMarkedText(withPics.html)
    if (!withPics.pics.length) { if (text.trim()) chapters.push({ title: chapterTitle(html), text }); continue }
    const placed = await anchorPics(text, withPics.pics)
    if (placed.text.trim()) chapters.push({ title: chapterTitle(html), text: placed.text, pics: placed.pics })
  }
  if (!chapters.length) throw new ApiError('в EPUB не найден текст')
  return { title, chapters, cover: coverPath ? blobOf(files[coverPath], coverType) : undefined }
}

/** EPUB 3 "cover-image", EPUB 2 <meta name="cover">, or an image called "cover": its href and type. */
function coverItem(opf: string): { href: string; type: string } | null {
  const items = opf.match(/<item\b[^>]*>/g) ?? []
  const isImage = (t: string) => (attr(t, 'media-type') ?? '').startsWith('image')
  const meta = /<meta\b[^>]*\bname\s*=\s*["']cover["'][^>]*>/.exec(opf)?.[0]
  const metaId = meta ? attr(meta, 'content') : null
  const item = items.find((t) => (attr(t, 'properties') ?? '').includes('cover-image'))
    ?? (metaId ? items.find((t) => attr(t, 'id') === metaId && isImage(t)) : undefined)
    ?? items.find((t) => isImage(t) && /cover/i.test((attr(t, 'id') ?? '') + (attr(t, 'href') ?? '')))
  const href = item && attr(item, 'href')
  return href ? { href, type: attr(item!, 'media-type') ?? 'image/jpeg' } : null
}

const decoded = (href: string) => { try { return decodeURIComponent(href) } catch { return href } }
const blobOf = (b: Uint8Array | undefined, type: string) => (b ? new Blob([b as BlobPart], { type }) : undefined)

const PIC_ON = '', PIC_OFF = ''
const PIC_MARK = /(\d+)/g
const IMG_TYPES: Record<string, string> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml' }

/**
 * The chapter's pictures (not the cover) with their captions; in the HTML each picture becomes a mark (its own
 * paragraph when it stands alone) and its caption is taken out (shown under the picture, not lesson text).
 */
async function chapterPics(html: string, files: Record<string, Uint8Array>, chapter: string, cover: string | null): Promise<{ html: string; pics: { data: Blob; cap: string }[] }> {
  if (!/<(img|image)\b/i.test(html)) return { html, pics: [] }
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const dir = chapter.includes('/') ? chapter.slice(0, chapter.lastIndexOf('/')) : ''
  const pics: { data: Blob; cap: string }[] = []
  const capClass = /caption|legend|bildunter|unterschrift|figcap/i
  for (const img of [...doc.querySelectorAll('img, image')]) {
    if (!img.isConnected) continue
    const src = img.getAttribute('src') ?? img.getAttribute('href') ?? img.getAttribute('xlink:href') ?? ''
    const path = src && !/^(data|https?):/i.test(src) ? resolve(dir, decoded(src.split('#')[0])) : ''
    const bytes = path && path !== cover ? files[path] : undefined
    // the outermost element holding only this picture (an <svg>, a <p> or <div> around it)
    let box: Element = img.closest('svg') ?? img
    while (box.parentElement && box.parentElement !== doc.body && !box.parentElement.textContent?.trim()
      && box.parentElement.querySelectorAll('img, image').length === 1) box = box.parentElement
    const fig = img.closest('figure')
    let capEl: Element | null = fig?.querySelector('figcaption') ?? null
    if (!capEl) {
      const next = (fig ?? box).nextElementSibling
      if (next && capClass.test(next.getAttribute('class') ?? '') && (next.textContent ?? '').length < 600) capEl = next
    }
    if (!bytes) continue
    const cap = capEl ? clean(capEl.textContent ?? '') : ''
    capEl?.remove()
    const ext = path.split('.').pop()!.toLowerCase()
    const mark = PIC_ON + pics.length + PIC_OFF
    pics.push({ data: new Blob([bytes as BlobPart], { type: IMG_TYPES[ext] ?? 'image/jpeg' }), cap })
    const outer = fig ?? box
    if (outer !== img || !outer.parentElement?.textContent?.trim()) {
      const p = doc.createElement('p'); p.textContent = mark; outer.replaceWith(p)
    } else outer.replaceWith(doc.createTextNode(mark))
  }
  return { html: doc.body.innerHTML, pics }
}

/** Size of a picture, or null when it does not open. */
function picSize(b: Blob): Promise<{ w: number; h: number } | null> {
  return new Promise((ok) => {
    const url = URL.createObjectURL(b)
    const im = new Image()
    const done = (r: { w: number; h: number } | null) => { URL.revokeObjectURL(url); ok(r) }
    im.onload = () => done(im.naturalWidth && im.naturalHeight ? { w: im.naturalWidth, h: im.naturalHeight } : null)
    im.onerror = () => done(null)
    im.src = url
  })
}

/** The text without the picture marks; each picture tied to the paragraph after it (or before it). Tiny ones dropped. */
async function anchorPics(text: string, raw: { data: Blob; cap: string }[]): Promise<{ text: string; pics: BookPic[] }> {
  const paras: string[] = []
  const pics: BookPic[] = []
  let waiting: number[] = []
  let prev: string | null = null
  const sizes = await Promise.all(raw.map((r) => picSize(r.data)))
  const pic = (n: number, before: string | null) => {
    const sz = sizes[n]
    if (!sz || Math.min(sz.w, sz.h) < 40 || sz.w * sz.h < 12000) return // ornaments, bullets
    pics.push({ data: raw[n].data, w: sz.w, h: sz.h, cap: raw[n].cap, before, after: prev })
  }
  for (const p of text.split(/\n\n/)) {
    const marks = [...p.matchAll(PIC_MARK)].map((m) => Number(m[1]))
    const rest = p.replace(PIC_MARK, '').replace(/[ \t]{2,}/g, ' ').trim()
    if (!rest) { waiting.push(...marks); continue }
    const key = paraKey(rest)
    for (const n of [...waiting, ...marks]) pic(n, key)
    waiting = []
    paras.push(rest); prev = key
  }
  for (const n of waiting) pic(n, null)
  return { text: paras.join('\n\n'), pics }
}

const attr = (tag: string, name: string) => new RegExp(`\\b${name}\\s*=\\s*(["'])(.*?)\\1`).exec(tag)?.[2] ?? null

function resolve(base: string, href: string): string {
  const out: string[] = []
  for (const p of (base ? `${base}/${href}` : href).split('/')) {
    if (p === '' || p === '.') continue
    if (p === '..') out.pop()
    else out.push(p)
  }
  return out.join('/')
}

function chapterTitle(html: string): string {
  const h = /<h[1-3]\b[^>]*>([\s\S]*?)<\/h[1-3]>/i.exec(html)?.[1] ?? /<title[^>]*>([\s\S]*?)<\/title>/i.exec(html)?.[1] ?? ''
  return clean(stripTags(h))
}

const stripTags = (s: string) => s.replace(/<[^>]+>/g, ' ')
const clean = (s: string) => unescape(s).replace(/[\s ]+/g, ' ').trim()

const BLOCK = 'p|div|h[1-6]|li|blockquote|tr|section|article|pre|dd|dt|figcaption'

// --- bold / italic of HTML (EPUB, pasted text) -> **…** / *…*, the way the reader shows them ---

const B_ON = '\uE000', B_OFF = '\uE001', I_ON = '\uE002', I_OFF = '\uE003'

/** Private-use marks where bold / italic starts and ends (tags b strong i em, inline font-weight / font-style). */
function markEmphasis(html: string): string {
  if (!/<(b|strong|i|em)\b|font-(weight|style)/i.test(html)) return html
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const walk = (el: Element, b: boolean, i: boolean) => {
    for (const c of [...el.children]) {
      const tag = c.tagName.toLowerCase()
      if (tag === 'script' || tag === 'style') continue
      const st = (c.getAttribute('style') ?? '').toLowerCase()
      const fw = /font-weight\s*:\s*([a-z0-9]+)/.exec(st)?.[1]
      const fs = /font-style\s*:\s*([a-z]+)/.exec(st)?.[1]
      // Google Docs wraps everything in <b style="font-weight:normal">
      const cb = fw ? fw === 'bold' || fw === 'bolder' || Number(fw) >= 600 : tag === 'b' || tag === 'strong' ? true : b
      const ci = fs ? fs === 'italic' || fs === 'oblique' : tag === 'i' || tag === 'em' ? true : i
      const open = (cb !== b ? (cb ? B_ON : B_OFF) : '') + (ci !== i ? (ci ? I_ON : I_OFF) : '')
      const shut = (ci !== i ? (ci ? I_OFF : I_ON) : '') + (cb !== b ? (cb ? B_OFF : B_ON) : '')
      if (open) { c.prepend(doc.createTextNode(open)); c.append(doc.createTextNode(shut)) }
      walk(c, cb, ci)
    }
  }
  walk(doc.body, false, false)
  return doc.body.innerHTML
}

/** The marks become ** / * around whole words; at a sentence end the markers close and open again (LingQ splits there). */
function marksToStars(t: string): string {
  if (!/[\uE000-\uE003]/.test(t)) return t
  type Run = { s: string; b: boolean; i: boolean }
  const runs: Run[] = []
  let b = 0, i = 0
  const push = (ch: string) => {
    const nl = ch === '\n'
    const rb = !nl && b > 0, ri = !nl && i > 0
    const last = runs[runs.length - 1]
    if (last && last.b === rb && last.i === ri && !nl && last.s !== '\n') last.s += ch
    else runs.push({ s: ch, b: rb, i: ri })
  }
  for (const ch of t) {
    if (ch === B_ON) b++
    else if (ch === B_OFF) b = Math.max(0, b - 1)
    else if (ch === I_ON) i++
    else if (ch === I_OFF) i = Math.max(0, i - 1)
    else push(ch)
  }
  // "<b>Das</b> <b>Haus</b>": the space between two runs of the same style joins them
  for (let k = 1; k + 1 < runs.length; k++) {
    const [x, y, z] = [runs[k - 1], runs[k], runs[k + 1]]
    if (!y.b && !y.i && /^[ \t]+$/.test(y.s) && x.b === z.b && x.i === z.i && (x.b || x.i)) {
      x.s += y.s + z.s; runs.splice(k, 2); k--
    }
  }
  let out = ''
  runs.forEach((r, k) => {
    if (!r.b && !r.i) { out += r.s; return }
    const m = /^(\s*)([^]*?)(\s*)$/.exec(r.s)!
    const core = m[2]
    const next = runs[k + 1]?.s[0] ?? ' '
    // only around letters, and never glued to a letter outside (mid-word bold would show its stars)
    if (!/[\p{L}\p{N}]/u.test(core) || (!m[1] && /[\p{L}\p{N}]/u.test(out.slice(-1))) || (!m[3] && /[\p{L}\p{N}]/u.test(next))) { out += r.s; return }
    const mk = r.b && r.i ? '***' : r.b ? '**' : '*'
    out += m[1] + mk + core.replace(/([.!?…]+["'»«“”„’)\]]*)(\s+)(?=\S)/g, `$1${mk}$2${mk}`) + mk + m[3]
  })
  return out
}

/** HTML -> text with paragraphs, bold and italic kept as ** and *. */
export function htmlToMarkedText(html: string): string {
  return marksToStars(htmlToText(markEmphasis(html)))
}

/** Pasted HTML -> text, only when it has bold / italic (else the plain clipboard text is better); null = none. */
export function pastedEmphasis(html: string): string | null {
  if (!html) return null
  const marked = markEmphasis(html)
  if (!/[\uE000-\uE003]/.test(marked)) return null
  const t = marksToStars(htmlToText(marked))
  return /\*/.test(t) ? t : null
}

export function htmlToText(html: string): string {
  let s = html
  s = s.replace(/<head\b[\s\S]*?<\/head>/gi, ' ')
  s = s.replace(/<(script|style)\b[\s\S]*?<\/\1>/gi, ' ')
  s = s.replace(/\s+/g, ' ') // source line breaks are not paragraphs
  s = s.replace(/<br\s*\/?>/gi, '\n')
  s = s.replace(new RegExp(`</?(${BLOCK})\\b[^>]*>`, 'gi'), '\n\n')
  s = s.replace(/<[^>]+>/g, '')
  s = unescape(s)
  return s.split('\n').map((l) => l.replace(/[ \t ]+/g, ' ').trim()).join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

const NAMED: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', shy: '',
  ndash: '–', mdash: '—', hellip: '…', laquo: '«', raquo: '»', bdquo: '„',
  ldquo: '“', rdquo: '”', lsquo: '‘', rsquo: '’', sbquo: '‚', middot: '·',
}

export function unescape(s: string): string {
  return s.replace(/&(#[xX][0-9a-fA-F]+|#\d+|[a-zA-Z]+);/g, (m, g: string) => {
    if (g[0] === '#') {
      const n = g[1] === 'x' || g[1] === 'X' ? parseInt(g.slice(2), 16) : parseInt(g.slice(1), 10)
      try { return Number.isFinite(n) ? String.fromCodePoint(n) : m } catch { return m }
    }
    return NAMED[g] ?? m
  })
}
