// Reader screen: text with tappable words, sentence mode, word window. Port of the UI part of Reader.kt.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { Fragment, render, type JSX } from 'preact'
import { signal } from '@preact/signals'
import { Icon } from '../icons'
import { Press } from '../press'
import { back, go, stack } from '../app'
import { readerStyle, useKeepAwake } from '../readerLook'
import { useReadingTimer } from '../stats'
import { prefs } from '../settings'
import { Chip, ErrorBox, IconButton, LinearProgress, Loading, Menu, MenuItem, ProgressPill, Sheet, Spinner, TopBar } from '../ui'
import { LessonBookmarksDialog } from '../screens/Bookmarks'
import { ref as wordRef, refSent, type ReaderSession, type Sent, type WordRef } from './session'

/** A long-press selection is being dragged: the page must not scroll under the finger (iOS). */
let selecting = false
/** A finger (or mouse) is dragging pages: they must not be snapped from elsewhere meanwhile. */
let dragging = false
document.addEventListener('touchmove', (e) => { if (selecting) e.preventDefault() }, { passive: false })

/** Phone-sized screen: no docked panel (and no page-turn buttons, see styles). */
const narrowQuery = window.matchMedia('(max-width: 600px)')
const narrow = signal(narrowQuery.matches)
narrowQuery.addEventListener?.('change', (e) => { narrow.value = e.matches })

/** The word window is docked at the right (not on a phone: the text would get too narrow). */
const docked = () => prefs.dockPanel.value === '1' && !narrow.value

/**
 * Page turning by swipe, done here instead of the browser's own scrolling: on the iPad the browser would take
 * the touch over as soon as the finger moves, which breaks long press + drag over words (selection).
 * Horizontal drag moves the pages and snaps to one; vertical drag scrolls a page that does not fit.
 */
function useSwipe(pager: { current: HTMLDivElement | null }) {
  const st = useRef<{
    id: number; x: number; y: number; t: number; left: number; top: number; lastX: number
    scroller: HTMLElement | null; axis: '' | 'x' | 'y'
  } | null>(null)
  const end = () => {
    const g = st.current
    st.current = null
    dragging = false
    const el = pager.current
    if (!g || g.axis !== 'x' || !el || !el.clientWidth) return
    const dx = g.lastX - g.x
    const v = dx / Math.max(1, performance.now() - g.t)
    const base = Math.round(g.left / el.clientWidth)
    let i = Math.round(el.scrollLeft / el.clientWidth)
    if (dx < -40 || v < -0.35) i = base + 1
    else if (dx > 40 || v > 0.35) i = base - 1
    i = Math.max(0, Math.min(el.children.length - 1, i))
    glide(el, i * el.clientWidth, 420)
  }
  return {
    onPointerDown: (e: PointerEvent) => {
      if (e.pointerType === 'mouse' && e.button !== 0) return
      if ((e.target as Element).closest?.('button, input, textarea, a')) return
      const el = pager.current
      if (!el) return
      stopGlide(el)
      const scroller = (e.target as Element).closest?.('.page-text, .page') as HTMLElement | null
      dragging = true
      st.current = {
        id: e.pointerId, x: e.clientX, y: e.clientY, t: performance.now(), left: el.scrollLeft,
        top: scroller?.scrollTop ?? 0, lastX: e.clientX, scroller, axis: '',
      }
    },
    onPointerMove: (e: PointerEvent) => {
      const g = st.current
      const el = pager.current
      if (!g || g.id !== e.pointerId || !el) return
      if (selecting) { st.current = null; return }
      const dx = e.clientX - g.x, dy = e.clientY - g.y
      g.lastX = e.clientX
      if (!g.axis) {
        if (Math.abs(dx) > 10 && Math.abs(dx) > Math.abs(dy)) g.axis = 'x'
        else if (Math.abs(dy) > 10) g.axis = 'y'
        else return
      }
      if (g.axis === 'x') el.scrollLeft = g.left - dx
      else if (g.scroller) g.scroller.scrollTop = g.top - dy
    },
    onPointerUp: end,
    onPointerCancel: end,
    onPointerLeave: (e: PointerEvent) => { if (e.pointerType === 'mouse') end() },
  }
}

/** Running page-turn animations, by pager. */
const glides = new WeakMap<HTMLElement, number>()

/** Stops a page-turn animation (a finger took the page). */
function stopGlide(el: HTMLElement) {
  const id = glides.get(el)
  if (id != null) cancelAnimationFrame(id)
  glides.delete(el)
}

