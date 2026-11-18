// The lesson shown as its PDF pages (PDF book): swipe = next page, pinch = zoom (up to 5×, no swipe while zoomed),
// the words work as in the text reader through invisible boxes over the page. Port of PdfReader in Android PdfBook.kt.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { JSX } from 'preact'
import { Press } from '../press'
import { Loading } from '../ui'
import { logError } from '../applog'
import { pdfjsLib } from '../importer/pdf'
import { fileBytes, map as mapLesson, PdfMap, type PdfLink, type PdfTok } from '../pdfbook'
import type { ReaderSession, WordRef } from './session'
import { refSent } from './session'
import { docked, glide, isBook, SentenceTranslation, stopGlide } from './Reader'

/** Zoom and pan of one page: screen = offset + layout position × scale. */
class Zoom {
  scale = 1; ox = 0; oy = 0
  /** View size and where the unzoomed page lies in it. */
  vw = 0; vh = 0; left = 0; top = 0; pw = 0; ph = 0
  on: (() => void) | null = null

  clamp() {
    if (this.scale <= 1.001) { this.scale = 1; this.ox = 0; this.oy = 0 } else {
      this.ox = this.fit(this.ox, this.vw, this.left, this.pw)
      this.oy = this.fit(this.oy, this.vh, this.top, this.ph)
    }
    this.on?.()
  }

  private fit(o: number, view: number, start: number, len: number) {
    const size = len * this.scale
    return size <= view ? (view - size) / 2 - start * this.scale
      : Math.min(-start * this.scale, Math.max(view - (start + len) * this.scale, o))
  }

  zoomBy(f: number, cx: number, cy: number) {
    const ns = Math.min(5, Math.max(1, this.scale * f))
    const k = ns / this.scale
    this.ox = cx - (cx - this.ox) * k; this.oy = cy - (cy - this.oy) * k
    this.scale = ns
    this.clamp()
  }

  panBy(dx: number, dy: number) { this.ox += dx; this.oy += dy; this.clamp() }

  /** Double tap on the page (not on a word): zoom in there, or back to the whole page. [x], [y]: in the view. */
  toggle(x: number, y: number) {
    if (this.scale > 1.01) { this.scale = 1; this.clamp(); return }
    this.zoomBy(2.5, x, y)
  }

  /** View point -> page point (CSS px of the unzoomed page). */
  toPage(x: number, y: number) { return { x: (x - this.ox) / this.scale - this.left, y: (y - this.oy) / this.scale - this.top } }
}

/** Re-renders the component when the zoom changes. */
function useZoom(z: Zoom) {
  const [, set] = useState(0)
  useEffect(() => { z.on = () => set((n) => n + 1); return () => { if (z.on) z.on = null } }, [z])
}

export function PdfReader({ s, link }: { s: ReaderSession; link: PdfLink }) {
  const sents = s.sentences.value
  const [map, setMap] = useState<PdfMap | null>(null)
  const [doc, setDoc] = useState<any | false | null>(null)
  useEffect(() => {
    let gone = false
    setMap(null)
    mapLesson(link, sents).then((m) => { if (!gone) setMap(m) }, (e) => {
      logError('pdf', 'PDF-урок', e)
      if (!gone) setMap(new PdfMap([], new Map(), new Map()))
    })
    return () => { gone = true }
  }, [link, sents])
  useEffect(() => {
    let gone = false
    let d: any = null
    ;(async () => {
      const bytes = await fileBytes(link.doc)
      if (!bytes) throw new Error('PDF нет в этом браузере')
      const pdfjs = await pdfjsLib()
      d = await pdfjs.getDocument({ data: bytes, isEvalSupported: false }).promise
      if (gone) { void d.destroy(); return }
      setDoc(d)
    })().catch((e) => { logError('pdf', 'PDF не открылся', e); if (!gone) setDoc(false) })
    return () => { gone = true; if (d) void d.destroy() }
  }, [link.doc])

  if (doc === false) {
    return (
      <div class="pdf-msg">
        <p class="error-text">PDF не открылся</p>
        <button class="btn outlined" onClick={() => { s.showPdf.value = false }}>Показать текст</button>
      </div>
    )
  }
  if (!map || !doc) return <Loading />
  if (!map.pages.length) {
    return (
      <div class="pdf-msg">
        <button class="btn outlined" onClick={() => { s.showPdf.value = false }}>Страниц нет — показать текст</button>
      </div>
    )
  }
  return <PdfPager s={s} map={map} doc={doc} />
}

