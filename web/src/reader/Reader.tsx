// Reader screen: text with tappable words, sentence mode, word window. Port of the UI part of Reader.kt.
import { usePinPlace } from './pinPlace'
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { Fragment, render, type JSX } from 'preact'
import { memo } from 'preact/compat'
import { signal } from '@preact/signals'
import { Icon } from '../icons'
import { checkLessons } from '../check'
import { Press } from '../press'
import { back, go, stack } from '../app'
import { readerStyle, useKeepAwake, lineMul, paraGapPx, paraIndentEm } from '../readerLook'
import { useReadingTimer } from '../stats'
import { prefs } from '../settings'
import { Chip, Dialog, ErrorBox, IconButton, LinearProgress, Loading, Menu, MenuFontRow, MenuItem, ProgressPill, Sheet, Spinner, TopBar } from '../ui'
import { LessonBookmarksDialog } from '../screens/Bookmarks'
import { ReaderHelp } from './Help'
import { ref as wordRef, refSent, type ReaderSession, type Sent, type Token, type WordRef, refToken } from './session'
import type { ShownPic } from '../bookpics'
import { playLesson } from '../player'
import { levelName } from '../lingq'
import { LessonDeleteDialog } from '../screens/CourseEdit'
import { PdfReader } from './PdfReader'

/** A long-press selection is being dragged: the page must not scroll under the finger (iOS). */
let selecting = false
/** A finger (or mouse) is dragging pages: they must not be snapped from elsewhere meanwhile. */
let dragging = false
// only over the reader's text, and the flag is dropped when the finger lifts: a long press that ended without its
// "end" (iOS cancels pointers on its own) left [selecting] on and blocked scrolling everywhere, e.g. podcast episodes
document.addEventListener('touchmove', (e) => {
  if (selecting && (e.target as Element | null)?.closest?.('.reader-text')) e.preventDefault()
}, { passive: false })
const dropSelecting = (e: TouchEvent) => { if (!e.touches.length) selecting = false }
document.addEventListener('touchend', dropSelecting)
document.addEventListener('touchcancel', dropSelecting)

/** Phone-sized screen: no docked panel (and no page-turn buttons, see styles). */
const narrowQuery = window.matchMedia('(max-width: 600px)')
const narrow = signal(narrowQuery.matches)
narrowQuery.addEventListener?.('change', (e) => { narrow.value = e.matches })

/** The word window is docked at the right (not on a phone: the text would get too narrow). */
export const docked = () => prefs.dockPanel.value === '1' && !narrow.value && !isBook()

/** Book mode ("Книжный режим"): full screen text, swipe only, word menu on a double tap. */
export const isBook = () => prefs.readerBook.value === '1'

/**
 * Page turning by swipe, done here instead of the browser's own scrolling: on the iPad the browser would take
 * the touch over as soon as the finger moves, which breaks long press + drag over words (selection).
 * Horizontal drag moves the pages and snaps to one; vertical drag scrolls a page that does not fit.
 */
function useSwipe(pager: { current: HTMLDivElement | null }, onTap?: (e: PointerEvent) => void, onPullDown?: () => void) {
  const st = useRef<{
    id: number; x: number; y: number; t: number; left: number; top: number; lastX: number
    scroller: HTMLElement | null; axis: '' | 'x' | 'y'
  } | null>(null)
  const end = (e?: PointerEvent) => {
    const g = st.current
    st.current = null
    dragging = false
    const el = pager.current
    if (g && !g.axis && e && onTap && performance.now() - g.t < 450) onTap(e)
    // pulled down from the top of the page (sentence mode: collapses it)
    if (g && g.axis === 'y' && e && onPullDown && g.top <= 0 && e.clientY - g.y > 110) onPullDown()
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
    onPointerUp: (e: PointerEvent) => end(e),
    onPointerCancel: () => end(),
    onPointerLeave: (e: PointerEvent) => { if (e.pointerType === 'mouse') end() },
  }
}

/** Running page-turn animations, by pager. */
const glides = new WeakMap<HTMLElement, number>()

/** Stops a page-turn animation (a finger took the page). */
export function stopGlide(el: HTMLElement) {
  const id = glides.get(el)
  if (id != null) cancelAnimationFrame(id)
  glides.delete(el)
}

/**
 * Turns the pager to [left] slowly and evenly (the browser's own smooth scrolling is too fast for a book page).
 * [ms] is for a whole page; a shorter way (the rest of a swipe) takes proportionally less, but not too little.
 */
export function glide(el: HTMLElement, left: number, ms = 520) {
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
    // book mode: a double tap opens the word menu, a long press does nothing
    double: (r) => { if (isBook()) s.bookMenu.value = r; else void s.openPopup(r) },
    long: (r) => { if (isBook()) return; selecting = true; s.selectStart(r, true) },
    longMove: (e) => {
      if (isBook()) return
      const el = document.elementFromPoint(e.clientX, e.clientY)?.closest?.('[data-w]') as HTMLElement | null
      if (el) s.selectTo(el.dataset.w!)
    },
    longEnd: () => { if (isBook()) return; selecting = false; s.selectEnd() },
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
  const rootRef = useRef<HTMLDivElement>(null)
  useLooseJustify(rootRef, prefs.readerJustify.value === '1')
  const book = isBook()
  return (
    <div ref={rootRef} class={'screen reader' + (s.pinsHidden.value ? ' pins-hidden' : '') + (prefs.readerJustify.value === '1' ? ' justify' : '')
      + (book ? ' book' : '') + (book && s.bookBar.value ? ' book-bar' : '') + (prefs.wordBg.value === '0' ? ' nobg' : '')} style={readerStyle(book)}>
      <TopBar
        center
        nav={<IconButton icon="close" label="Закрыть урок" onClick={back} />}
        title={paras ? <div class="reader-progress"><PageSlider s={s} /><span>{s.pageLabel.value}</span></div> : null}
        actions={<ReaderActions s={s} />}
      />
      <div class={'screen-body reader-body' + (docked() ? ' docked' : '')}>
        <div class="text-area">
          {err ? <ErrorBox text={err} retry={() => void s.load()} /> : !paras ? <Loading />
            : s.showPdf.value && s.pdfLink.value ? <PdfReader s={s} link={s.pdfLink.value} /> : <TextView s={s} paras={paras} />}
        </div>
        {docked() && <DockedPanel s={s} />}
      </div>
      {!book && <nav class="bottom-nav">
        <button onClick={() => s.openSentenceMode()}><span class="nav-ind"><Icon name="list" /></span><span>По предложениям</span></button>
        <button class={s.allSentences.value ? 'active' : ''} onClick={() => { s.allSentences.value = !s.allSentences.value }}>
          <span class="nav-ind"><Icon name="translate" /></span><span>Переводы</span>
        </button>
        <button onClick={() => s.chatText()}><span class="nav-ind"><Icon name="chat" /></span><span>Чат</span></button>
      </nav>}
      {book && s.bookMenu.value && (
        <WordMenuAt r={s.bookMenu.value} box=".text-pager" onClose={() => { s.bookMenu.value = null }}>
          {(close) => <BookMenuItems s={s} r={s.bookMenu.value!} close={close} />}
        </WordMenuAt>
      )}
      <SentenceOverlay s={s} />
      {!docked() && s.popup.value && <WordSheet s={s} r={s.popup.value} />}
      {!docked() && s.phrase.value && <PhraseSheet s={s} />}
      {s.wordsOpen.value && <PinnedWordsDialog s={s} />}
      <PicViewer s={s} />
      {s.bookmarksOpen.value && <LessonBookmarksDialog s={s} onClose={() => { s.bookmarksOpen.value = false }} />}
      {s.editing.value != null && <SentenceEditDialog s={s} id={s.editing.value} />}
      {s.levelResult.value && (
        <Dialog title="Уровень урока" onClose={() => { s.levelResult.value = null }}
          actions={<button class="btn text" onClick={() => { s.levelResult.value = null }}>OK</button>}>
          <p><b>{levelName(s.levelResult.value[0])}</b></p>
          {!!s.levelResult.value[1] && <p>{s.levelResult.value[1]}</p>}
          <p class="hint">Уровень сохранён в уроке на LingQ.</p>
        </Dialog>
      )}
    </div>
  )
}