/**
 * Turns the pager to [left] slowly and evenly (the browser's own smooth scrolling is too fast for a book page).
 * [ms] is for a whole page; a shorter way (the rest of a swipe) takes proportionally less, but not too little.
 */
function glide(el: HTMLElement, left: number, ms = 520) {
  stopGlide(el)
  const from = el.scrollLeft
  const dist = left - from
  if (Math.abs(dist) < 1) { el.scrollLeft = left; return }
  const w = el.clientWidth || 1
  const dur = Math.max(260, ms * Math.min(1, Math.abs(dist) / w))
  const t0 = performance.now()
  const ease = (x: number) => (x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2) // ease-in-out cubic
  const step = (now: number) => {
    const x = Math.min(1, (now - t0) / dur)
    el.scrollLeft = from + dist * ease(x)
    if (x < 1) glides.set(el, requestAnimationFrame(step))
    else glides.delete(el)
  }
  glides.set(el, requestAnimationFrame(step))
}

/** Round page-turn buttons at the sides of the text. */
function TurnButtons({ at, count, turn }: { at: number; count: number; turn: (d: number) => void }) {
  return (
    <>
      {at > 0 && (
        <button class="turn-btn left" aria-label="Предыдущая страница" onClick={() => turn(-1)}>
          <Icon name="chevronLeft" size={28} />
        </button>
      )}
      {at < count - 1 && (
        <button class="turn-btn right" aria-label="Следующая страница" onClick={() => turn(1)}>
          <Icon name="chevron" size={28} />
        </button>
      )}
    </>
  )
}

/**
 * Word gestures (event delegation): tap = translation above the word, double tap = word window,
 * long press = sentence translation, long press and drag over other words of the sentence = those words together.
 */
function useWordPress(s: ReaderSession) {
  const press = useMemo(() => new Press<WordRef>({
    // docked word panel: a tap also shows the word there
    tap: (r) => { s.tap(r); if (docked()) { s.closePhrase(); void s.openPopup(r) } },
    double: (r) => { void s.openPopup(r) },
    long: (r) => { selecting = true; s.selectStart(r) },
    longMove: (e) => {
      const el = document.elementFromPoint(e.clientX, e.clientY)?.closest?.('[data-w]') as HTMLElement | null
      if (el) s.selectTo(el.dataset.w!)
    },
    longEnd: () => { selecting = false; s.selectEnd() },
  }), [s])
  const target = (e: Event) => (e.target as Element | null)?.closest?.('[data-w]') as HTMLElement | null
  return {
    onPointerDown: (e: PointerEvent) => { const el = target(e); if (el) press.down(e, el.dataset.w!) },
    onPointerMove: (e: PointerEvent) => press.move(e),
    onPointerUp: () => press.up(),
    onPointerCancel: () => press.cancel(),
    onContextMenu: (e: Event) => e.preventDefault(),
  }
}

export function ReaderScreen({ s }: { s: ReaderSession }) {
  useEffect(() => { if (!s.paragraphs.value && !s.error.value) void s.load() }, [s])
  const paras = s.paragraphs.value
  const err = s.error.value
  // in front (not under a chat or settings): reading time counts, the screen may be kept on
  const top = stack.value.at(-1)
  const active = top?.k === 'reader' && top.s === s
  useReadingTimer(active && !!paras)
  useKeepAwake(active)
  return (
    <div class="screen reader" style={readerStyle()}>
      <TopBar
        center
        nav={<IconButton icon="close" label="Закрыть урок" onClick={back} />}
        title={paras ? <div class="reader-progress"><ProgressPill p={s.textProgress.value} /><span>{s.pageLabel.value}</span></div> : null}
        actions={<ReaderActions s={s} />}
      />
      <div class={'screen-body reader-body' + (docked() ? ' docked' : '')}>
        <div class="text-area">
          {err ? <ErrorBox text={err} retry={() => void s.load()} /> : !paras ? <Loading /> : <TextView s={s} paras={paras} />}
        </div>
        {docked() && <DockedPanel s={s} />}
      </div>
      <nav class="bottom-nav">
        <button onClick={() => s.openSentenceMode()}><span class="nav-ind"><Icon name="list" /></span><span>По предложениям</span></button>
        <button class={s.allSentences.value ? 'active' : ''} onClick={() => { s.allSentences.value = !s.allSentences.value }}>
          <span class="nav-ind"><Icon name="translate" /></span><span>Переводы</span>
        </button>
        <button onClick={() => s.chatText()}><span class="nav-ind"><Icon name="chat" /></span><span>Чат</span></button>
      </nav>
      <SentenceOverlay s={s} />
      {!docked() && s.popup.value && <WordSheet s={s} r={s.popup.value} />}
      {!docked() && s.phrase.value && <PhraseSheet s={s} />}
      {s.bookmarksOpen.value && <LessonBookmarksDialog s={s} onClose={() => { s.bookmarksOpen.value = false }} />}
    </div>
  )
}

