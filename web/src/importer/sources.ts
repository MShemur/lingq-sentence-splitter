// Reading .txt/.md/.epub/.pdf files picked on the iPad. Port of Android Sources.kt (Epub) and PdfText (text layer only:
// there is no OCR on the web, scanned pages are reported as such; page choice in pdf.ts).
import { ApiError } from '../lingq'
import { openPdf, type PdfDoc } from './pdf'

/** A piece of text that becomes one LingQ lesson (or several, if it is long). */
export interface SourceText { title: string; text: string }

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

// --- EPUB: OPF spine order, XHTML -> plain text with paragraphs; images are skipped ---

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
  const chapters: SourceText[] = []
  for (const id of spine) {
    const href = manifest.get(id)
    if (!href) continue
    let path = href.split('#')[0]
    try { path = decodeURIComponent(path) } catch { /* keep */ }
    const html = read(resolve(base, path))
    if (!html) continue
    const text = htmlToText(html)
    if (text.trim()) chapters.push({ title: chapterTitle(html), text })
  }
  if (!chapters.length) throw new ApiError('в EPUB не найден текст')
  return { title, chapters, cover: cover(opf, files, base) }
}

/** EPUB 3 "cover-image", EPUB 2 <meta name="cover">, or an image called "cover". */
function cover(opf: string, files: Record<string, Uint8Array>, base: string): Blob | undefined {
  const items = opf.match(/<item\b[^>]*>/g) ?? []
  const isImage = (t: string) => (attr(t, 'media-type') ?? '').startsWith('image')
  const meta = /<meta\b[^>]*\bname\s*=\s*["']cover["'][^>]*>/.exec(opf)?.[0]
  const metaId = meta ? attr(meta, 'content') : null
  const item = items.find((t) => (attr(t, 'properties') ?? '').includes('cover-image'))
    ?? (metaId ? items.find((t) => attr(t, 'id') === metaId && isImage(t)) : undefined)
    ?? items.find((t) => isImage(t) && /cover/i.test((attr(t, 'id') ?? '') + (attr(t, 'href') ?? '')))
  const href = item && attr(item, 'href')
  if (!href) return undefined
  let path = href
  try { path = decodeURIComponent(href) } catch { /* keep */ }
  const b = files[resolve(base, path)]
  return b ? new Blob([b as BlobPart], { type: attr(item!, 'media-type') ?? 'image/jpeg' }) : undefined
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