function ReaderActions({ s }: { s: ReaderSession }) {
  const menu = s.menuOpen.value
  const close = () => { s.menuOpen.value = false }
  const pinCount = Object.keys(s.pins.value).length + Object.keys(s.phrasePins.value).length
  const [help, setHelp] = useState(false)
  const [del, setDel] = useState(false)
  const [aiAsk, setAiAsk] = useState(false)
  return (
    <>
      {help && <ReaderHelp onClose={() => setHelp(false)} />}
      {aiAsk && (
        <Dialog title="Обработать текст с ИИ?" onClose={() => setAiAsk(false)} actions={<>
          <button class="btn text" onClick={() => setAiAsk(false)}>Отмена</button>
          <button class="btn text" onClick={() => { setAiAsk(false); void s.cleanWithAi() }}>Обработать</button>
        </>}>
          <p>ИИ уберёт из текста урока мусор (номера страниц, колонтитулы, сноски, разорванные строки) и сохранит его в LingQ.
            Предложения, которые изменятся, потеряют перевод и время в аудио. Копия старого текста сохранится в резервных копиях.</p>
        </Dialog>
      )}
      {del && <LessonDeleteDialog l={s.lang} id={s.lesson.id} title={s.title.value || s.lesson.title} onClose={() => setDel(false)} />}
      <IconButton icon="bookmark" label="Закладки урока" onClick={() => { s.bookmarksOpen.value = true }} />
      <div class="menu-anchor">
        <IconButton icon="more" label="Ещё" onClick={() => { s.menuOpen.value = true }} />
        {menu && (
          <Menu onClose={close}>
            <div class="menu-group">Текст</div>
            <MenuFontRow size={Number(prefs.readerFont.value)} onChange={(d) => s.changeFont(d)} />
            <MenuItem icon={isBook() ? 'check' : undefined} onClick={() => {
              close(); s.bookBar.value = false; s.bookMenu.value = null
              prefs.readerBook.value = isBook() ? '0' : '1'
            }}>Книжный режим</MenuItem>
            {s.pdfLink.value && (
              <MenuItem icon={s.showPdf.value ? 'list' : 'file'} onClick={() => { close(); s.showPdf.value = !s.showPdf.value }}>
                {s.showPdf.value ? 'Показать текстом' : 'Показать страницы PDF'}
              </MenuItem>
            )}
            {s.pdfLink.value && s.showPdf.value && (
              <MenuItem icon={prefs.pdfFit.value === 'page' ? 'check' : undefined}
                onClick={() => { close(); prefs.pdfFit.value = prefs.pdfFit.value === 'page' ? 'width' : 'page' }}>
                Страница PDF целиком
              </MenuItem>
            )}
            <MenuItem icon="palette" onClick={() => { close(); go({ k: 'settingsReading' }) }}>Настройки чтения…</MenuItem>
            <hr />
            <div class="menu-group">Переводы</div>
            <MenuItem icon="translate" onClick={() => { close(); void s.translateAll() }}>
              {s.trAll.value != null ? `Перевожу урок: ${s.trAll.value}` : 'Перевести весь урок'}
            </MenuItem>
            {pinCount > 0 && <MenuItem icon="list" onClick={() => { close(); s.wordsOpen.value = true }}>Слова с переводом ({pinCount})</MenuItem>}
            {(pinCount > 0 || s.pinsHidden.value) && (
              <MenuItem icon="translate" onClick={() => { close(); s.pinsHidden.value = !s.pinsHidden.value; s.peek.value = {} }}>
                {s.pinsHidden.value ? 'Показать переводы над словами' : 'Временно скрыть переводы'}
              </MenuItem>
            )}
            {pinCount > 0 && <MenuItem onClick={() => { s.clearPins(); close() }}>Снять переводы над словами ({pinCount})</MenuItem>}
            <hr />
            <div class="menu-group">Урок</div>
            {s.audioUrl.value && (
              <MenuItem icon="headphones" onClick={() => {
                close()
                playLesson(s.lang, { ...s.lesson, title: s.title.value, audio: s.audioUrl.value ?? '' })
              }}>Слушать в плеере</MenuItem>
            )}
            <MenuItem icon="chart" onClick={() => { close(); void s.detectLevel() }}>
              {s.levelBusy.value ? 'Определяю уровень…' : 'Определить уровень (ИИ)'}
            </MenuItem>
            <MenuItem icon="magic" onClick={() => { close(); if (s.aiClean.value == null) setAiAsk(true) }}>
              {s.aiClean.value != null ? `ИИ обрабатывает текст: ${s.aiClean.value}` : 'Обработать текст с ИИ…'}
            </MenuItem>
            <MenuItem icon="check" onClick={() => {
              close()
              const title = s.title.value || s.lesson.title
              checkLessons(s.lang, [{ ...s.lesson, title }], title)
            }}>Исправить разбивку…</MenuItem>
            <hr />
            <MenuItem icon="info" onClick={() => { close(); setHelp(true) }}>Как пользоваться</MenuItem>
            <MenuItem icon="chat" onClick={() => { close(); go({ k: 'feedback' }) }}>Отзыв / задача</MenuItem>
            <hr />
            <MenuItem icon="delete" danger onClick={() => { close(); setDel(true) }}>Удалить урок…</MenuItem>
          </Menu>
        )}
      </div>
    </>
  )
}

/** Part of a paragraph that lies on one page. */
/**
 * Sentences of one paragraph on one page. [start]: the first sentence begins at this chunk (its start is on the page
 * before); [end]: the last sentence stops before this chunk (it goes on on the next page).
 */