function ReaderActions({ s }: { s: ReaderSession }) {
  const menu = s.menuOpen.value
  const close = () => { s.menuOpen.value = false }
  const pinCount = Object.keys(s.pins.value).length
  return (
    <>
      <IconButton icon="bookmark" label="Закладки урока" onClick={() => { s.bookmarksOpen.value = true }} />
      <div class="menu-anchor">
        <IconButton icon="more" label="Ещё" onClick={() => { s.menuOpen.value = true }} />
        {menu && (
          <Menu onClose={close}>
            <MenuItem icon="chat" onClick={() => { close(); s.chatText() }}>Чат о тексте</MenuItem>
            <MenuItem icon="palette" onClick={() => { close(); go({ k: 'settingsReading' }) }}>Оформление и экран</MenuItem>
            <hr />
            <MenuItem onClick={() => s.changeFont(+2)}>Шрифт крупнее&nbsp; A+</MenuItem>
            <MenuItem onClick={() => s.changeFont(-2)}>Шрифт мельче&nbsp; A−</MenuItem>
            {pinCount > 0 && <hr />}
            {pinCount > 0 && <MenuItem onClick={() => { s.clearPins(); close() }}>Снять все переводы над словами ({pinCount})</MenuItem>}
          </Menu>
        )}
      </div>
    </>
  )
}

/** Part of a paragraph that lies on one page. */
interface Block { para: number; sents: Sent[] }

/** Height of a block of sentences and the bottom of every prefix of it (k+1 sentences), in px. */
interface Measure { height: number; bottoms: number[] }

const PARA_GAP = 14

/** Renders blocks (bare text) into the hidden measuring box and reads their layout. */
function measureBlocks(box: HTMLElement, s: ReaderSession, blocks: Sent[][]): Measure[] {
  render(<>{blocks.map((b, i) => <Paragraph key={i} s={s} sents={b} plain />)}</>, box)
  const out: Measure[] = []
  box.querySelectorAll<HTMLElement>(':scope > .para').forEach((el, i) => {
    const top = el.getBoundingClientRect().top
    const bottomOf = new Map<number, number>()
    el.querySelectorAll<HTMLElement>('[data-se]').forEach((x) => {
      const id = Number(x.dataset.se)
      bottomOf.set(id, Math.max(bottomOf.get(id) ?? 0, x.getBoundingClientRect().bottom - top))
    })
    let acc = 0
    const bottoms = blocks[i].map((sent) => (acc = Math.max(acc, bottomOf.get(sent.id) ?? acc)))
    out.push({ height: el.getBoundingClientRect().height, bottoms })
  })
  render(null, box)
  return out
}

/** Measured blocks, by width, font and sentences; survives page rebuilds. */
type MeasureCache = Map<string, Measure>

/**
 * Measures blocks, using the cache where nothing changed. New ones are rendered in small batches, so a long
 * lesson is never put into the page all at once (Android ran out of memory doing that, PORTING note 87bc83d).
 */
function measureCached(box: HTMLElement, s: ReaderSession, cache: MeasureCache, prefix: string, blocks: Sent[][]): Measure[] {
  const keys = blocks.map((b) => prefix + b.map((x) => x.id).join('/'))
  const missing = blocks.map((_, i) => i).filter((i) => !cache.has(keys[i]))
  for (let a = 0; a < missing.length; a += 25) {
    const part = missing.slice(a, a + 25)
    measureBlocks(box, s, part.map((i) => blocks[i])).forEach((m, j) => cache.set(keys[part[j]], m))
  }
  if (cache.size > 4000) { const keep = new Set(keys); for (const k of cache.keys()) if (!keep.has(k)) cache.delete(k) }
  return keys.map((k) => cache.get(k)!)
}

/**
 * The text split into pages that fit the screen, turned with a swipe like a book (port of Android PagedText).
 * Pages are laid out from the bare text only: translations above words and under sentences do not move text to
 * other pages, a page that grows with them scrolls up and down instead. A paragraph that does not fit is split at
 * a sentence boundary.
 */
