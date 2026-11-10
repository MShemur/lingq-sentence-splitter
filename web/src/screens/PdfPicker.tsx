// "Какие страницы взять?": previews of a PDF's pages to tap, or a range typed in. Port of Android PdfPagePicker.
import { useEffect, useRef, useState } from 'preact/hooks'
import type { ImportSession } from '../importer/session'
import { pagesLabel, parsePages, type PdfDoc } from '../importer/pdf'
import { Sheet } from '../ui'
import { Icon } from '../icons'

export function PdfPicker({ s, d }: { s: ImportSession; d: PdfDoc }) {
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const [range, setRange] = useState('')
  const bad = range.trim() !== '' && parsePages(range, d.pages) == null

  const pick = (next: Set<number>) => { setPicked(next); setRange(next.size ? pagesLabel([...next]) : '') }
  const toggle = (i: number) => { const n = new Set(picked); if (n.has(i)) n.delete(i); else n.add(i); pick(n) }
  const typed = (t: string) => {
    setRange(t)
    const p = parsePages(t, d.pages)
    if (p) setPicked(new Set(p))
  }
  const list = [...picked].sort((a, b) => a - b)
  return (
    <Sheet onClose={() => s.cancelPdf()}>
      <div class="sheet-title">Какие страницы взять?</div>
      <div class="pdf-pick">
        <label class="field">
          <span>Страницы, например 3-5, 8</span>
          <input value={range} inputMode="numeric" placeholder={`1–${d.pages}`} onInput={(e) => typed((e.target as HTMLInputElement).value)} />
        </label>
        {bad && <div class="error-text pdf-bad">Не понимаю диапазон: номера от 1 до {d.pages}, через запятую или дефис</div>}
        <div class="pdf-grid">
          {Array.from({ length: d.pages }, (_, i) => (
            <PageTile key={i} d={d} i={i} on={picked.has(i)} onClick={() => toggle(i)} />
          ))}
        </div>
        <div class="row-buttons pdf-actions">
          <button class="btn text" onClick={() => pick(new Set(Array.from({ length: d.pages }, (_, i) => i)))}>Все</button>
          <button class="btn text" disabled={!picked.size} onClick={() => pick(new Set())}>Сбросить</button>
          <span class="grow" />
          <button class="btn filled" disabled={!picked.size} onClick={() => void s.importPdfPages(list)}>
            {picked.size ? `Взять ${picked.size} стр.` : 'Выберите страницы'}
          </button>
        </div>
      </div>
    </Sheet>
  )
}

/** One page: its preview (rendered when it scrolls into view), number, a ✓ when picked. */
function PageTile({ d, i, on, onClick }: { d: PdfDoc; i: number; on: boolean; onClick: () => void }) {
  const box = useRef<HTMLButtonElement>(null)
  const [src, setSrc] = useState<string | null>(null)
  useEffect(() => {
    const el = box.current
    if (!el) return
    let gone = false
    const io = new IntersectionObserver((es) => {
      if (!es.some((e) => e.isIntersecting)) return
      io.disconnect()
      d.preview(i, 150).then((u) => { if (!gone) setSrc(u) }, () => {})
    }, { rootMargin: '300px' })
    io.observe(el)
    return () => { gone = true; io.disconnect() }
  }, [d, i])
  return (
    <button ref={box} class={'pdf-page' + (on ? ' on' : '')} onClick={onClick} aria-pressed={on} aria-label={`Страница ${i + 1}`}>
      <span class="pdf-thumb">{src ? <img src={src} alt="" /> : <span class="skeleton" />}</span>
      <span class="pdf-num">{i + 1}</span>
      {on && <span class="pdf-check"><Icon name="check" size={16} /></span>}
    </button>
  )
}