/** [cont]: the block goes on with a paragraph begun on the page before (no first-line indent in book mode). */
interface Block { para: number; sents: Sent[]; start?: number; end?: number; cont?: boolean }

/** Height of a block of sentences, the bottom of every prefix of it (k+1 sentences), and of every chunk, in px. */
interface Measure { height: number; bottoms: number[]; chunks: number[][] }

const PARA_GAP = 14

/** Renders blocks (bare text) into the hidden measuring box and reads their layout. */
function measureBlocks(box: HTMLElement, s: ReaderSession, blocks: Spec[], split: boolean): Measure[] {
  render(<>{blocks.map((b, i) => <Paragraph key={i} s={s} sents={b.sents} start={b.start} cont={b.cont} plain split={split} />)}</>, box)
  const out: Measure[] = []
  // the bottom of the line [x] ends on: book mode lays words out as text (inline), where a word's box is lower than
  // its line by half the leading; a word split by a hyphen has two boxes, the last one counts
  const lh = parseFloat(getComputedStyle(box).lineHeight) || 0
  const lineBottom = (x: HTMLElement) => {
    const rs = x.getClientRects()
    const r = rs.length ? rs[rs.length - 1] : x.getBoundingClientRect()
    return r.bottom + Math.max(0, (lh - r.height) / 2)
  }
  box.querySelectorAll<HTMLElement>(':scope > .para').forEach((el, i) => {
    const top = el.getBoundingClientRect().top
    const bottomOf = new Map<number, number>()
    el.querySelectorAll<HTMLElement>('[data-se]').forEach((x) => {
      const id = Number(x.dataset.se)
      bottomOf.set(id, Math.max(bottomOf.get(id) ?? 0, lineBottom(x) - top))
    })
    let acc = 0
    const bottoms = blocks[i].sents.map((sent) => (acc = Math.max(acc, bottomOf.get(sent.id) ?? acc)))
    const chunks = blocks[i].sents.map((sent) => [...el.querySelectorAll<HTMLElement>(`.chunk[data-se="${sent.id}"]`)]
      .map((x) => lineBottom(x) - top))
    out.push({ height: el.getBoundingClientRect().height, bottoms, chunks })
  })
  render(null, box)
  return out
}

/** Measured blocks, by width, font and sentences; survives page rebuilds. */
type MeasureCache = Map<string, Measure>
/** What is measured: sentences of a paragraph, the first from chunk [start]. */
interface Spec { sents: Sent[]; start?: number; cont?: boolean }

/**
 * Measures blocks, using the cache where nothing changed. New ones are rendered in small batches, so a long
 * lesson is never put into the page all at once (Android ran out of memory doing that, PORTING note 87bc83d).
 */
function measureCached(box: HTMLElement, s: ReaderSession, cache: MeasureCache, prefix: string, blocks: Spec[], split = false): Measure[] {
  // [split]: a shown sentence translation ends the line there ('!' in the key), as on the page
  const keys = blocks.map((b) => prefix + b.sents.map((x) => x.id + (split && s.sentenceShown(x.id) ? '!' : '')).join('/') + (b.start ? '@' + b.start : '') + (b.cont ? '^' : '') + (asideOf(s, b.sents, b.start, b.cont) ?? '') + picsSig(s, b.sents, b.start, b.cont))
  const missing = blocks.map((_, i) => i).filter((i) => !cache.has(keys[i]))
  for (let a = 0; a < missing.length; a += 25) {
    const part = missing.slice(a, a + 25)
    measureBlocks(box, s, part.map((i) => blocks[i]), split).forEach((m, j) => cache.set(keys[part[j]], m))
  }
  if (cache.size > 4000) { const keep = new Set(keys); for (const k of cache.keys()) if (!keep.has(k)) cache.delete(k) }
  return keys.map((k) => cache.get(k)!)
}

/**
 * The text split into pages that fit the screen, turned with a swipe like a book (port of Android PagedText).
 * Pages are laid out from the bare text only: translations above words and under sentences do not move text to
 * other pages, a page that grows with them scrolls up and down instead. A paragraph that does not fit is split at
 * a sentence boundary; a sentence goes whole to the next page (the user's choice, web v1.58), only one taller than a
 * whole page is split at a line inside it.
 */
/** [cut]: book mode, a sentence that does not fit goes on on the next page from the line where it was cut (as a book). */
function paginate(box: HTMLElement, s: ReaderSession, paras: Sent[][], h: number, cache: MeasureCache, prefix: string, gap = PARA_GAP, cut = false): Block[][] {
  // book mode cuts a sentence at a line: the lines must be the ones on the page, so a shown sentence translation
  // (which starts the text after it on a new line) is laid out too, without its own height (the page scrolls for that)
  const whole = measureCached(box, s, cache, prefix, paras.map((p) => ({ sents: p })), cut)
  const pages: Block[][] = []
  let cur: Block[] = []
  let used = 0
  const need = (x: number) => (cur.length ? gap : 0) + x
  const flush = () => { if (cur.length) pages.push(cur); cur = []; used = 0 }
  paras.forEach((para, pi) => {
    let from = 0
    let start = 0 // the first sentence of the rest begins at this chunk
    let m = whole[pi]
    while (from < para.length) {
      const rest = para.slice(from)
      const st = start || undefined
      const cont = from > 0 || start > 0 || undefined
      if (used + need(m.height) <= h) { used += need(m.height); cur.push({ para: pi, sents: rest, start: st, cont }); break }
      // the longest run of whole sentences that still fits on this page
      let k = 0
      while (k < rest.length && used + need(m.bottoms[k]) <= h) k++
      // all the text fits but the book's picture after it does not: the last sentence goes on with the picture
      if (k === rest.length && s.pics.value.after.has(para[para.length - 1].id)) k--
      // a sentence taller than a whole page (nothing else on it), or any sentence in book mode: the lines that fit
      const cb = cut ? m.chunks[k] ?? [] : k === 0 && !cur.length ? m.chunks[0] ?? [] : []
      let c = 0
      while (c < cb.length - 1 && used + need(cb[c]) <= h) c++
      if (c > 0) {
        const base = k === 0 ? start : 0
        cur.push({ para: pi, sents: rest.slice(0, k + 1), start: st, end: base + c, cont })
        from += k; start = base + c; flush()
      } else if (k > 0) { cur.push({ para: pi, sents: rest.slice(0, k), start: st, cont }); from += k; start = 0; flush() }
      else if (cur.length) { flush(); continue }
      else { cur.push({ para: pi, sents: rest.slice(0, 1), start: st, cont }); from += 1; start = 0; flush() } // not one line fits
      if (from < para.length) m = measureCached(box, s, cache, prefix, [{ sents: para.slice(from), start, cont: true }], cut)[0]
    }
  })
  flush()
  return pages.length ? pages : [[]]
}

