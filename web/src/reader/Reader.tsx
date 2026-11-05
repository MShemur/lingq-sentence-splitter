// Reader screen: text with tappable words, sentence mode, word window. Port of the UI part of Reader.kt.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { Fragment, render, type JSX } from 'preact'
import { Icon } from '../icons'
import { Press } from '../press'
import { back } from '../app'
import { prefs } from '../settings'
import { bookmarks } from '../bookmarks'
import { Chip, ErrorBox, IconButton, LinearProgress, Loading, Menu, MenuItem, ProgressPill, Sheet, Spinner, TopBar } from '../ui'
import { LessonBookmarksDialog } from '../screens/Bookmarks'
import { ref as wordRef, refSent, type ReaderSession, type Sent, type WordRef } from './session'

/** A long-press selection is being dragged: the page must not scroll under the finger (iOS). */
let selecting = false
document.addEventListener('touchmove', (e) => { if (selecting) e.preventDefault() }, { passive: false })

/**
 * Word gestures (event delegation): tap = translation above the word, double tap = word window,
 * long press = sentence translation, long press and drag over other words of the sentence = those words together.
 */
function useWordPress(s: ReaderSession) {
  const press = useMemo(() => new Press<WordRef>({
    tap: (r) => s.tap(r),
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
  return (
    <div class="screen reader">
      <TopBar
        center
        nav={<IconButton icon="close" label="Закрыть урок" onClick={back} />}
        title={paras ? <div class="reader-progress"><ProgressPill p={s.textProgress.value} /><span>{s.pageLabel.value}</span></div> : null}
        actions={<ReaderActions s={s} />}
      />
      <div class="screen-body">
        {err ? <ErrorBox text={err} retry={() => void s.load()} /> : !paras ? <Loading /> : <TextView s={s} paras={paras} />}
      </div>
      <nav class="bottom-nav">
        <button onClick={() => s.openSentenceMode()}><span class="nav-ind"><Icon name="list" /></span><span>По предложениям</span></button>
        <button class={s.allSentences.value ? 'active' : ''} onClick={() => { s.allSentences.value = !s.allSentences.value }}>
          <span class="nav-ind"><Icon name="translate" /></span><span>Переводы</span>
        </button>
        <button onClick={() => s.chatText()}><span class="nav-ind"><Icon name="chat" /></span><span>Чат</span></button>
      </nav>
      <SentenceOverlay s={s} />
      {s.popup.value && <WordSheet s={s} r={s.popup.value} />}
      {s.phrase.value && <PhraseSheet s={s} />}
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

/** Renders blocks into the hidden measuring box and reads their layout. */
function measureBlocks(box: HTMLElement, s: ReaderSession, blocks: Sent[][]): Measure[] {
  render(<>{blocks.map((b, i) => <Paragraph key={i} s={s} sents={b} />)}</>, box)
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

/** Measured blocks, by what decides their height; survives page rebuilds. */
type MeasureCache = Map<string, Measure>

/** What changes the height of a sentence: pins above its words, its shown translation, its bookmark mark. */
function sentenceSigs(s: ReaderSession): (id: number) => string {
  const pinsOf = new Map<number, string>()
  for (const [r, v] of Object.entries(s.pins.value)) {
    const id = refSent(r)
    pinsOf.set(id, (pinsOf.get(id) ?? '') + r + '=' + v + ';')
  }
  return (id) =>
    (pinsOf.get(id) ?? '') +
    (s.sentenceShown(id) ? '|T' + (s.sentTr.value[id] ?? '') + (s.retranslating.value[id] ? 'r' : '') : '') +
    (s.isBookmarked(id) ? '|B' : '')
}

/**
 * Measures blocks, using the cache where nothing changed. New ones are rendered in small batches, so a long
 * lesson is never put into the page all at once (Android ran out of memory doing that, PORTING note 87bc83d).
 */
function measureCached(box: HTMLElement, s: ReaderSession, cache: MeasureCache, prefix: string, blocks: Sent[][]): Measure[] {
  const sig = sentenceSigs(s)
  const keys = blocks.map((b) => prefix + b.map((x) => x.id + ':' + sig(x.id)).join('/'))
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
 * Paragraphs are measured as they are drawn (pins and shown translations included); a paragraph that does not
 * fit is split at a sentence boundary.
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

  // everything that changes the height of the text
  const deps = [paras, s.pins.value, s.shown.value, s.allSentences.value, s.sentTr.value, s.retranslating.value,
    prefs.readerFont.value, bookmarks.value, size.w, size.h]

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
    if (el && el.clientWidth) el.scrollTo({ left: i * el.clientWidth, behavior: smooth ? 'smooth' : 'auto' })
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
    if (el && el.clientWidth && !scrolling.current && Math.round(el.scrollLeft / el.clientWidth) !== cur) scrollTo(cur, false)
  }, [pages, cur])

  // publish progress and what is on screen
  useLayoutEffect(() => {
    if (!pages.length) return
    const p = pages[cur] ?? []
    s.visibleSents = new Set(p.flatMap((b) => b.sents.map((x) => x.id)))
    s.textProgress.value = (cur + 1) / pages.length
    s.pageLabel.value = `${cur + 1} / ${pages.length}`
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

  // arrow keys turn pages (iPad keyboard)
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      const el = pager.current
      if (!el || el.closest('.layer.below') || s.sentenceMode.value || s.popup.value || s.menuOpen.value || s.bookmarksOpen.value) return
      if ((e.target as Element)?.closest?.('input, textarea')) return
      // from the page being turned to, so quick presses do not skip pages
      const at = Math.round(el.scrollLeft / el.clientWidth)
      const target = turnTo.current != null && scrolling.current ? turnTo.current : at
      let i: number
      if (e.key === 'ArrowRight' || e.key === 'PageDown' || e.key === ' ') i = Math.min(pages.length - 1, target + 1)
      else if (e.key === 'ArrowLeft' || e.key === 'PageUp') i = Math.max(0, target - 1)
      else return
      turnTo.current = i
      scrollTo(i, true)
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
    scrollEnd.current = window.setTimeout(() => { scrolling.current = false; turnTo.current = null }, 150)
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
      <div class="pager text-pager" ref={pager} onScroll={onScroll}>
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
    </>
  )
}

/** A paragraph flows as one text; a shown sentence translation is inserted right after its sentence. */
function Paragraph({ s, sents }: { s: ReaderSession; sents: Sent[] }) {
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

function SentenceFlow({ s, sents }: { s: ReaderSession; sents: Sent[] }) {
  const pins = s.pins.value
  const prov = s.provisional.value
  const popup = s.popup.value
  return (
    <div class="flow">
      {sents.map((sent) => (
        <Fragment key={sent.id}>
          {s.isBookmarked(sent.id) && <span class="flow-mark" data-se={sent.id}><Icon name="bookmark" /></span>}
          {sent.chunks.map((chunk) => (
            <span class="chunk" data-se={sent.id}>
              {chunk.map((t) => {
                if (!t.isWord) return <span class="punct">{t.text}</span>
                const r = wordRef(sent.id, t.index)
                const tr = pins[r]
                const selected = tr !== undefined || popup === r
                const picked = s.inSelection(sent.id, t.index)
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

  const snap = (smooth = false) => {
    const el = pager.current
    if (!el || !el.clientWidth) return
    if (Math.round(el.scrollLeft / el.clientWidth) !== s.page.value)
      el.scrollTo({ left: s.page.value * el.clientWidth, behavior: smooth ? 'smooth' : 'auto' })
  }
  // opened, or the page changed from outside (bookmark): show it
  useLayoutEffect(() => { if (open) snap() }, [open, page])
  useEffect(() => {
    const onResize = () => snap()
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  useEffect(() => {
    if (!open) return
    const h = (e: KeyboardEvent) => {
      if ((e.target as Element)?.closest?.('input, textarea') || s.popup.value) return
      const go = (d: number) => {
        const i = Math.min(list.length - 1, Math.max(0, s.page.value + d))
        pager.current?.scrollTo({ left: i * pager.current.clientWidth, behavior: 'smooth' })
      }
      if (e.key === 'ArrowRight') go(1)
      else if (e.key === 'ArrowLeft') go(-1)
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
      <div class="pager" ref={pager} onScroll={onScroll}>
        {list.map((sent, i) => (
          <div class="page" key={sent.id}>
            {Math.abs(i - page) <= 2 && (
              <div class="page-inner reader-text" style={{ '--fs': Number(prefs.readerFont.value) * 1.15 + 'px' } as JSX.CSSProperties} {...handlers}>
                <SentenceFlow s={s} sents={[sent]} />
                <SentenceTranslationPage s={s} id={sent.id} />
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

/** In sentence mode the translation is always shown. */
function SentenceTranslationPage({ s, id }: { s: ReaderSession; id: number }) {
  useEffect(() => { s.ensureSentence(id) }, [id])
  const tr = s.sentTr.value[id]
  return (
    <div class="sent-tr big">
      <div class="sent-tr-text">{tr || '…'}</div>
      <BookmarkButton s={s} id={id} />
      <RetranslateButton s={s} id={id} />
      <IconButton icon="chat" label="Чат о предложении" size={20} class="small primary" onClick={() => s.chatSentence(id)} />
    </div>
  )
}

function WordSheet({ s, r }: { s: ReaderSession; r: WordRef }) {
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
    <Sheet onClose={() => s.closePopup()}>
      <div class="word-sheet">
        <div class="ws-head">
          <div class="ws-title">
            <div class="ws-word">{word}</div>
            {main && <div class={'ws-main' + (!saved && thinking ? ' prov' : '')}>{main}</div>}
          </div>
          {thinking && <Spinner size={18} />}
          {(saved || r in s.pins.value) && <IconButton icon="close" label="Убрать перевод с этого слова" onClick={() => s.unpin(r)} />}
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
              <form class="own" onSubmit={(e) => { e.preventDefault(); s.choose(r, own) }}>
                <input value={own} placeholder="Свой вариант" onInput={(e) => setOwn((e.target as HTMLInputElement).value)} autocapitalize="off" />
                <IconButton icon="check" label="Сохранить" disabled={!own.trim()} onClick={() => s.choose(r, own)} />
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
          {!tr && <Chip icon="translate" label="Перевод" onClick={() => s.toggleSentence(sid)} />}
          <Chip icon={marked ? 'bookmark' : 'bookmarkBorder'} label={marked ? 'В закладках' : 'Закладка'} onClick={() => s.toggleBookmark(sid)} />
          <Chip icon="chat" label="Спросить" onClick={() => s.chatSentence(sid)} />
        </div>
      </div>
    </Sheet>
  )
}

/** Window for several words translated together. */
function PhraseSheet({ s }: { s: ReaderSession }) {
  const p = s.phrase.value!
  const [own, setOwn] = useState('')
  const saved = s.chosen.value[p.text.toLowerCase()]
  const tr = s.sentTr.value[p.sent]
  const loading = s.phraseLoading.value
  return (
    <Sheet onClose={() => s.closePhrase()}>
      <div class="word-sheet">
        <div class="ws-word">{p.text}</div>
        {s.phraseBest.value && <div class="ws-main">{s.phraseBest.value}</div>}
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
          <Chip icon="chat" label="Спросить" onClick={() => { s.closePhrase(); s.chatSentence(p.sent) }} />
        </div>
      </div>
    </Sheet>
  )
}
