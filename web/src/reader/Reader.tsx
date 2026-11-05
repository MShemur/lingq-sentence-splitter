// Reader screen: text with tappable words, sentence mode, word window. Port of the UI part of Reader.kt.
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks'
import { Fragment, type JSX } from 'preact'
import { Icon } from '../icons'
import { Press } from '../press'
import { back } from '../app'
import { prefs } from '../settings'
import { Chip, ErrorBox, IconButton, LinearProgress, Loading, Menu, MenuItem, ProgressPill, Sheet, Spinner, TopBar } from '../ui'
import { LessonBookmarksDialog } from '../screens/Bookmarks'
import { ref as wordRef, refSent, type ReaderSession, type Sent, type WordRef } from './session'

/** Tap / double tap / long press on the words inside a container (event delegation). */
function useWordPress(s: ReaderSession) {
  const press = useMemo(() => new Press<WordRef>({
    tap: (r) => s.tap(r),
    double: (r) => { void s.openPopup(r) },
    long: (r) => s.toggleSentence(refSent(r)),
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
        title={paras ? <ProgressPill p={s.textProgress.value} /> : null}
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

function TextView({ s, paras }: { s: ReaderSession; paras: Sent[][] }) {
  const box = useRef<HTMLDivElement>(null)
  const handlers = useWordPress(s)

  const paraEls = () => Array.from(box.current?.querySelectorAll<HTMLElement>('[data-p]') ?? [])
  const updateProgress = () => {
    const el = box.current
    if (!el) return
    const bottom = el.scrollTop + el.clientHeight
    s.textProgress.value = bottom >= el.scrollHeight - 4 ? 1 : bottom / el.scrollHeight
  }
  const scrollToSent = (id: number, smooth: boolean, onlyIfHidden: boolean) => {
    const el = box.current?.querySelector<HTMLElement>(`[data-sid="${id}"]`)
    const c = box.current
    if (!el || !c) return
    const r = el.getBoundingClientRect()
    const cr = c.getBoundingClientRect()
    if (onlyIfHidden && r.top >= cr.top && r.bottom <= cr.bottom) return
    c.scrollTo({ top: c.scrollTop + r.top - cr.top - cr.height / 3, behavior: smooth ? 'smooth' : 'auto' })
  }

  useEffect(() => {
    s.view = {
      firstVisible: () => {
        const top = box.current?.getBoundingClientRect().top ?? 0
        const i = paraEls().findIndex((p) => p.getBoundingClientRect().bottom > top + 4)
        return Math.max(0, i)
      },
      isVisible: (p) => {
        const el = paraEls()[p]
        const c = box.current?.getBoundingClientRect()
        if (!el || !c) return false
        const r = el.getBoundingClientRect()
        return r.bottom > c.top && r.top < c.bottom
      },
    }
    updateProgress()
    return () => { s.view = null }
  }, [s])

  // bookmark "go to source"
  const jump = s.jump.value
  useLayoutEffect(() => {
    if (jump == null) return
    scrollToSent(jump, false, false)
    s.jump.value = null
    updateProgress()
  }, [jump])

  // sentence mode collapsed: show its sentence
  const syncTo = s.syncTo.value
  useEffect(() => {
    if (syncTo == null) return
    scrollToSent(syncTo, true, true)
    s.syncTo.value = null
  }, [syncTo])

  return (
    <div class="reader-scroll" ref={box} onScroll={updateProgress}>
      <div class="reader-text" style={{ '--fs': prefs.readerFont.value + 'px' } as JSX.CSSProperties} {...handlers}>
        <p class="reader-hint">Нажатие на слово — перевод над ним, двойное — окошко с вариантами, долгое — перевод предложения.</p>
        {paras.map((p, i) => <Paragraph key={i} s={s} para={p} index={i} />)}
      </div>
    </div>
  )
}

/** A paragraph flows as one text; a shown sentence translation is inserted right after its sentence. */
function Paragraph({ s, para, index }: { s: ReaderSession; para: Sent[]; index: number }) {
  const parts: JSX.Element[] = []
  let run: Sent[] = []
  for (const sent of para) {
    run.push(sent)
    if (s.sentenceShown(sent.id)) {
      parts.push(<SentenceFlow key={'f' + sent.id} s={s} sents={run} />)
      parts.push(<SentenceTranslation key={'t' + sent.id} s={s} id={sent.id} />)
      run = []
    }
  }
  if (run.length) parts.push(<SentenceFlow key="rest" s={s} sents={run} />)
  return <div class="para" data-p={index}>{parts}</div>
}

function SentenceFlow({ s, sents }: { s: ReaderSession; sents: Sent[] }) {
  const pins = s.pins.value
  const prov = s.provisional.value
  const popup = s.popup.value
  return (
    <div class="flow">
      {sents.map((sent) => (
        <Fragment key={sent.id}>
          {s.isBookmarked(sent.id) && <span class="flow-mark" data-sid={sent.id}><Icon name="bookmark" /></span>}
          {sent.chunks.map((chunk, ci) => (
            <span class="chunk" data-sid={ci === 0 ? sent.id : undefined}>
              {chunk.map((t) => {
                if (!t.isWord) return <span class="punct">{t.text}</span>
                const r = wordRef(sent.id, t.index)
                const tr = pins[r]
                const selected = tr !== undefined || popup === r
                return (
                  <span class={'w' + (selected ? ' sel' : '')} data-w={r}>
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
    <div class="sent-tr">
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
    else { const t = setTimeout(() => setMounted(false), 260); return () => clearTimeout(t) }
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