/**
 * Justified text: a line with much room left (two or three long words) is not spread over the width. A line is
 * spread only when its free space is at most 20 % of the width (as Android's JustifyLoose); otherwise its last item
 * gets margin-right: auto (class "jl"), which packs the line to the left. Lines do not move: only spacing changes.
 */
function looseJustify(root: HTMLElement) {
  const on = !!root.closest('.justify')
  for (const flow of root.querySelectorAll<HTMLElement>('.flow')) {
    const kids = [...flow.children] as HTMLElement[]
    kids.forEach((k) => { if (k.classList.contains('jl')) { k.classList.remove('jl'); k.style.paddingRight = '' } })
    // book mode: words are text there, justified by the browser with hyphenation (no flex lines to pack)
    if (!on || flow.closest('.measurer, .page-inner, .book')) continue
    const width = flow.clientWidth
    if (!width) continue
    const lines: HTMLElement[][] = []
    let bottom = NaN
    for (const k of kids) {
      const b = Math.round(k.getBoundingClientRect().bottom)
      if (!lines.length || Math.abs(b - bottom) > 2) { lines.push([]); bottom = b }
      lines[lines.length - 1].push(k)
    }
    // also the last line: it is spread too when its paragraph goes on on the next page
    const startX = flow.getBoundingClientRect().left + (parseFloat(getComputedStyle(flow).paddingLeft) || 0)
    for (const [li, line] of lines.entries()) {
      // the first line starts after the paragraph indent (the ::before spacer is not a child)
      let used = li === 0 ? Math.max(0, line[0].getBoundingClientRect().left - startX) : 0
      for (const k of line) used += k.getBoundingClientRect().width + (parseFloat(getComputedStyle(k).marginRight) || 0)
      if (width - used > width * 0.2) {
        // the auto margin counts as 0 when the browser breaks lines: the word's own gap goes into a padding instead,
        // or the next word could move up to this line, and the paragraph would wrap otherwise than its pages were
        // measured (a page then ended in the middle of a line, web v1.66)
        const last = line[line.length - 1]
        last.style.paddingRight = (parseFloat(getComputedStyle(last).marginRight) || 0) + 'px'
        last.classList.add('jl')
      }
    }
  }
}

/** Keeps looseJustify applied while the text changes (pages, translations, font) or the size does. */
function useLooseJustify(ref: { current: HTMLElement | null }, on: boolean) {
  useEffect(() => {
    const el = ref.current
    if (!el) return
    let raf = 0
    const run = () => { cancelAnimationFrame(raf); raf = requestAnimationFrame(() => looseJustify(el)) }
    run()
    if (!on) return () => cancelAnimationFrame(raf)
    const mo = new MutationObserver((list) => { if (list.some((m) => m.type === 'childList' || m.type === 'characterData')) run() })
    mo.observe(el, { childList: true, subtree: true, characterData: true })
    const ro = new ResizeObserver(run)
    ro.observe(el)
    return () => { mo.disconnect(); ro.disconnect(); cancelAnimationFrame(raf) }
  }, [on])
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
  /** The pager width the current pages were laid out for. */
  const laidOut = useRef(0)
  /** When pages were last rebuilt (scroll events right after it come from the layout, not the reader). */
  const rebuilt = useRef(0)

  const book = isBook()
  usePinPlace(pager, book, [prefs.readerFont.value, prefs.lineSpacing.value, prefs.pinGap.value, prefs.readerFace.value, prefs.paraIndent.value, pages, cur])
  /** Pages per screen: book mode in landscape on a big screen shows two side by side (a spread). */
  const per = book && size.w > size.h && size.w >= 900 ? 2 : 1
  const spreadOf = (i: number) => Math.floor(i / per)

  // what changes the layout of the bare text (translations do not: the page scrolls instead)
  const deps = [paras, prefs.readerFont.value, size.w, size.h, per, book, prefs.lineSpacing.value, prefs.paraGap.value, prefs.paraIndent.value,
    book ? s.shown.value : null, book && s.allSentences.value, s.asides.value, s.pics.value]

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
    const x = spreadOf(i) * el.clientWidth
    if (smooth) glide(el, x)
    else { stopGlide(el); el.scrollLeft = x }
  }

  useLayoutEffect(() => {
    const box = measurer.current
    if (!box || !size.h) return
    const cs = getComputedStyle(box)
    const h = size.h - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom)
    const next = paginate(box, s, paras, h, cache.current,
      `${size.w}|${prefs.readerFont.value}|${per}|${book ? 'b' : ''}|${lineMul()}|${paraIndentEm()}|`, paraGapPx(book), book)
    // the same text stays on screen when pages are rebuilt. The anchor is the sentence the reader got to and is NOT
    // moved to the first sentence of the new page: that crept back a little with every rebuild (font A+ / A−, the
    // window resized when switching apps) until the reading position was back at the start.
    const old = pagesRef.current[s.textPage.value]
    const onPage = (id: number | null) => id != null && !!old?.some((b) => b.sents.some((x) => x.id === id))
    const anchor = !old || onPage(s.textAnchor) ? s.textAnchor : old[0]?.sents[0]?.id ?? s.textAnchor
    s.textAnchor = anchor
    const i = anchor == null ? 0 : Math.max(0, pageOf(next, anchor))
    laidOut.current = size.w
    // the browser clamps / keeps the old scroll offset while the new pages go in: those scroll events are not page turns
    rebuilt.current = Date.now()
    setPages(next)
    s.textPage.value = i
  }, deps)

  // show the current page unless the user is the one scrolling there (a swipe or a smooth page turn)
  useLayoutEffect(() => {
    if (!pages.length) return
    const el = pager.current
    if (el && el.clientWidth && !scrolling.current && Math.abs(el.scrollLeft - spreadOf(cur) * el.clientWidth) > 1) scrollTo(cur, false)
    if (s.textAnchor == null && pages[cur]) s.textAnchor = pages[cur][0]?.sents[0]?.id ?? null
  }, [pages, cur])

  // publish progress and what is on screen
  useLayoutEffect(() => {
    if (!pages.length) return
    const first = spreadOf(cur) * per
    const p = pages.slice(first, first + per).flat()
    const last = Math.min(pages.length, first + per)
    s.visibleSents = new Set(p.flatMap((b) => b.sents.map((x) => x.id)))
    s.textProgress.value = last / pages.length
    s.pageLabel.value = `${last > first + 1 ? `${first + 1}–${last}` : first + 1} / ${pages.length}`
    s.pageCount.value = pages.length
    const ids = p.flatMap((b) => b.sents.map((x) => x.id))
    if (ids.length) s.savePos(ids[0], ids[ids.length - 1])
  }, [pages, cur, per])

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
    if (Math.abs(el.scrollLeft - spreadOf(i) * el.clientWidth) > 1) scrollTo(i, false)
  }

  /** Turns [d] pages, counting from the page being turned to, so quick presses do not skip pages. */
  const turn = (d: number) => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    const at = Math.round(el.scrollLeft / el.clientWidth) * per
    const from = turnTo.current != null && scrolling.current ? turnTo.current : at
    const i = spreadOf(Math.max(0, Math.min(pages.length - 1, from + d * per))) * per
    turnTo.current = i
    // the animation starts on the next frame: a second quick press must already count from [i]
    scrolling.current = true
    clearTimeout(scrollEnd.current)
    scrollEnd.current = window.setTimeout(() => { scrolling.current = false; turnTo.current = null; settle() }, 150)
    scrollTo(i, true)
  }
  // book mode: a tap in the middle of the page (not on a word) shows / hides the top bar over the text
  // (the iPad keeps this; Android v1.127 fixed the bar in place, the user wants it back here: note 20261117-024446-web)
  const swipe = useSwipe(pager, (e) => {
    if (!isBook() || (e.target as Element | null)?.closest?.('[data-w], .sent-tr, button')) return
    const x = e.clientX / window.innerWidth, y = e.clientY / window.innerHeight
    if (x >= 0.2 && x <= 0.8 && y >= 0.15 && y <= 0.85) s.bookBar.value = !s.bookBar.value
  })

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
    // the window changed width (Split View, switching apps, rotation): the browser keeps the old scroll offset,
    // which points at another page now. That is not the reader turning pages: the pages are rebuilt around
    // the same sentence and put in place, and the position must not move (it used to jump ahead).
    if (Math.abs(el.clientWidth - laidOut.current) > 1) return
    if (Date.now() - rebuilt.current < 400 && !dragging && !glides.has(el)) { settle(); return }
    scrolling.current = true
    clearTimeout(scrollEnd.current)
    scrollEnd.current = window.setTimeout(() => { scrolling.current = false; turnTo.current = null; settle() }, 150)
    const i = Math.round(el.scrollLeft / el.clientWidth) * per
    if (spreadOf(i) !== spreadOf(s.textPage.value) && i >= 0 && i < pages.length) {
      s.textPage.value = i
      s.textAnchor = pages[i][0]?.sents[0]?.id ?? null
    }
  }

  const fs = { '--fs': prefs.readerFont.value + 'px' } as JSX.CSSProperties
  const pageText = (i: number) => {
    const p = pages[i]
    if (!p) return <div class="reader-text page-text" key={i} style={fs} />
    return (
      <div class="reader-text page-text" lang={s.lang} key={i} style={fs} {...handlers}>
        {p.map((b) => <Paragraph key={b.para + ':' + b.sents[0].id + ':' + (b.start ?? 0)} s={s} sents={b.sents} start={b.start} end={b.end} cont={b.cont}
          cut={b.end != null || b !== p[p.length - 1] ? false : b.sents[b.sents.length - 1] !== paras[b.para][paras[b.para].length - 1]} />)}
      </div>
    )
  }
  return (
    <>
      <div class={'reader-text measurer' + (per === 2 ? ' half' : '')} lang={s.lang} ref={measurer} style={fs} aria-hidden="true" />
      <div class="pager text-pager" ref={pager} onScroll={onScroll} {...swipe}>
        {Array.from({ length: Math.ceil(pages.length / per) }, (_, si) => (
          <div class="page" key={si}>
            {Math.abs(si - spreadOf(cur)) <= 1 && (per === 2 ? (
              <div class="spread">{[0, 1].map((k) => pageText(si * 2 + k))}</div>
            ) : pageText(si))}
          </div>
        ))}
      </div>
      {!book && <TurnButtons at={cur} count={pages.length} turn={turn} />}
    </>
  )
}