/** Max pixels of one page picture (iPad Safari refuses canvases above ~16 Mpx and runs out of memory early). */
const MAX_PX = 10_000_000

function PdfPager({ s, map, doc }: { s: ReaderSession; map: PdfMap; doc: any }) {
  const pages = map.pages
  const pager = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })
  const zooms = useMemo(() => new Map<number, Zoom>(), [map])
  const zoomOf = (i: number) => { let z = zooms.get(i); if (!z) zooms.set(i, z = new Zoom()); return z }
  const cur = Math.max(0, Math.min(pages.length - 1, s.textPage.value))

  useEffect(() => {
    const el = pager.current
    if (!el) return
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }))
    ro.observe(el)
    // iOS: a pinch over the page must not zoom the whole web page
    const g = (e: Event) => e.preventDefault()
    document.addEventListener('gesturestart', g)
    return () => { ro.disconnect(); document.removeEventListener('gesturestart', g) }
  }, [])

  // the start: the page of the sentence the reader got to (a bookmark's sentence wins)
  useLayoutEffect(() => {
    const anchor = s.jump.value ?? s.textAnchor
    s.jump.value = null
    const i = anchor == null ? 0 : map.pageOf(anchor)
    s.textPage.value = Math.max(0, i)
  }, [map])

  // the current page lies in place unless the user is moving the pages
  useLayoutEffect(() => {
    const el = pager.current
    if (!el || !el.clientWidth || gesture.current?.mode === 'swipe') return
    if (Math.abs(el.scrollLeft - cur * el.clientWidth) > 1) { stopGlide(el); el.scrollLeft = cur * el.clientWidth }
  }, [cur, size.w])

  // progress and what is on screen
  useLayoutEffect(() => {
    const ss = map.sents.get(pages[cur]) ?? []
    s.visibleSents = new Set(ss)
    s.textProgress.value = (cur + 1) / pages.length
    s.pageLabel.value = `${cur + 1} / ${pages.length}`
    s.pageCount.value = pages.length
    if (ss.length) { s.textAnchor = ss[0]; s.savePos(ss[0], ss[ss.length - 1]) }
  }, [cur, map])

  const turnTo = (i: number, smooth = true) => {
    const el = pager.current
    i = Math.max(0, Math.min(pages.length - 1, i))
    if (el && el.clientWidth) { if (smooth) glide(el, i * el.clientWidth, 420); else el.scrollLeft = i * el.clientWidth }
    s.textPage.value = i
  }

  // bookmark "go to source"; sentence mode collapsed: the page of its sentence
  const jump = s.jump.value
  useEffect(() => {
    if (jump == null) return
    const i = map.pageOf(jump)
    if (i >= 0) turnTo(i, false)
    s.jump.value = null
  }, [jump])
  const syncTo = s.syncTo.value
  useEffect(() => {
    if (syncTo == null) return
    const i = map.pageOf(syncTo)
    if (i >= 0 && i !== cur) turnTo(i)
    s.syncTo.value = null
  }, [syncTo])

  // arrow keys turn pages (iPad keyboard)
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const el = pager.current
      if (!el || el.closest('.layer.below') || s.sentenceMode.value || (s.popup.value && !docked()) || s.menuOpen.value || s.bookmarksOpen.value) return
      if ((e.target as Element)?.closest?.('input, textarea')) return
      const i = s.textPage.value
      if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') turnTo(i + 1)
      else if (e.key === 'ArrowLeft' || e.key === 'PageUp') turnTo(i - 1)
      else return
      e.preventDefault()
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [map])

  // --- gestures: swipe, pinch, pan, and the words (tap / double tap / long press + drag) ---

  /** Where a view point lies: the word nearest to it (within [near] page px), in sentence [sent] if given. */
  const hit = (clientX: number, clientY: number, sent: number | null, near: number): WordRef | null => {
    const el = pager.current
    const i = s.textPage.value
    const toks = map.toks.get(pages[i]) ?? []
    if (!el) return null
    const z = zoomOf(i)
    const r = el.getBoundingClientRect()
    const p = z.toPage(clientX - r.left, clientY - r.top)
    let best: WordRef | null = null
    let bd = Infinity
    for (const t of toks) {
      if (sent != null && t.sent !== sent) continue
      const dx = Math.max(t.x * z.pw - p.x, 0, p.x - (t.x + t.w) * z.pw)
      const dy = Math.max(t.y * z.ph - p.y, 0, p.y - (t.y + t.h) * z.ph)
      const d = dx + dy * 3
      if (d < bd) { bd = d; best = t.ref }
    }
    return bd <= near ? best : null
  }

  const last = useRef({ x: 0, y: 0 })
  const longWord = useRef<WordRef | null>(null)
  const press = useMemo(() => new Press<string>({
    tap: (k) => {
      if (k) { s.tap(k); if (docked()) { s.closePhrase(); void s.openPopup(k) } return }
      // book mode: a tap in the middle of the page (not on a word) shows / hides the top bar
      const x = last.current.x / window.innerWidth, y = last.current.y / window.innerHeight
      if (isBook() && x >= 0.2 && x <= 0.8 && y >= 0.15 && y <= 0.85) s.bookBar.value = !s.bookBar.value
    },
    double: (k) => {
      if (k) { void s.openPopup(k); return }
      const el = pager.current
      if (!el) return
      const r = el.getBoundingClientRect()
      zoomOf(s.textPage.value).toggle(last.current.x - r.left, last.current.y - r.top)
    },
    long: (k) => { longWord.current = k || null; if (k) s.selectStart(k, true) },
    longMove: (e) => {
      const w = longWord.current
      if (!w) return
      const r = hit(e.clientX, e.clientY, refSent(w), Infinity)
      if (r) s.selectTo(r)
    },
    longEnd: () => { if (longWord.current) { longWord.current = null; s.selectEnd() } },
  }), [s, map])

  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const gesture = useRef<{ mode: '' | 'swipe' | 'pan' | 'pinch' | 'done'; x: number; y: number; t: number; left: number; lastX: number; lastY: number; dist: number; cx: number; cy: number } | null>(null)

  const pinchState = () => {
    const [a, b] = [...pointers.current.values()]
    return { dist: Math.hypot(a.x - b.x, a.y - b.y) || 1, cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2 }
  }

  const onPointerDown = (e: PointerEvent) => {
    if (e.pointerType === 'mouse' && e.button !== 0) return
    const el = pager.current
    if (!el) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (pointers.current.size === 1) {
      stopGlide(el)
      last.current = { x: e.clientX, y: e.clientY }
      gesture.current = { mode: '', x: e.clientX, y: e.clientY, t: performance.now(), left: el.scrollLeft, lastX: e.clientX, lastY: e.clientY, dist: 1, cx: 0, cy: 0 }
      const z = zoomOf(s.textPage.value)
      press.down(e, hit(e.clientX, e.clientY, null, 6 / z.scale) ?? '')
    } else if (pointers.current.size === 2) {
      // a second finger: pinch (the word gesture and a started swipe are dropped)
      press.cancel()
      const g = gesture.current
      if (g?.mode === 'swipe') turnTo(Math.round(g.left / (el.clientWidth || 1)))
      const p = pinchState()
      gesture.current = { ...(g ?? { x: 0, y: 0, t: 0, left: el.scrollLeft, lastX: 0, lastY: 0 }), mode: 'pinch', dist: p.dist, cx: p.cx, cy: p.cy }
    }
  }

  const onPointerMove = (e: PointerEvent) => {
    const pt = pointers.current.get(e.pointerId)
    const g = gesture.current
    const el = pager.current
    if (!pt || !g || !el) return
    pt.x = e.clientX; pt.y = e.clientY
    const z = zoomOf(s.textPage.value)
    if (g.mode === 'pinch') {
      if (pointers.current.size < 2) return
      const p = pinchState()
      const r = el.getBoundingClientRect()
      z.zoomBy(p.dist / g.dist, p.cx - r.left, p.cy - r.top)
      z.panBy(p.cx - g.cx, p.cy - g.cy)
      g.dist = p.dist; g.cx = p.cx; g.cy = p.cy
      return
    }
    if (g.mode === 'done' || press.holding) return
    const dx = e.clientX - g.x, dy = e.clientY - g.y
    if (!g.mode) {
      if (Math.hypot(dx, dy) <= 10) return
      g.mode = z.scale > 1.001 ? 'pan' : Math.abs(dx) > Math.abs(dy) ? 'swipe' : 'done'
    }
    if (g.mode === 'pan') z.panBy(e.clientX - g.lastX, e.clientY - g.lastY)
    else if (g.mode === 'swipe') el.scrollLeft = g.left - dx
    g.lastX = e.clientX; g.lastY = e.clientY
  }

  const onPointerUp = (e: PointerEvent) => {
    pointers.current.delete(e.pointerId)
    const g = gesture.current
    const el = pager.current
    if (!g || !el) return
    if (g.mode === 'pinch') {
      // the other finger stays down: it does nothing until lifted
      if (pointers.current.size) { g.mode = 'done'; return }
    }
    if (pointers.current.size) return
    gesture.current = null
    if (g.mode !== 'swipe' || !el.clientWidth) return
    const dx = g.lastX - g.x
    const v = dx / Math.max(1, performance.now() - g.t)
    const base = Math.round(g.left / el.clientWidth)
    let i = Math.round(el.scrollLeft / el.clientWidth)
    if (dx < -40 || v < -0.35) i = base + 1
    else if (dx > 40 || v > 0.35) i = base - 1
    turnTo(i)
  }

  const onPointerCancel = (e: PointerEvent) => {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size) return
    const g = gesture.current
    gesture.current = null
    if (g?.mode === 'swipe') turnTo(Math.round(g.left / (pager.current?.clientWidth || 1)))
  }

  /** Mouse / trackpad: ctrl + wheel (pinch on a trackpad) zooms, the wheel pans a zoomed page. */
  const onWheel = (e: WheelEvent) => {
    const el = pager.current
    if (!el) return
    const z = zoomOf(s.textPage.value)
    const r = el.getBoundingClientRect()
    if (e.ctrlKey) { e.preventDefault(); z.zoomBy(Math.exp(-e.deltaY / 200), e.clientX - r.left, e.clientY - r.top) }
    else if (z.scale > 1.001) { e.preventDefault(); z.panBy(-e.deltaX, -e.deltaY) }
  }

  // sentence translations (long press on a word, or "Переводы" below) of the sentences on this page
  const onPage = map.sents.get(pages[cur]) ?? []
  const shown = s.allSentences.value ? onPage : onPage.filter((id) => s.shown.value[id])
  return (
    <>
      <div class="pager pdf-pager" ref={pager} onPointerDown={onPointerDown} onPointerMove={onPointerMove}
        onPointerUp={onPointerUp} onPointerCancel={onPointerCancel} onWheel={onWheel} onContextMenu={(e) => e.preventDefault()}>
        {pages.map((p, i) => (
          <div class="page pdf-page-slot" key={p}>
            {Math.abs(i - cur) <= 1 && size.w > 0 && (
              <PdfPageView s={s} doc={doc} page={p} aspect={map.aspect.get(p) ?? 1.414} toks={map.toks.get(p) ?? []}
                z={zoomOf(i)} current={i === cur} vw={size.w} vh={size.h} />
            )}
          </div>
        ))}
      </div>
      {shown.length > 0 && (
        <div class="pdf-tr-card" lang={s.lang}>
          {shown.map((id) => <SentenceTranslation key={id} s={s} id={id} />)}
        </div>
      )}
    </>
  )
}