function paginate(box: HTMLElement, s: ReaderSession, paras: Sent[][], h: number, cache: MeasureCache, prefix: string): Block[][] {
  const whole = measureCached(box, s, cache, prefix, paras)
  const pages: Block[][] = []
  let cur: Block[] = []
  let used = 0
  const need = (x: number) => (cur.length ? PARA_GAP : 0) + x
  const flush = () => { if (cur.length) pages.push(cur); cur = []; used = 0 }
  paras.forEach((para, pi) => {
    let from = 0
    let m = whole[pi]
    while (from < para.length) {
      const rest = para.slice(from)
      if (used + need(m.height) <= h) { used += need(m.height); cur.push({ para: pi, sents: rest }); break }
      // the longest run of sentences that still fits on this page
      let k = 0
      while (k + 1 < rest.length && used + need(m.bottoms[k]) <= h) k++
      if (k > 0) { cur.push({ para: pi, sents: rest.slice(0, k) }); from += k; flush() }
      else if (cur.length) { flush(); continue }
      else { cur.push({ para: pi, sents: rest.slice(0, 1) }); from += 1; flush() } // taller than a page
      if (from < para.length) m = measureCached(box, s, cache, prefix, [para.slice(from)])[0]
    }
  })
  flush()
  return pages.length ? pages : [[]]
}

function TextView({ s, paras }: { s: ReaderSession; paras: Sent[][] }) {
  const pager = useRef<HTMLDivElement>(null)
  const measurer = useRef<HTMLDivElement>(null)
  const handlers = useWordPress(s)
  const [pages, setPages] = useState<Block[][]>([])
  const [size, setSize] = useState({ w: 0, h: 0 })
  const cur = s.textPage.value
  const scrolling = useRef(false)
  const scrollEnd = useRef(0)
  const turnTo = useRef<number | null>(null)
  const pagesRef = useRef<Block[][]>([])
  pagesRef.current = pages
  const cache = useRef<MeasureCache>(new Map())

  // what changes the layout of the bare text (translations do not: the page scrolls instead)
  const deps = [paras, prefs.readerFont.value, size.w, size.h]

  useEffect(() => {
    const el = pager.current
    if (!el) return
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth, h: el.clientHeight }))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const pageOf = (list: Block[][], id: number) => list.findIndex((p) => p.some((b) => b.sents.some((x) => x.id === id)))
  const scrollTo = (i: number, smooth: boolean) => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    if (smooth) glide(el, i * el.clientWidth)
    else { stopGlide(el); el.scrollLeft = i * el.clientWidth }
  }

  useLayoutEffect(() => {
    const box = measurer.current
    if (!box || !size.h) return
    const cs = getComputedStyle(box)
    const h = size.h - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom)
    const next = paginate(box, s, paras, h, cache.current, `${size.w}|${prefs.readerFont.value}|`)
    // the same text stays on screen when pages are rebuilt: the first sentence of the page being read
    const anchor = pagesRef.current[s.textPage.value]?.[0]?.sents[0]?.id ?? s.textAnchor
    s.textAnchor = anchor
    const i = anchor == null ? 0 : Math.max(0, pageOf(next, anchor))
    setPages(next)
    s.textPage.value = i
  }, deps)

  // show the current page unless the user is the one scrolling there (a swipe or a smooth page turn)
  useLayoutEffect(() => {
    if (!pages.length) return
    const el = pager.current
    if (el && el.clientWidth && !scrolling.current && Math.abs(el.scrollLeft - cur * el.clientWidth) > 1) scrollTo(cur, false)
  }, [pages, cur])

  // publish progress and what is on screen
  useLayoutEffect(() => {
    if (!pages.length) return
    const p = pages[cur] ?? []
    s.visibleSents = new Set(p.flatMap((b) => b.sents.map((x) => x.id)))
    s.textProgress.value = (cur + 1) / pages.length
    s.pageLabel.value = `${cur + 1} / ${pages.length}`
    const ids = p.flatMap((b) => b.sents.map((x) => x.id))
    if (ids.length) s.savePos(ids[0], ids[ids.length - 1])
  }, [pages, cur])

  // bookmark "go to source"
  const jump = s.jump.value
  useEffect(() => {
    if (jump == null || !pages.length) return
    const i = pageOf(pages, jump)
    if (i >= 0) { s.textAnchor = jump; s.textPage.value = i }
    s.jump.value = null
  }, [jump, pages])

  // sentence mode collapsed: show the page of the sentence it ended on
  const syncTo = s.syncTo.value
  useEffect(() => {
    if (syncTo == null || !pages.length) return
    const i = pageOf(pages, syncTo)
    if (i >= 0 && i !== s.textPage.value) { s.textAnchor = syncTo; scrollTo(i, true) }
    s.syncTo.value = null
  }, [syncTo, pages])

  /**
   * Scrolling has stopped: the current page must lie exactly in place. The width may have changed meanwhile
   * (docked panel, rotation) while a re-snap was held back because the pager was scrolling.
   */
  const settle = () => {
    const el = pager.current
    if (!el || !el.clientWidth || dragging || glides.has(el)) return
    const i = s.textPage.value
    if (Math.abs(el.scrollLeft - i * el.clientWidth) > 1) scrollTo(i, false)
  }

  /** Turns [d] pages, counting from the page being turned to, so quick presses do not skip pages. */
  const turn = (d: number) => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    const at = Math.round(el.scrollLeft / el.clientWidth)
    const from = turnTo.current != null && scrolling.current ? turnTo.current : at
    const i = Math.max(0, Math.min(pages.length - 1, from + d))
    turnTo.current = i
    // the animation starts on the next frame: a second quick press must already count from [i]
    scrolling.current = true
    clearTimeout(scrollEnd.current)
    scrollEnd.current = window.setTimeout(() => { scrolling.current = false; turnTo.current = null; settle() }, 150)
    scrollTo(i, true)
  }
  const swipe = useSwipe(pager)

  // arrow keys turn pages (iPad keyboard)
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const el = pager.current
      if (!el || el.closest('.layer.below') || s.sentenceMode.value || (s.popup.value && !docked()) || s.menuOpen.value || s.bookmarksOpen.value) return
      if ((e.target as Element)?.closest?.('input, textarea')) return
      if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') turn(1)
      else if (e.key === 'ArrowLeft' || e.key === 'PageUp') turn(-1)
      else return
      e.preventDefault()
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [pages])

  const onScroll = () => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    scrolling.current = true
    clearTimeout(scrollEnd.current)
    scrollEnd.current = window.setTimeout(() => { scrolling.current = false; turnTo.current = null; settle() }, 150)
    const i = Math.round(el.scrollLeft / el.clientWidth)
    if (i !== s.textPage.value && i >= 0 && i < pages.length) {
      s.textPage.value = i
      s.textAnchor = pages[i][0]?.sents[0]?.id ?? null
    }
  }

  const fs = { '--fs': prefs.readerFont.value + 'px' } as JSX.CSSProperties
  return (
    <>
      <div class="reader-text measurer" ref={measurer} style={fs} aria-hidden="true" />
      <div class="pager text-pager" ref={pager} onScroll={onScroll} {...swipe}>
        {pages.map((p, i) => (
          <div class="page" key={i}>
            {Math.abs(i - cur) <= 1 && (
              <div class="reader-text page-text" style={fs} {...handlers}>
                {p.map((b) => <Paragraph key={b.para + ':' + b.sents[0].id} s={s} sents={b.sents} />)}
              </div>
            )}
          </div>
        ))}
      </div>
      <TurnButtons at={cur} count={pages.length} turn={turn} />
    </>
  )
}