/** PDF book: the paragraph begun by [sents] came from a caption ('c') or a box ('o') of a page (not on a page after its start). */
const asideOf = (s: ReaderSession, sents: Sent[], start?: number, cont?: boolean) =>
  start || cont || !sents.length ? undefined : s.asides.value.get(sents[0].id)

/** EPUB pictures before the paragraph begun by [sents] and after it (when it ends here); measured with the paragraph. */
function picsAround(s: ReaderSession, sents: Sent[], start?: number, cont?: boolean, end?: number, cut?: boolean) {
  if (!sents.length) return [undefined, undefined] as const
  const before = start || cont ? undefined : s.pics.value.before.get(sents[0].id)
  const after = end != null || cut ? undefined : s.pics.value.after.get(sents[sents.length - 1].id)
  return [before, after] as const
}
const picsSig = (s: ReaderSession, sents: Sent[], start?: number, cont?: boolean) => {
  const [b, a] = picsAround(s, sents, start, cont)
  return b || a ? `#${b?.length ?? 0}.${a?.length ?? 0}` : ''
}

/** Pictures of the book (kept in this browser): their size is known, so pages are laid out before they load. */
function Pics({ s, list }: { s: ReaderSession; list?: ShownPic[] }) {
  if (!list) return null
  return (
    <>
      {list.map((p, i) => (
        <figure key={'pic' + i} class="para-pic" onClick={(e) => { e.stopPropagation(); s.picOpen.value = p }}>
          <img src={p.url} alt={p.cap} draggable={false}
            style={{ width: `min(100%, ${p.w}px, calc(42vh * ${(p.w / p.h).toFixed(4)}))`, aspectRatio: `${p.w} / ${p.h}` }} />
          {p.cap && <figcaption>{p.cap}</figcaption>}
        </figure>
      ))}
    </>
  )
}

/** A picture on the whole screen; a tap closes it. */
function PicViewer({ s }: { s: ReaderSession }) {
  const p = s.picOpen.value
  if (!p) return null
  return (
    <div class="pic-viewer" role="dialog" aria-label="Картинка" onClick={() => { s.picOpen.value = null }}>
      <img src={p.url} alt={p.cap} />
      {p.cap && <div class="pic-viewer-cap">{p.cap}</div>}
    </div>
  )
}

/** The grey label over such a paragraph: shown only, not part of the lesson text; measured with the paragraph. */
function AsideLabel({ kind }: { kind?: 'c' | 'o' }) {
  return kind ? <div class="para-label" aria-hidden="true">{kind === 'c' ? 'Подпись к картинке' : 'Врезка'}</div> : null
}

/**
 * A paragraph flows as one text; a shown sentence translation is inserted right after its sentence.
 * [plain]: the bare text without translations and marks (how pages are laid out).
 */
