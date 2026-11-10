// PDF import with page choice: previews, "3-5, 8" ranges, the text layer read per page (a page with fewer than 80
// letters is a scan: the web has no OCR, such pages are skipped and named). Port of Android PdfPick.kt.
import { ApiError } from '../lingq'

/** An opened PDF: page count, previews, text of chosen pages. */
export interface PdfDoc {
  pages: number
  /** Preview of page [i] (0-based) as an image URL, [width] px wide. */
  preview(i: number, width: number): Promise<string>
  /** Text of pages [list] (0-based); [scans]: chosen pages without a text layer (skipped). */
  text(list: number[], onStatus: (s: string) => void): Promise<{ text: string; scans: number[] }>
  close(): void
}

export async function openPdf(bytes: Uint8Array): Promise<PdfDoc> {
  const pdfjs = await import('pdfjs-dist/legacy/build/pdf.mjs')
  const worker = (await import('pdfjs-dist/legacy/build/pdf.worker.mjs?url')).default
  pdfjs.GlobalWorkerOptions.workerSrc = worker
  const doc = await pdfjs.getDocument({ data: bytes, isEvalSupported: false }).promise
  // previews one at a time (pdf.js renders a page at a time anyway; the iPad stays responsive)
  let chain: Promise<unknown> = Promise.resolve()
  return {
    pages: doc.numPages,
    preview(i, width) {
      const job = chain.then(async () => {
        const page = await doc.getPage(i + 1)
        const vp1 = page.getViewport({ scale: 1 })
        const vp = page.getViewport({ scale: (width * (window.devicePixelRatio || 1)) / vp1.width })
        const c = document.createElement('canvas')
        c.width = Math.ceil(vp.width); c.height = Math.ceil(vp.height)
        await page.render({ canvasContext: c.getContext('2d')!, viewport: vp, canvas: c } as any).promise
        page.cleanup()
        return c.toDataURL('image/jpeg', 0.7)
      })
      chain = job.catch(() => {})
      return job
    },
    async text(list, onStatus) {
      const out: string[] = []
      const scans: number[] = []
      for (let n = 0; n < list.length; n++) {
        const i = list[n]
        onStatus(`Читаю страницу ${i + 1} (${n + 1} из ${list.length})…`)
        const page = await doc.getPage(i + 1)
        const t = pageText((await page.getTextContent()).items as any[])
        page.cleanup()
        if ((t.match(/\p{L}/gu) ?? []).length < 80) scans.push(i)
        else out.push(t)
      }
      if (!out.length) {
        throw new ApiError(list.length === 1
          ? 'на этой странице нет текста — это скан. Распознавание сканов на iPad недоступно (на телефоне — есть).'
          : 'на выбранных страницах нет текста — это сканы. Распознавание сканов на iPad недоступно (на телефоне — есть).')
      }
      return { text: out.join('\n\n'), scans }
    },
    close() { void doc.destroy() },
  }
}

/** Items in reading order; a new line where pdf.js says so or where the text moves down. */
function pageText(items: any[]): string {
  let out = ''
  let lastY: number | null = null
  for (const it of items) {
    if (typeof it.str !== 'string') continue
    const y = it.transform?.[5]
    if (lastY != null && y != null && Math.abs(y - lastY) > 2 && !out.endsWith('\n')) out += '\n'
    out += it.str
    if (it.hasEOL) out += '\n'
    if (y != null) lastY = y
  }
  return out.trim()
}

/** "3-5, 8" (1-based) -> page indices (0-based, sorted), null if it cannot be read. */
export function parsePages(text: string, pages: number): number[] | null {
  const out = new Set<number>()
  for (const part of text.split(/[,; ]+/).map((x) => x.trim()).filter(Boolean)) {
    const ab = part.split(/[-–—]/).map((x) => x.trim())
    if (ab.length > 2 || !/^\d+$/.test(ab[0]) || (ab.length > 1 && !/^\d+$/.test(ab[1]))) return null
    const a = Number(ab[0]), b = ab.length > 1 ? Number(ab[1]) : a
    if (a < 1 || b < a || b > pages) return null
    for (let p = a; p <= b; p++) out.add(p - 1)
  }
  return [...out].sort((x, y) => x - y)
}

/** Page indices (0-based) -> "3–5, 8". */
export function pagesLabel(list: number[]): string {
  const s = [...list].sort((a, b) => a - b)
  const parts: string[] = []
  for (let i = 0; i < s.length;) {
    let j = i
    while (j + 1 < s.length && s[j + 1] === s[j] + 1) j++
    parts.push(j > i ? `${s[i] + 1}–${s[j] + 1}` : `${s[i] + 1}`)
    i = j + 1
  }
  return parts.join(', ')
}