/**
 * A paragraph flows as one text; a shown sentence translation is inserted right after its sentence.
 * [plain]: the bare text without translations and marks (how pages are laid out).
 */
function Paragraph({ s, sents, plain }: { s: ReaderSession; sents: Sent[]; plain?: boolean }) {
  if (plain) return <div class="para"><SentenceFlow s={s} sents={sents} plain /></div>
  const parts: JSX.Element[] = []
  let run: Sent[] = []
  for (const sent of sents) {
    run.push(sent)
    if (s.sentenceShown(sent.id)) {
      parts.push(<SentenceFlow key={'f' + sent.id} s={s} sents={run} />)
      parts.push(<SentenceTranslation key={'t' + sent.id} s={s} id={sent.id} />)
      run = []
    }
  }
  if (run.length) parts.push(<SentenceFlow key="rest" s={s} sents={run} />)
  return <div class="para">{parts}</div>
}

function SentenceFlow({ s, sents, plain }: { s: ReaderSession; sents: Sent[]; plain?: boolean }) {
  const pins = plain ? {} : s.pins.value
  const prov = s.provisional.value
  const popup = plain ? null : s.popup.value
  return (
    <div class="flow">
      {sents.map((sent) => (
        <Fragment key={sent.id}>
          {!plain && s.isBookmarked(sent.id) && <span class="flow-mark" data-se={sent.id}><Icon name="bookmark" /></span>}
          {sent.chunks.map((chunk) => (
            <span class="chunk" data-se={sent.id}>
              {chunk.map((t) => {
                if (!t.isWord) return <span class="punct">{t.text}</span>
                const r = wordRef(sent.id, t.index)
                const tr = pins[r]
                const selected = tr !== undefined || popup === r
                const picked = !plain && s.inSelection(sent.id, t.index)
                return (
                  <span class={'w' + (selected ? ' sel' : '') + (picked ? ' picked' : '')} data-w={r}>
                    {tr !== undefined && <span class={'pin' + (prov[r] ? ' prov' : '')}>{tr || '…'}</span>}
                    <span class="wt">{t.text}</span>
                  </span>
                )
              })}
            </span>
          ))}
        </Fragment>
      ))}
    </div>
  )
}