function Paragraph({ s, sents, plain, start, end, cut, split, cont }: {
  s: ReaderSession; sents: Sent[]; plain?: boolean
  /** [plain] in book mode: the text breaks after a shown sentence translation as on the page (no translation drawn). */
  split?: boolean
  /** Goes on with a paragraph begun on the page before. */
  cont?: boolean
  /** Chunk range of a block cut by the pages (see Block); [cut]: the paragraph goes on on the next page. */
  start?: number; end?: number; cut?: boolean
}) {
  const cls = 'para' + (sents[0]?.heading ? ' heading' : '') + (end != null || cut ? ' cut' : '') + (start || cont ? ' cont' : '')
  const last = sents[sents.length - 1]
  const [picsBefore, picsAfter] = picsAround(s, sents, start, cont, end, cut)
  const label = [<Pics key="pics" s={s} list={picsBefore} />, <AsideLabel key="label" kind={asideOf(s, sents, start, cont)} />]
  const tail = <Pics key="pics-after" s={s} list={picsAfter} />
  if (plain && !split) return <div class={cls}>{label}<SentenceFlow s={s} sents={sents} start={start} end={end} plain />{tail}</div>
  if (plain) {
    const runs: Sent[][] = [[]]
    sents.forEach((x, i) => { runs[runs.length - 1].push(x); if (i < sents.length - 1 && s.sentenceShown(x.id)) runs.push([]) })
    return (
      <div class={cls}>
        {label}
        {runs.map((r, i) => <SentenceFlow key={i} s={s} sents={r} start={i === 0 ? start : undefined} end={i === runs.length - 1 ? end : undefined} plain />)}
        {tail}
      </div>
    )
  }
  const parts: JSX.Element[] = [...label]
  let run: Sent[] = []
  let first = true
  const flow = (key: string) => {
    const el = <SentenceFlow key={key} s={s} sents={run} start={first ? start : undefined} end={run[run.length - 1] === last ? end : undefined} />
    first = false
    return el
  }
  for (const sent of sents) {
    run.push(sent)
    // a sentence going on on the next page gets its translation there, after its end
    if (s.sentenceShown(sent.id) && !(sent === last && end != null)) {
      parts.push(flow('f' + sent.id))
      parts.push(<SentenceTranslation key={'t' + sent.id} s={s} id={sent.id} />)
      run = []
    }
  }
  if (run.length) parts.push(flow('rest'))
  parts.push(tail)
  return <div class={cls}>{parts}</div>
}

type PhrasePin = { from: number; to: number; text: string }

/** What one sentence shows besides its words; [sig] sums it up, so a sentence re-renders only when its part changes. */
interface SentState {
  pins: Record<string, string>
  peek: Record<string, true>
  prov: Record<string, true>
  popup: WordRef | null
  sel: { from: number; to: number } | null
  phrases: PhrasePin[]
  marked: boolean
  /** The other part of a split verb whose word is open or pinned: highlighted too. */
  pair?: Record<string, true>
  sig: string
}

const NO_STATE: SentState = { pins: {}, peek: {}, prov: {}, popup: null, sel: null, phrases: [], marked: false, sig: '' }

/**
 * The words of [sents] flowing as one text, with pinned translations above words / phrases.
 * The session's state (pins, the word window, the selection, phrases, bookmarks) is read here once and handed to each
 * sentence as plain data: a tap or a selection drag re-renders the sentence it touches, not every word of three pages.
 */
export function SentenceFlow({ s, sents, plain, start, end }: { s: ReaderSession; sents: Sent[]; plain?: boolean; start?: number; end?: number }) {
  /** Chunk range of each sentence: the first may begin at [start], the last stop before [end]. */
  const from = (i: number) => (i === 0 ? start : undefined)
  const to = (i: number) => (i === sents.length - 1 ? end : undefined)
  if (plain) {
    return <div class="flow">{sents.map((sent, i) => <SentView key={sent.id} s={s} sent={sent} st={NO_STATE} plain from={from(i)} to={to(i)} />)}</div>
  }
  const pins = s.pins.value
  const prov = s.provisional.value
  const popup = s.popup.value
  const sel = s.sel.value
  const peekAll = s.peek.value
  void s.phrasePins.value
  const ids = new Set(sents.map((x) => x.id))
  const pairOn: Record<string, true> = {}
  for (const [w, ps] of Object.entries(s.pairs.value)) {
    if (popup !== w && pins[w] === undefined) continue
    for (const p of ps) if (ids.has(refSent(p))) pairOn[p] = true
  }
  const by = new Map<number, { pins: Record<string, string>; prov: Record<string, true> }>()
  for (const [k, v] of Object.entries(pins)) {
    const id = refSent(k)
    if (!ids.has(id)) continue
    let e = by.get(id)
    if (!e) by.set(id, e = { pins: {}, prov: {} })
    e.pins[k] = v
    if (prov[k]) e.prov[k] = true
  }
  return (
    <div class="flow">
      {sents.map((sent, si) => {
        const e = by.get(sent.id)
        const pp = e?.pins ?? {}
        const pv = e?.prov ?? {}
        const pu = popup && refSent(popup) === sent.id ? popup : null
        const sl = sel && sel.sent === sent.id ? { from: sel.from, to: sel.to } : null
        const phrases = s.phrasesIn(sent.id)
        const marked = s.isBookmarked(sent.id)
        const pk: Record<string, true> = {}
        for (const k in peekAll) if (refSent(k) === sent.id) pk[k] = true
        const pr: Record<string, true> = {}
        for (const k in pairOn) if (refSent(k) === sent.id) pr[k] = true
        const sig = JSON.stringify([pp, Object.keys(pv), pu, sl, phrases, marked, Object.keys(pk), Object.keys(pr)])
        return <SentView key={sent.id} s={s} sent={sent} st={{ pins: pp, peek: pk, prov: pv, popup: pu, sel: sl, phrases, marked, pair: pr, sig }} from={from(si)} to={to(si)} />
      })}
    </div>
  )
}