function PdfPageView({ s, doc, page, aspect, toks, z, current, vw, vh }: {
  s: ReaderSession; doc: any; page: number; aspect: number; toks: PdfTok[]; z: Zoom; current: boolean; vw: number; vh: number
}) {
  useZoom(z)
  const pw = Math.min(vw, vh / aspect)
  const ph = pw * aspect
  z.vw = vw; z.vh = vh; z.pw = pw; z.ph = ph
  z.left = (vw - pw) / 2; z.top = (vh - ph) / 2
  // zoomed in: the page is drawn again sharper (only the page on screen)
  const q = !current ? 1 : z.scale >= 2.5 ? 3 : z.scale >= 1.5 ? 2 : 1
  const stage = {
    transform: `translate(${z.ox}px, ${z.oy}px) scale(${z.scale})`,
  } as JSX.CSSProperties
  return (
    <div class="pdf-view">
      <div class="pdf-stage" style={stage}>
        <div class="pdf-sheet" style={{ left: z.left + 'px', top: z.top + 'px', width: pw + 'px', height: ph + 'px' }}>
          <PageCanvas doc={doc} page={page} pw={pw} ph={ph} q={q} />
          <PdfWordLayer s={s} toks={toks} pw={pw} ph={ph} scale={z.scale} />
        </div>
      </div>
    </div>
  )
}