function SentenceTranslation({ s, id }: { s: ReaderSession; id: number }) {
  useEffect(() => { s.ensureSentence(id) }, [id])
  const tr = s.sentTr.value[id]
  return (
    <div class="sent-tr" data-se={id}>
      <div class="sent-tr-text" onClick={() => { if (!s.allSentences.value) s.toggleSentence(id) }}>{tr || '…'}</div>
      <BookmarkButton s={s} id={id} />
      <RetranslateButton s={s} id={id} />
      <IconButton icon="chat" label="Чат о предложении" size={20} class="small primary" onClick={() => s.chatSentence(id)} />
    </div>
  )
}

function BookmarkButton({ s, id }: { s: ReaderSession; id: number }) {
  const on = s.isBookmarked(id)
  return (
    <IconButton
      icon={on ? 'bookmark' : 'bookmarkBorder'} label={on ? 'Убрать из закладок' : 'В закладки'}
      size={20} class="small primary" onClick={() => s.toggleBookmark(id)}
    />
  )
}

/** Translate the sentence again (Gemini, or LingQ's ChatGPT). */
function RetranslateButton({ s, id }: { s: ReaderSession; id: number }) {
  if (s.retranslating.value[id]) return <span class="icon-btn small"><Spinner size={18} /></span>
  return <IconButton icon="refresh" label="Перевести заново" size={20} class="small primary" onClick={() => void s.retranslate(id)} />
}

/** Sentence mode lies over the text: slides up from the bottom, the arrow collapses it back. */
function SentenceOverlay({ s }: { s: ReaderSession }) {
  const open = s.sentenceMode.value && s.sentences.value.length > 0
  // keep the content during the slide-out animation
  const [mounted, setMounted] = useState(open)
  useEffect(() => {
    if (open) setMounted(true)
    else { const t = setTimeout(() => setMounted(false), 500); return () => clearTimeout(t) }
  }, [open])
  return (
    <div class={'sentence-overlay' + (open ? ' open' : '')} aria-hidden={!open}>
      {(open || mounted) && <SentencePager s={s} open={open} />}
    </div>
  )
}