const SentView = memo(function SentView({ s, sent, st, plain, from, to }: {
  s: ReaderSession; sent: Sent; st: SentState; plain?: boolean
  /** Only chunks [from, to) (a sentence cut by the pages). */
  from?: number; to?: number
}) {
  const { pins, peek, prov, popup, sel, phrases: phr } = st
  const picked = (i: number) => !!sel && i >= sel.from && i <= sel.to

  /** One chunk (a word with its punctuation); [ph]: the pinned phrase it belongs to. */
  const chunkView = (chunk: Token[], ph: PhrasePin | null, key: number) => (
    <span class="chunk" data-se={sent.id} key={key}>
      {chunk.map((t) => {
        const inPhrase = !!ph && t.index >= ph.from && t.index <= ph.to
        const pk = !plain && picked(t.index)
        if (!t.isWord) {
          const between = !!ph && t.index > ph.from && t.index < ph.to
          return <span class={'punct' + (pk ? ' picked' : between ? ' sel' : '') + (t.b ? ' md-b' : '') + (t.i ? ' md-i' : '')}>{t.text}</span>
        }
        const r = wordRef(sent.id, t.index)
        const tr = inPhrase ? undefined : pins[r]
        const selected = tr !== undefined || popup === r || inPhrase || !!st.pair?.[r]
        return (
          <span class={'w' + (selected ? ' sel' : '') + (pk ? ' picked' : '')} data-w={r}>
            {tr !== undefined && <span class={'pin' + (prov[r] ? ' prov' : '') + (peek[r] ? ' peek' : '')}>{tr || '…'}</span>}
            <span class={'wt' + (t.b ? ' md-b' : '') + (t.i ? ' md-i' : '')}>{t.text}</span>
          </span>
        )
      })}
    </span>
  )

  // a chunk belongs to a phrase by one of its words, or by anything lying inside it (a dash between its words)
  const phraseOf = (c: Token[]) => phr.find((p) => c.some((t) =>
    t.isWord ? t.index >= p.from && t.index <= p.to : t.index > p.from && t.index < p.to)) ?? null
  const out: (JSX.Element | string)[] = []
  const ch = sent.chunks
  const stop = Math.min(to ?? ch.length, ch.length)
  for (let i = from ?? 0; i < stop;) {
    const ph = phraseOf(ch[i])
    if (!ph) { out.push(chunkView(ch[i], null, i), ' '); i++; continue }
    // several words translated together: one translation above all of them
    let j = i
    while (j < stop && phraseOf(ch[j]) === ph) j++
    out.push(
      <span class="phrase" key={'p' + i}>
        <span class={'pin phrase-pin' + (peek[`${sent.id}:${ph.from}:${ph.to}`] ? ' peek' : '')}>{ph.text || '…'}</span>
        <span class="phrase-words">{ch.slice(i, j).flatMap((c, n) => [chunkView(c, ph, i + n), ' '])}</span>
      </span>,
      ' ',
    )
    i = j
  }
  void s
  return (
    <>
      {st.marked && !from && <span class="flow-mark" data-se={sent.id}><Icon name="bookmark" /></span>}
      {out}
    </>
  )
}, (a, b) => a.sent === b.sent && a.plain === b.plain && a.st.sig === b.st.sig && a.from === b.from && a.to === b.to)

/**
 * A sentence translation, or a shimmering placeholder while it is being made (one or two lines, like the
 * sentence); the text fades in when it arrives, and shimmers again while "translate again" works.
 */
export function TrText({ s, id }: { s: ReaderSession; id: number }) {
  const tr = s.sentTr.value[id]
  if (!tr) {
    const long = (s.sentence(id)?.text.length ?? 0) > 70
    return (
      <span class="tr-wait" role="status" aria-label="Перевод…">
        <span class="tr-wait-line" />
        {long && <span class="tr-wait-line short" />}
      </span>
    )
  }
  return <span class={'tr-arrived' + (s.retranslating.value[id] ? ' tr-redo' : '')} key={tr}>{tr}</span>
}

export function SentenceTranslation({ s, id }: { s: ReaderSession; id: number }) {
  useEffect(() => { s.ensureSentence(id) }, [id])
  return (
    <div class="sent-tr" data-se={id}>
      <div class="sent-tr-text" onClick={() => { if (!s.allSentences.value) s.toggleSentence(id) }}><TrText s={s} id={id} /></div>
      <RetranslateButton s={s} id={id} />
      <ChatButton s={s} id={id} />
    </div>
  )
}

/** A menu right under the long-pressed word [r] (searched inside [box]). */
export function WordMenuAt({ r, box, onClose, children }: {
  r: WordRef; box: string; onClose: () => void; children: (close: () => void) => JSX.Element
}) {
  const el = [...document.querySelectorAll(`${box} [data-w="${r}"]`)].find((e) => (e as HTMLElement).offsetParent) as HTMLElement | undefined
  const rect = el?.getBoundingClientRect()
  // the sentence translation the long press showed stays readable: the menu goes under it
  const tr = [...document.querySelectorAll(`${box} .sent-tr[data-se="${refSent(r)}"]`)].find((e) => (e as HTMLElement).offsetParent)
  const below = Math.max(rect?.bottom ?? 0, tr?.getBoundingClientRect().bottom ?? 0)
  const left = rect ? Math.max(8, Math.min(rect.left, window.innerWidth - 260)) : 16
  const topPx = rect ? Math.min(below + 4, window.innerHeight - 300) : 80
  return (
    <Menu onClose={onClose} class="word-menu">
      <style>{`.word-menu { position: fixed; left: ${left}px; top: ${topPx}px; right: auto; }`}</style>
      {children(onClose)}
    </Menu>
  )
}

/** Book mode word menu (double tap): the word window first, then what the long press and the buttons do elsewhere. */
function BookMenuItems({ s, r, close }: { s: ReaderSession; r: WordRef; close: () => void }) {
  const id = refSent(r)
  const marked = s.isBookmarked(id)
  return (
    <>
      <MenuItem icon="translate" onClick={() => { close(); void s.openPopup(r) }}>Перевод слова</MenuItem>
      <MenuItem icon="chat" onClick={() => { close(); s.toggleSentence(id) }}>
        {s.sentenceShown(id) ? 'Скрыть перевод предложения' : 'Перевод предложения'}
      </MenuItem>
      <MenuItem icon={marked ? 'bookmark' : 'bookmarkBorder'} onClick={() => { close(); s.toggleBookmark(id) }}>
        {marked ? 'Убрать закладку' : 'В закладки'}
      </MenuItem>
      <MenuItem onClick={() => { close(); void s.toggleBold(id, refToken(r), refToken(r)) }}>
        {s.isBold(id, refToken(r), refToken(r)) ? 'Убрать жирный' : 'Сделать жирным'}
      </MenuItem>
      <MenuItem icon="edit" onClick={() => { close(); s.startEdit(id) }}>Исправить предложение</MenuItem>
      {s.hasTime(id) && <MenuItem icon="headphones" onClick={() => { close(); s.listenFrom(id) }}>Слушать отсюда</MenuItem>}
      <MenuItem icon="chat" onClick={() => { close(); s.chatSentence(id) }}>Чат о предложении</MenuItem>
    </>
  )
}

/** Page mode: the progress line on top is a slider; dragging it moves through the lesson page by page. */
function PageSlider({ s }: { s: ReaderSession }) {
  const n = s.pageCount.value
  if (n < 2) return <ProgressPill p={s.textProgress.value} />
  const v = Math.min(n - 1, s.textPage.value)
  return (
    <input class="page-slider" type="range" min={0} max={n - 1} step={1} value={v} aria-label="Страница"
      style={{ '--p': (v / (n - 1)) * 100 + '%' } as JSX.CSSProperties}
      onInput={(e) => { s.textAnchor = null; s.textPage.value = Number((e.target as HTMLInputElement).value) }} />
  )
}

/** The lesson's words with a translation above them; a tap goes to the word's sentence. */
function PinnedWordsDialog({ s }: { s: ReaderSession }) {
  const close = () => { s.wordsOpen.value = false }
  const list = s.pinnedList()
  return (
    <Dialog title={`Слова с переводом (${list.length})`} onClose={close} actions={<button class="btn text" onClick={close}>Закрыть</button>}>
      {!list.length ? <p class="hint">Нажмите на слово в тексте — перевод появится над ним и здесь.</p> : (
        <div class="pinned-list">
          {list.map((x) => (
            <button key={x.key} class="pinned-row" onClick={() => { close(); s.jumpTo(x.sent) }}>
              <b>{x.word}</b><span>{x.tr}</span>
            </button>
          ))}
        </div>
      )}
    </Dialog>
  )
}