/** The page drawn by pdf.js; the old picture stays until the new (sharper) one is ready. */
function PageCanvas({ doc, page, pw, ph, q }: { doc: any; page: number; pw: number; ph: number; q: number }) {
  const box = useRef<HTMLDivElement>(null)
  useEffect(() => {
    let task: any = null
    let gone = false
    const c = document.createElement('canvas')
    ;(async () => {
      const pg = await doc.getPage(page + 1)
      if (gone) return
      const dpr = window.devicePixelRatio || 1
      let k = q * dpr
      while (k > 1 && pw * k * ph * k > MAX_PX) k -= 0.5
      const vp1 = pg.getViewport({ scale: 1 })
      const vp = pg.getViewport({ scale: (pw * k) / vp1.width })
      c.width = Math.ceil(vp.width); c.height = Math.ceil(vp.height)
      const ctx = c.getContext('2d')!
      ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, c.width, c.height)
      task = pg.render({ canvasContext: ctx, viewport: vp, canvas: c } as any)
      await task.promise
      if (gone || !box.current) return
      for (const old of [...box.current.querySelectorAll('canvas')]) { (old as HTMLCanvasElement).width = 0; old.remove() }
      box.current.appendChild(c)
    })().catch(() => {})
    return () => { gone = true; task?.cancel?.(); if (!c.isConnected) c.width = 0 }
  }, [doc, page, Math.round(pw), q])
  useEffect(() => () => { for (const c of [...(box.current?.querySelectorAll('canvas') ?? [])]) (c as HTMLCanvasElement).width = 0 }, [])
  return <div class="pdf-canvas" ref={box} />
}