/** Sentence mode: one sentence with its translation per page, swipe for the next one. */
function SentencePager({ s, open }: { s: ReaderSession; open: boolean }) {
  const list = s.sentences.value
  const page = Math.min(s.page.value, list.length - 1)
  const pager = useRef<HTMLDivElement>(null)
  const handlers = useWordPress(s)
  const swipe = useSwipe(pager)
  const turn = (d: number) => {
    const i = Math.min(list.length - 1, Math.max(0, s.page.value + d))
    if (pager.current) glide(pager.current, i * pager.current.clientWidth)
  }

  const snap = (smooth = false) => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    if (Math.abs(el.scrollLeft - s.page.value * el.clientWidth) <= 1) return
    if (smooth) glide(el, s.page.value * el.clientWidth)
    else { stopGlide(el); el.scrollLeft = s.page.value * el.clientWidth }
  }
  // opened, or the page changed from outside (bookmark): show it
  useLayoutEffect(() => { if (open) snap() }, [open, page])
  // sentence mode remembers where reading stopped too
  useEffect(() => { const c = list[page]; if (open && c) s.savePos(c.id, c.id) }, [open, page])
  // the page width changes with the window and when the word panel is docked / undocked
  useEffect(() => {
    const el = pager.current
    if (!el) return
    const ro = new ResizeObserver(() => snap())
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  useEffect(() => {
    if (!open) return
    const h = (e: KeyboardEvent) => {
      if ((e.target as Element)?.closest?.('input, textarea') || (s.popup.value && !docked())) return
      if (e.key === 'ArrowRight') turn(1)
      else if (e.key === 'ArrowLeft') turn(-1)
      else if (e.key === 'Escape' || e.key === 'ArrowDown') s.closeSentenceMode()
    }
    window.addEventListener('keydown', h)
    return () => window.removeEventListener('keydown', h)
  }, [open, list])

  const onScroll = () => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    const i = Math.round(el.scrollLeft / el.clientWidth)
    if (i !== s.page.value && i >= 0 && i < list.length) s.page.value = i
  }

  const cur = list[page]
  return (
    <div class="sentence-mode">
      <div class="sm-header">
        <IconButton icon="down" label="Свернуть" size={32} onClick={() => s.closeSentenceMode()} />
        <div class="sm-progress">
          <ProgressPill p={(page + 1) / Math.max(1, list.length)} />
          <span>{page + 1} / {list.length}</span>
        </div>
        <div class="sm-right">{cur && <BookmarkButton s={s} id={cur.id} />}</div>
      </div>
      <div class="sm-main">
      <div class="sm-body">
      <div class="pager" ref={pager} onScroll={onScroll} {...swipe}>
        {list.map((sent, i) => (
          <div class="page" key={sent.id}>
            {Math.abs(i - page) <= 2 && (
              <div class="page-inner reader-text" style={{ '--fs': Number(prefs.readerFont.value) * 1.15 + 'px' } as JSX.CSSProperties} {...handlers}>
                <div class="sm-center">
                  <SentenceFlow s={s} sents={[sent]} />
                  <SentenceTranslationPage s={s} id={sent.id} />
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
      <TurnButtons at={page} count={list.length} turn={turn} />
      </div>
      {/* the docked word panel stays beside the sentence (sentence mode lies over the text view and its panel) */}
      {docked() && <DockedPanel s={s} />}
      </div>
    </div>
  )
}

/** Sentence mode: the translation under the sentence, folds away and back with a button (kept for all sentences). */
function SentenceTranslationPage({ s, id }: { s: ReaderSession; id: number }) {
  const open = prefs.smTranslation.value === '1'
  useEffect(() => { if (open) s.ensureSentence(id) }, [id, open])
  const tr = s.sentTr.value[id]
  return (
    <>
      <button
        class={'tr-toggle' + (open ? ' open' : '')} aria-expanded={open}
        onClick={() => { prefs.smTranslation.value = open ? '0' : '1' }}
      >
        <Icon name="translate" size={18} />
        <span>{open ? 'Скрыть перевод' : 'Показать перевод'}</span>
        <Icon name="down" size={20} class="tr-chevron" />
      </button>
      <div class={'fold' + (open ? ' open' : '')}>
        <div class="fold-inner">
          <div class="sent-tr big">
            <div class="sent-tr-text">{tr || '…'}</div>
            <BookmarkButton s={s} id={id} />
            <RetranslateButton s={s} id={id} />
            <IconButton icon="chat" label="Чат о предложении" size={20} class="small primary" onClick={() => s.chatSentence(id)} />
          </div>
        </div>
      </div>
    </>
  )
}

/** What the word window shows; used in the pop-up sheet and in the docked panel. */
function WordContent({ s, r, head }: { s: ReaderSession; r: WordRef; head?: JSX.Element }) {
  const [own, setOwn] = useState('')
  useEffect(() => setOwn(''), [r])
  const word = s.word(r)
  const saved = s.chosen.value[word.toLowerCase()]
  const best = s.popupBest.value
  const thinking = s.popupThinking.value
  const main = saved ?? best
  const hints = s.popupHints.value
  const sid = refSent(r)
  const tr = s.sentTr.value[sid]
  const marked = s.isBookmarked(sid)
  return (
    <div class="word-sheet">
      <div class="ws-head">
        <div class="ws-title">
          <div class="ws-word">{word}</div>
          {main && <div class={'ws-main' + (!saved && thinking ? ' prov' : '')}>{main}</div>}
        </div>
        {thinking && <Spinner size={18} />}
        {(saved || r in s.pins.value) && <IconButton icon="close" label="Убрать перевод с этого слова" onClick={() => s.unpin(r)} />}
        {head}
      </div>
      {s.popupNote.value.trim() && <p class="ws-note">{s.popupNote.value}</p>}
      {s.popupError.value ? <p class="error-text">Ошибка: {s.popupError.value}</p>
        : !hints ? <LinearProgress />
        : (
          <>
            <div class="chips">
              {s.options(r).map((h) => (
                <Chip key={h} label={h} selected={!!saved && h.toLowerCase() === saved.toLowerCase()} onClick={() => s.choose(r, h)} />
              ))}
            </div>
            <form class="own" onSubmit={(e) => { e.preventDefault(); s.choose(r, own); setOwn('') }}>
              <input value={own} placeholder="Свой вариант" onInput={(e) => setOwn((e.target as HTMLInputElement).value)} autocapitalize="off" />
              <IconButton icon="check" label="Сохранить" disabled={!own.trim()} onClick={() => { s.choose(r, own); setOwn('') }} />
            </form>
          </>
        )}
      <hr />
      {tr && (
        <div class="ws-tr">
          <div class="sent-tr-text">{tr}</div>
          <RetranslateButton s={s} id={sid} />
        </div>
      )}
      <div class="chips">
        {!tr && <Chip icon="translate" label="Перевод" onClick={() => (docked() ? s.ensureSentence(sid) : s.toggleSentence(sid))} />}
        <Chip icon={marked ? 'bookmark' : 'bookmarkBorder'} label={marked ? 'В закладках' : 'Закладка'} onClick={() => s.toggleBookmark(sid)} />
        <Chip icon="chat" label="Спросить" onClick={() => s.chatWord(sid, word)} />
      </div>
    </div>
  )
}

/** What the window for several words shows; pop-up sheet or docked panel. */
function PhraseContent({ s, head }: { s: ReaderSession; head?: JSX.Element }) {
  const p = s.phrase.value!
  const [own, setOwn] = useState('')
  const saved = s.chosen.value[p.text.toLowerCase()]
  const tr = s.sentTr.value[p.sent]
  const loading = s.phraseLoading.value
  return (
    <div class="word-sheet">
      <div class="ws-head">
        <div class="ws-title">
          <div class="ws-word">{p.text}</div>
          {s.phraseBest.value && <div class="ws-main">{s.phraseBest.value}</div>}
        </div>
        {head}
      </div>
      {loading && <LinearProgress />}
      {s.phraseError.value && <p class="error-text">Ошибка: {s.phraseError.value}</p>}
      {s.phraseNote.value.trim() && <p class="ws-note">{s.phraseNote.value}</p>}
      {s.phraseOptions.value.length > 0 && (
        <div class="chips">
          {s.phraseOptions.value.map((h) => (
            <Chip key={h} label={h} selected={!!saved && h.toLowerCase() === saved.toLowerCase()} onClick={() => s.choosePhrase(h)} />
          ))}
        </div>
      )}
      {!loading && (
        <form class="own" onSubmit={(e) => { e.preventDefault(); s.choosePhrase(own); setOwn('') }}>
          <input value={own} placeholder="Свой вариант" onInput={(e) => setOwn((e.target as HTMLInputElement).value)} autocapitalize="off" />
          <IconButton icon="check" label="Сохранить" disabled={!own.trim()} onClick={() => { s.choosePhrase(own); setOwn('') }} />
        </form>
      )}
      <hr />
      {tr && <div class="ws-tr"><div class="sent-tr-text">{tr}</div></div>}
      <div class="chips">
        {!tr && <Chip icon="translate" label="Перевод предложения" onClick={() => s.ensureSentence(p.sent)} />}
        <Chip icon="chat" label="Спросить" onClick={() => s.chatWord(p.sent, p.text)} />
      </div>
    </div>
  )
}

const DockButton = () => narrow.value ? null : (
  <IconButton icon="pin" label="Закрепить панель справа" onClick={() => { prefs.dockPanel.value = '1' }} />
)

function WordSheet({ s, r }: { s: ReaderSession; r: WordRef }) {
  return <Sheet onClose={() => s.closePopup()}><WordContent s={s} r={r} head={<DockButton />} /></Sheet>
}

/** Window for several words translated together. */
function PhraseSheet({ s }: { s: ReaderSession }) {
  return <Sheet onClose={() => s.closePhrase()}><PhraseContent s={s} head={<DockButton />} /></Sheet>
}

/** The word window docked at the right: always there, empty until a word is chosen. */
function DockedPanel({ s }: { s: ReaderSession }) {
  const r = s.popup.value
  const phrase = s.phrase.value
  return (
    <aside class="dock-panel">
      <div class="dock-head">
        <span class="grow">Перевод</span>
        <IconButton icon="close" label="Открепить панель" onClick={() => { prefs.dockPanel.value = '0'; s.closePopup(); s.closePhrase() }} />
      </div>
      <div class="dock-body">
        {phrase ? <PhraseContent s={s} />
          : r ? <WordContent s={s} r={r} />
          : <p class="dock-empty">Нажмите на слово — здесь появится его перевод. Долгое нажатие и протягивание — несколько слов сразу.</p>}
      </div>
    </aside>
  )
}