/** Sentence mode ⋮: correct the sentence, listen from it, text size, feedback. */
function SentenceMenu({ s, id }: { s: ReaderSession; id: number }) {
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  return (
    <div class="menu-anchor">
      <IconButton icon="more" label="Ещё" onClick={() => setOpen(true)} />
      {open && (
        <Menu onClose={close}>
          <MenuItem icon="edit" onClick={() => { close(); s.startEdit(id) }}>Исправить предложение</MenuItem>
          {s.hasTime(id) && <MenuItem icon="headphones" onClick={() => { close(); s.listenFrom(id) }}>Слушать отсюда</MenuItem>}
          <hr />
          <MenuFontRow size={Number(prefs.readerFont.value)} onChange={(d) => s.changeFont(d)} />
          <hr />
          <MenuItem icon="chat" onClick={() => { close(); go({ k: 'feedback' }) }}>Отзыв / задача</MenuItem>
        </Menu>
      )}
    </div>
  )
}

/** Window to correct a sentence and its translation; saving writes them into the lesson on LingQ. */
function SentenceEditDialog({ s, id }: { s: ReaderSession; id: number }) {
  const sent = s.sentences.value.find((x) => x.id === id)
  const [text, setText] = useState(sent?.text ?? '')
  const [tr, setTr] = useState(() => s.shownTranslation(id))
  if (!sent) return null
  const saving = s.editSaving.value
  const close = () => { if (!saving) s.editing.value = null }
  return (
    <Dialog title="Исправить предложение" onClose={close} actions={<>
      <button class="btn text" disabled={saving} onClick={close}>Отмена</button>
      <button class="btn text" disabled={saving || !text.trim()} onClick={() => void s.saveEdit(id, text, tr)}>
        {saving ? <Spinner size={18} /> : 'Сохранить'}
      </button>
    </>}>
      <div class="edit-sent">
        <label class="field">
          <span>Предложение</span>
          <textarea rows={3} value={text} disabled={saving} onInput={(e) => setText((e.target as HTMLTextAreaElement).value)} />
        </label>
        <label class="field">
          <span>Перевод</span>
          <textarea rows={3} value={tr} disabled={saving} onInput={(e) => setTr((e.target as HTMLTextAreaElement).value)} />
        </label>
        <p class="hint">
          Изменения сохранятся в уроке на LingQ (копия урока до правки — в «Резервных копиях»).
          Переводы слов этого предложения сбросятся, если изменить текст.
        </p>
        {s.editError.value && <p class="error-text">{s.editError.value}</p>}
      </div>
    </Dialog>
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

/** Chat about this sentence, next to its translation. */
function ChatButton({ s, id }: { s: ReaderSession; id: number }) {
  return <IconButton icon="chat" label="Чат о предложении" size={20} class="small primary" onClick={() => s.chatSentence(id)} />
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
  // a swipe down on the sentence (scrolled to its top) or on the top bar collapses sentence mode (as Android v1.126)
  const swipe = useSwipe(pager, undefined, () => s.closeSentenceMode())
  const headY = useRef<number | null>(null)
  const head = {
    onPointerDown: (e: PointerEvent) => {
      if ((e.target as Element).closest?.('button')) return
      headY.current = e.clientY
      try { (e.currentTarget as Element).setPointerCapture(e.pointerId) } catch { /* gone */ }
    },
    onPointerUp: (e: PointerEvent) => {
      const y = headY.current
      headY.current = null
      if (y != null && e.clientY - y > 66) s.closeSentenceMode()
    },
    onPointerCancel: () => { headY.current = null },
  }
  const turn = (d: number) => {
    const i = Math.min(list.length - 1, Math.max(0, s.page.value + d))
    if (pager.current) glide(pager.current, i * pager.current.clientWidth)
  }

  const width = useRef(0)
  const snap = (smooth = false) => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    width.current = el.clientWidth
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
    // a new window width keeps the old scroll offset (another page): stay on the sentence, do not follow it
    if (el.clientWidth !== width.current) { width.current = el.clientWidth; snap(); return }
    const i = Math.round(el.scrollLeft / el.clientWidth)
    if (i !== s.page.value && i >= 0 && i < list.length) s.page.value = i
  }

  const cur = list[page]
  return (
    <div class="sentence-mode">
      <div class="sm-header" {...head}>
        <IconButton icon="down" label="Свернуть" size={32} onClick={() => s.closeSentenceMode()} />
        <div class="sm-progress">
          <ProgressPill p={(page + 1) / Math.max(1, list.length)} />
          <span>{page + 1} / {list.length}</span>
        </div>
        <div class="sm-right">{cur && <BookmarkButton s={s} id={cur.id} />}{cur && <SentenceMenu s={s} id={cur.id} />}</div>
      </div>
      <div class="sm-main">
      <div class="sm-body">
      <div class="pager" ref={pager} onScroll={onScroll} {...swipe}>
        {list.map((sent, i) => (
          <div class="page" key={sent.id}>
            {Math.abs(i - page) <= 2 && (
              <div class="page-inner reader-text" style={{ '--fs': Number(prefs.readerFont.value) + 'px' } as JSX.CSSProperties} {...handlers}>
                <div class={'sm-center' + (sent.heading ? ' heading' : '')}>
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
            <div class="sent-tr-text" onClick={() => { prefs.smTranslation.value = '0' }}><TrText s={s} id={id} /></div>
            <RetranslateButton s={s} id={id} />
            <ChatButton s={s} id={id} />
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
        <BoldChip s={s} id={sid} from={refToken(r)} to={refToken(r)} />
      </div>
    </div>
  )
}

/** "Жирным" / "Убрать жирный": ** around the words in the lesson text on LingQ. */
function BoldChip({ s, id, from, to }: { s: ReaderSession; id: number; from: number; to: number }) {
  void s.sentences.value // follows the saved sentence
  const on = s.isBold(id, from, to)
  return <Chip label={on ? 'Убрать жирный' : <><b>Ж</b>ирным</>} onClick={() => void s.toggleBold(id, from, to)} />
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
        <BoldChip s={s} id={p.sent} from={p.from} to={p.to} />
      </div>
    </div>
  )
}

const DockButton = () => narrow.value ? null : (
  <IconButton icon="pin" label="Закрепить панель справа" onClick={() => { prefs.dockPanel.value = '1' }} />
)

export function WordSheet({ s, r }: { s: ReaderSession; r: WordRef }) {
  return <Sheet onClose={() => s.closePopup()}><WordContent s={s} r={r} head={<DockButton />} /></Sheet>
}

/** Window for several words translated together. */
export function PhraseSheet({ s }: { s: ReaderSession }) {
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