/** Highlights and translations above the words of a page (the boxes themselves are invisible: taps are measured). */
function PdfWordLayer({ s, toks, pw, ph, scale }: { s: ReaderSession; toks: PdfTok[]; pw: number; ph: number; scale: number }) {
  const pins = s.pins.value
  const prov = s.provisional.value
  const popup = s.popup.value
  const peek = s.peek.value
  void s.sel.value
  const byRef = useMemo(() => { const m = new Map<string, PdfTok>(); for (const t of toks) if (!m.has(t.ref)) m.set(t.ref, t); return m }, [toks])
  // phrases translated together: highlight over all their words, one translation above the first line
  const phr: { k: string; ts: PdfTok[]; tr: string }[] = []
  for (const [k, v] of Object.entries(s.phrasePins.value)) {
    const [sent, from, to] = k.split(':').map(Number)
    const ts = toks.filter((t) => t.sent === sent && t.token >= from && t.token <= to)
    if (ts.length) phr.push({ k, ts, tr: v })
  }
  const inPhrase = new Set(phr.flatMap((p) => p.ts))
  const pairOn = new Set<string>()
  for (const [w, ps] of Object.entries(s.pairs.value)) if (popup === w || pins[w] !== undefined) ps.forEach((p) => pairOn.add(p))

  const out: JSX.Element[] = []
  toks.forEach((t, n) => {
    const sel = s.inSelection(t.sent, t.token)
    const on = inPhrase.has(t) || pins[t.ref] !== undefined || popup === t.ref || pairOn.has(t.ref)
    if (!sel && !on) return
    const m = t.h * ph * 0.12
    out.push(<div key={'h' + n} class={'pdf-hl' + (sel ? ' picked' : '')} style={{
      left: t.x * pw - m + 'px', top: t.y * ph - m + 'px', width: t.w * pw + 2 * m + 'px', height: t.h * ph + 2 * m + 'px', borderRadius: 2 * m + 'px',
    }} />)
  })
  const label = (key: string, text: string, cx: number, top: number, lineH: number, cls: string) => {
    const font = Math.max(lineH * 0.62, 10 / scale)
    const pad = font * 0.25
    const est = Math.min(pw * 0.7, text.length * font * 0.55 + 2 * pad)
    const x = Math.max(0, Math.min(pw - est, cx - est / 2))
    const lh = font * 1.25
    const y = Math.max(lh, top - lineH * 0.08)
    out.push(<div key={'l' + key} class={'pdf-label' + cls + (peek[key] ? ' peek' : '')}
      style={{ left: x + 'px', top: y + 'px', fontSize: font + 'px', lineHeight: lh + 'px', maxWidth: pw * 0.7 + 'px', padding: `0 ${pad}px`, borderRadius: lh * 0.3 + 'px' }}>{text}</div>)
  }
  for (const [r, tr] of Object.entries(pins)) {
    const t = byRef.get(r)
    if (!t || inPhrase.has(t)) continue
    label(r, tr || '…', (t.x + t.w / 2) * pw, t.y * ph, t.h * ph, prov[r] ? ' prov' : '')
  }
  for (const { k, ts, tr } of phr) {
    const first = ts.reduce((a, b) => (b.sent < a.sent || (b.sent === a.sent && b.token < a.token) ? b : a))
    const line = ts.filter((t) => Math.abs(t.y - first.y) < first.h * 0.5)
    const l = Math.min(...line.map((t) => t.x)), r = Math.max(...line.map((t) => t.x + t.w))
    label(k, tr || '…', ((l + r) / 2) * pw, first.y * ph, first.h * ph, '')
  }
  return <div class="pdf-words">{out}</div>
}
