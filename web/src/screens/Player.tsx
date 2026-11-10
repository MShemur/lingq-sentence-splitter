// The player tab: a lesson's audio with its text. Collapsed: a window with the sentence sounding now (bold) and its
// neighbours; a tap opens the whole text, which follows the audio. Without a lesson: what to listen to.
// Port of Android Player.kt and the audio parts of Audio.kt / Extras.kt.
import { useEffect, useMemo, useRef, useState } from 'preact/hooks'
import type { JSX } from 'preact'
import * as audio from '../audio'
import { fmtLength, fmtSpeed, fmtTime } from '../audio'
import { courses, loadCourses, loadRecent, recent, stack } from '../app'
import { lang, prefs } from '../settings'
import * as api from '../lingq'
import type { Course, Lesson } from '../lingq'
import { openAudioBookmark, openPlaying, player, playAll, playLesson, queueLoading, switchTab } from '../player'
import type { ReaderSession, WordRef } from '../reader/session'
import { refSent } from '../reader/session'
import { PhraseSheet, SentenceFlow, TrText, WordMenuAt, WordSheet } from '../reader/Reader'
import { readerStyle } from '../readerLook'
import { Chip, ErrorBox, IconButton, Menu, MenuItem, Sheet, TopBar } from '../ui'
import { Icon } from '../icons'
import { Cover, plural } from './Library'
import { Press } from '../press'
import { useReadingTimer } from '../stats'

// --- bottom tabs and the mini player ---

export function MainTabs({ at }: { at: 'courses' | 'player' }) {
  return (
    <nav class="bottom-nav tabs">
      <button class={at === 'courses' ? 'active' : ''} onClick={() => switchTab('courses')}>
        <span class="nav-ind"><Icon name="library" /></span><span>Библиотека</span>
      </button>
      <button class={at === 'player' ? 'active' : ''} onClick={() => switchTab('player')}>
        <span class="nav-ind"><Icon name="headphones" /></span><span>Плеер</span>
      </button>
    </nav>
  )
}

/** Thin bar while audio is loaded and the player tab is not on screen: cover, title, progress, play/pause. */
export function MiniPlayer() {
  if (!audio.audioKey.value) return null
  const d = audio.duration.value
  const p = audio.position.value
  return (
    <div class="mini-player" onClick={() => openPlaying()}>
      <div class="mini-progress"><div style={{ width: (d > 0 ? Math.min(100, (p / d) * 100) : 0) + '%' }} /></div>
      <div class="mini-row">
        <Cover url={audio.audioImage.value} title={audio.audioTitle.value} class="mini-cover" />
        <div class="mini-text">
          <div class="mini-title">{audio.audioTitle.value}</div>
          <div class="mini-time">{fmtTime(p)}{d > 0 ? ` / ${fmtTime(d)}` : ''}</div>
        </div>
        <span onClick={(e) => e.stopPropagation()} class="mini-buttons">
          <IconButton icon="replay" label="Назад на 5 секунд" onClick={() => audio.seekBy(-5000)} />
          <IconButton icon={audio.playing.value ? 'pause' : 'play'} label={audio.playing.value ? 'Пауза' : 'Слушать'} onClick={() => audio.toggle()} size={30} />
        </span>
      </div>
    </div>
  )
}

// --- the tab ---

export function PlayerScreen() {
  const s = player.value
  if (!s) {
    return (
      <div class="screen">
        <TopBar title="Плеер" />
        <div class="screen-body scroll"><AudioLessons onPick={(l, x) => playLesson(l, x)} /></div>
        <MainTabs at="player" />
      </div>
    )
  }
  return <PlayerLesson s={s} key={s.audioKey} />
}

function PlayerLesson({ s }: { s: ReaderSession }) {
  useEffect(() => { if (!s.paragraphs.value && !s.error.value) void s.load() }, [s])
  const [expanded, setExpanded] = useState(false)
  const [picker, setPicker] = useState(false)
  const [marks, setMarks] = useState(false)
  const top = stack.value.at(-1)
  // reading the text in the player counts as study time too (listening is counted by the audio)
  useReadingTimer(top?.k === 'player' && expanded && !audio.playing.value)
  const paras = s.paragraphs.value
  const err = s.error.value
  const url = s.audioUrl.value
  const lesson = s.lesson
  return (
    <div class="screen player" style={readerStyle()}>
      {expanded ? (
        <header class="top-bar">
          <div class="top-bar-nav"><IconButton icon="down" label="Свернуть текст" onClick={() => setExpanded(false)} /></div>
          <div class="top-bar-title player-title-small">{s.title.value}</div>
          <div class="top-bar-actions"><PlayerActions s={s} onMarks={() => setMarks(true)} /></div>
        </header>
      ) : (
        <div class="player-head">
          <Cover url={lesson.imageUrl} title={s.title.value || '♪'} class="player-cover" />
          <button class="player-head-text" onClick={() => setPicker(true)} aria-label="Другой урок">
            <span class="player-title-row"><span class="player-title">{s.title.value || `Урок ${lesson.id}`}</span><Icon name="down" size={20} class="title-chevron" /></span>
            {!!lesson.courseTitle && <span class="player-course">{lesson.courseTitle}</span>}
          </button>
          <PlayerActions s={s} onMarks={() => setMarks(true)} />
        </div>
      )}
      <div class="screen-body player-body">
        {err ? <ErrorBox text={err} retry={() => void s.load()} />
          : !paras ? <div class="player-skeleton"><div class="skeleton line" /><div class="skeleton line wide" /><div class="skeleton line" /></div>
          : !url ? <p class="empty">У этого урока нет аудио на LingQ.</p>
          : expanded ? <FullText s={s} />
          : <TextWindow s={s} onOpen={() => setExpanded(true)} />}
      </div>
      {s.bottomSent.value != null && <BottomTranslation s={s} id={s.bottomSent.value} />}
      {url && <AudioBar s={s} />}
      <MainTabs at="player" />
      {s.wordMenu.value && (
        <WordMenuAt r={s.wordMenu.value} box=".player-text" onClose={() => { s.wordMenu.value = null }}>
          {(close) => <PlayerMenuItems s={s} r={s.wordMenu.value!} close={close} />}
        </WordMenuAt>
      )}
      {marks && <MarksSheet s={s} onClose={() => setMarks(false)} />}
      {picker && (
        <Sheet onClose={() => setPicker(false)}>
          <div class="sheet-title">Что слушать</div>
          <AudioLessons onPick={(l, x) => { setPicker(false); playLesson(l, x) }} onPlayAll={() => setPicker(false)} />
        </Sheet>
      )}
      {s.popup.value && <WordSheet s={s} r={s.popup.value} />}
      {s.phrase.value && <PhraseSheet s={s} />}
    </div>
  )
}

/** Header buttons: bookmark this place; menu with the lesson's audio bookmarks and another lesson. */
function PlayerActions({ s, onMarks, onPick }: { s: ReaderSession; onMarks: () => void; onPick?: () => void }) {
  const [menu, setMenu] = useState(false)
  return (
    <>
      <IconButton icon="bookmarkBorder" label="Закладка на это место" disabled={!s.audioUrl.value} onClick={() => s.markAudio()} />
      <div class="menu-anchor">
        <IconButton icon="more" label="Ещё" onClick={() => setMenu(true)} />
        {menu && (
          <Menu onClose={() => setMenu(false)}>
            <MenuItem icon="bookmark" onClick={() => { setMenu(false); onMarks() }}>Аудиозакладки ({s.audioMarks().length})</MenuItem>
            {onPick && <MenuItem icon="headphones" onClick={() => { setMenu(false); onPick() }}>Другой урок</MenuItem>}
          </Menu>
        )}
      </div>
    </>
  )
}

/** Collapsed text: the previous, current (bold) and next sentence; tap = whole text, double tap left / right = ∓5 s. */
function TextWindow({ s, onOpen }: { s: ReaderSession; onOpen: () => void }) {
  const list = s.sentences.value
  const cur = s.playingSent.value ?? s.sentenceAt(audio.savedPos(s.audioKey)) ?? list[0]?.id
  const i = Math.max(0, list.findIndex((x) => x.id === cur))
  const prevI = useRef(i)
  const dir = i >= prevI.current ? 'up' : 'down'
  useEffect(() => { prevI.current = i }, [i])
  const [flash, setFlash] = useState<{ fwd: boolean; n: number } | null>(null)
  useEffect(() => { if (!flash) return; const t = setTimeout(() => setFlash(null), 650); return () => clearTimeout(t) }, [flash])
  const last = useRef<{ t: number; x: number } | null>(null)
  const timer = useRef(0)
  const onUp = (e: PointerEvent) => {
    const box = (e.currentTarget as HTMLElement).getBoundingClientRect()
    const now = Date.now()
    if (last.current && now - last.current.t < 320) {
      clearTimeout(timer.current); last.current = null
      const fwd = e.clientX - box.left > box.width / 2
      audio.seekBy(fwd ? 5000 : -5000)
      setFlash({ fwd, n: now })
      return
    }
    last.current = { t: now, x: e.clientX }
    timer.current = window.setTimeout(() => { last.current = null; onOpen() }, 300)
  }
  return (
    <div class="text-window" onPointerUp={onUp} style={{ '--fs': prefs.readerFont.value + 'px' } as JSX.CSSProperties}>
      <div class={'tw-lines ' + dir} key={i}>
        {list[i - 1] && <div class="tw-side">{list[i - 1].text}</div>}
        {list[i] && <div class="tw-cur">{list[i].text}</div>}
        {list[i + 1] && <div class="tw-side">{list[i + 1].text}</div>}
      </div>
      {flash && <div class={'tw-flash ' + (flash.fwd ? 'right' : 'left')} key={flash.n}>{flash.fwd ? '5 с »' : '« 5 с'}</div>}
    </div>
  )
}

/** Word gestures in the player text: tap = to the sentence start, double tap = word window, long press = menu / drag = phrase. */
function usePlayerPress(s: ReaderSession) {
  const press = useMemo(() => new Press<WordRef>({
    tap: (r) => s.seekToSentence(refSent(r)),
    double: (r) => { void s.openPopup(r) },
    long: (r) => s.selectStart(r),
    longMove: (e) => {
      const el = document.elementFromPoint(e.clientX, e.clientY)?.closest?.('[data-w]') as HTMLElement | null
      if (el) s.selectTo(el.dataset.w!)
    },
    longEnd: () => s.selectEnd(),
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

/** The whole text; scrolls along with the audio unless the user scrolled in the last 6 s. */
function FullText({ s }: { s: ReaderSession }) {
  const box = useRef<HTMLDivElement>(null)
  const userAt = useRef(0)
  const handlers = usePlayerPress(s)
  const playingId = s.playingSent.value
  useEffect(() => {
    const el = box.current
    if (playingId == null || !el || Date.now() - userAt.current < 6000) return
    const t = el.querySelector(`[data-se="${playingId}"]`) as HTMLElement | null
    if (!t) return
    const r = t.getBoundingClientRect(), b = el.getBoundingClientRect()
    if (r.top < b.top || r.bottom > b.bottom - b.height / 4) el.scrollTo({ top: el.scrollTop + r.top - b.top - 24, behavior: 'smooth' })
  }, [playingId])
  const touched = () => { userAt.current = Date.now() }
  return (
    <div class="player-text reader-text" ref={box} onTouchMove={touched} onWheel={touched} {...handlers}>
      {s.paragraphs.value!.map((p) => <div class="para" key={p[0].id}><SentenceFlow s={s} sents={p} /></div>)}
    </div>
  )
}

/** Long press -> "Перевод предложения": the translation at the bottom above the player. */
function BottomTranslation({ s, id }: { s: ReaderSession; id: number }) {
  return (
    <div class="bottom-tr">
      <span class="grow"><TrText s={s} id={id} /></span>
      <IconButton icon="close" label="Скрыть перевод" onClick={() => { s.bottomSent.value = null }} />
    </div>
  )
}

/** Long press on a word in the player text: 4 items right at the word. */
function PlayerMenuItems({ s, r, close }: { s: ReaderSession; r: WordRef; close: () => void }) {
  const timed = (s.sentence(refSent(r))?.start ?? -1) >= 0
  return (
    <>
      <MenuItem icon="translate" onClick={() => { close(); void s.openPopup(r) }}>Перевод слова</MenuItem>
      <MenuItem icon="chat" onClick={() => { close(); s.bottomSent.value = null; s.toggleSentence(refSent(r)) }}>Перевод предложения</MenuItem>
      {timed && <MenuItem icon="repeat" onClick={() => { close(); s.loopStartAt(refSent(r)) }}>Начало повтора</MenuItem>}
      {timed && <MenuItem icon="repeat" onClick={() => { close(); s.loopEndAt(refSent(r)) }}>Конец повтора</MenuItem>}
    </>
  )
}

/** Player bar: time line, speed, previous/next sentence, ±5 s, play/pause, loop. */
function AudioBar({ s }: { s: ReaderSession }) {
  const mine = audio.audioKey.value === s.audioKey
  const dur = mine ? audio.duration.value : 0
  const pos = mine ? audio.position.value : audio.savedPos(s.audioKey)
  const [drag, setDrag] = useState<number | null>(null)
  const [speedSheet, setSpeedSheet] = useState(false)
  const [loopSheet, setLoopSheet] = useState(false)
  const shown = drag ?? pos
  return (
    <div class="audio-bar">
      <div class="ab-line">
        <span class="ab-time">{fmtTime(shown)}</span>
        <input type="range" min={0} max={dur || 1} step={100} value={shown} disabled={!mine || !dur}
          onInput={(e) => setDrag(Number((e.target as HTMLInputElement).value))}
          onChange={(e) => { audio.seekTo(Number((e.target as HTMLInputElement).value)); setDrag(null) }} />
        <span class="ab-time right">{dur > 0 ? fmtTime(dur) : '–:––'}</span>
      </div>
      {mine && audio.looping.value && (
        <div class="loop-pill">
          <button class="loop-pill-main" onClick={() => setLoopSheet(true)}>
            <Icon name="repeat" size={18} />Повтор {fmtTime(audio.loopA.value)} – {fmtTime(audio.loopB.value)}{audio.repeats.value > 0 ? ` · ${audio.repeats.value + 1}-й раз` : ''}
          </button>
          <button class="loop-pill-stop" aria-label="Остановить повтор" onClick={() => audio.clearLoop()}><Icon name="close" size={18} /></button>
        </div>
      )}
      {mine && audio.queueSize.value > 1 && (
        <div class="ab-queue">
          <button class="btn text" disabled={audio.queueIndex.value === 0} onClick={() => audio.prevTrack()}>‹ Урок</button>
          <span>{audio.queueIndex.value + 1} из {audio.queueSize.value}</span>
          <button class="btn text" disabled={audio.queueIndex.value >= audio.queueSize.value - 1} onClick={() => audio.nextTrack()}>Урок ›</button>
        </div>
      )}
      {mine && audio.audioError.value && <div class="ab-note error-text">{audio.audioError.value}</div>}
      <div class="ab-buttons">
        <button class="btn text ab-speed" onClick={() => setSpeedSheet(true)}>{fmtSpeed(audio.speed.value)}×</button>
        <IconButton icon="skipPrev" label="Предыдущее предложение" disabled={!mine} onClick={() => s.prevSentence()} />
        <IconButton icon="replay" label="Назад на 5 секунд" disabled={!mine} onClick={() => audio.seekBy(-5000)} />
        <button class="ab-play" aria-label={mine && audio.playing.value ? 'Пауза' : 'Слушать'} onClick={() => { audio.unlock(); s.togglePlay() }}>
          <Icon name={mine && audio.playing.value ? 'pause' : 'play'} size={30} />
        </button>
        <IconButton icon="fwd" label="Вперёд на 5 секунд" disabled={!mine} onClick={() => audio.seekBy(5000)} />
        <IconButton icon="skipNext" label="Следующее предложение" disabled={!mine} onClick={() => s.nextSentence()} />
        <IconButton icon="repeat" label="Повтор участка" disabled={!mine} class={mine && audio.looping.value ? 'tonal' : ''} onClick={() => setLoopSheet(true)} />
      </div>
      {speedSheet && <SpeedSheet onClose={() => setSpeedSheet(false)} />}
      {loopSheet && <LoopSheet s={s} onClose={() => setLoopSheet(false)} />}
    </div>
  )
}

/** Speed: slider 0.5–2.0 in steps of 0.05 and presets. */
function SpeedSheet({ onClose }: { onClose: () => void }) {
  const v = audio.speed.value
  return (
    <Sheet onClose={onClose}>
      <div class="sheet-title">Скорость · {fmtSpeed(v)}×</div>
      <input type="range" class="speed-slider" min={0.5} max={2} step={0.05} value={v}
        onInput={(e) => audio.setSpeed(Number((e.target as HTMLInputElement).value))} />
      <div class="chips">
        {[0.5, 0.75, 0.85, 1, 1.1, 1.25, 1.5, 2].map((x) => (
          <Chip key={x} label={fmtSpeed(x) + '×'} selected={Math.abs(v - x) < 0.001} onClick={() => audio.setSpeed(x)} />
        ))}
      </div>
    </Sheet>
  )
}

/** Loop choices: this sentence, the last / next N minutes, own A–B, pause between repeats. */
function LoopSheet({ s, onClose }: { s: ReaderSession; onClose: () => void }) {
  const [back, setBack] = useState(true) // the segment: what just sounded / from here on
  const [own, setOwn] = useState(audio.loopA.value >= 0 && !audio.looping.value)
  const on = audio.looping.value
  const segment = (sec: number) => {
    const ms = sec * 1000
    if (back) { const p = audio.position.value; audio.setLoop(Math.max(0, p - ms), p) } else {
      const p = s.sentenceStartAt(audio.position.value)
      const d = audio.duration.value
      audio.setLoop(p, d > 0 ? Math.min(d, p + ms) : p + ms)
    }
    onClose()
  }
  return (
    <Sheet onClose={onClose}>
      <div class="sheet-title">Повтор</div>
      <div class="loop-sheet">
        {/* what is going on and the main action first: a running loop is stopped with one big button */}
        {on && (
          <div class="loop-now">
            <Icon name="repeat" size={26} />
            <div class="grow">
              <div class="loop-now-title">Повторяется {fmtTime(audio.loopA.value)} – {fmtTime(audio.loopB.value)}</div>
              <div class="loop-now-sub">{audio.repeats.value > 0 ? `${audio.repeats.value + 1}-й раз` : 'первый раз'}</div>
            </div>
          </div>
        )}
        {on && <button class="btn filled big wide" onClick={() => { audio.clearLoop(); onClose() }}><Icon name="close" size={22} />Остановить повтор</button>}

        <button class={'loop-tile' + (on ? '' : ' main')} disabled={!s.hasTimes} onClick={() => { s.loopSentence(); onClose() }}>
          <Icon name="repeat" size={24} />
          <span class="grow"><span class="loop-tile-title">{on ? 'Повторять текущее предложение' : 'Повторять это предложение'}</span>
            {!s.hasTimes && <span class="loop-tile-sub">в уроке нет разметки времени</span>}</span>
        </button>

        <div class="loop-label">Отрезок</div>
        <div class="segmented" role="tablist">
          <button role="tab" aria-selected={back} class={back ? 'on' : ''} onClick={() => setBack(true)}>Только что прозвучало</button>
          <button role="tab" aria-selected={!back} class={!back ? 'on' : ''} onClick={() => setBack(false)}>Отсюда вперёд</button>
        </div>
        <div class="loop-durations">
          {[30, 60, 120, 300].map((sec) => (
            <button key={sec} class="loop-dur" onClick={() => segment(sec)}>{sec < 60 ? `${sec} с` : `${sec / 60} мин`}</button>
          ))}
        </div>

        <button class="loop-more" aria-expanded={own} onClick={() => setOwn(!own)}>
          <span class="grow">Свой участок A – B</span><Icon name="down" size={20} class={'tr-chevron' + (own ? ' open' : '')} />
        </button>
        {own && (
          <>
            <div class="loop-hint">Нажмите A в начале нужного места и B в конце (по границам предложений).</div>
            <div class="row-buttons">
              <button class={'btn grow ' + (audio.loopA.value >= 0 ? 'tonal' : 'outlined')} onClick={() => s.setLoopA()}>A · {audio.loopA.value >= 0 ? fmtTime(audio.loopA.value) : 'начало'}</button>
              <button class={'btn grow ' + (audio.loopB.value >= 0 ? 'tonal' : 'outlined')} disabled={audio.loopA.value < 0} onClick={() => { s.setLoopB(); if (audio.looping.value) onClose() }}>B · {audio.loopB.value >= 0 ? fmtTime(audio.loopB.value) : 'конец'}</button>
            </div>
          </>
        )}

        <div class="loop-label">Пауза между повторами</div>
        <div class="segmented small">
          {[0, 1000, 2000, 3000, 5000].map((g) => (
            <button key={g} class={audio.gap.value === g ? 'on' : ''} aria-pressed={audio.gap.value === g} onClick={() => audio.setGap(g)}>{g === 0 ? 'нет' : `${g / 1000} с`}</button>
          ))}
        </div>
      </div>
    </Sheet>
  )
}

/** Audio bookmarks of the lesson: tap = listen from there. */
function MarksSheet({ s, onClose }: { s: ReaderSession; onClose: () => void }) {
  const marks = s.audioMarks()
  return (
    <Sheet onClose={onClose}>
      <div class="sheet-title">Аудиозакладки</div>
      {!marks.length ? <p class="empty">Пока нет. Значок закладки вверху плеера отмечает место, которое звучит сейчас.</p> : (
        <div class="marks">
          {marks.map((b) => (
            <div class="mark-row" key={b.ms}>
              <button class="mark-main" onClick={() => { onClose(); openAudioBookmark(b) }}>
                <span class="mark-time">{fmtTime(b.ms!)}</span><span class="mark-text">{b.text || '—'}</span>
              </button>
              <IconButton icon="delete" label="Удалить закладку" onClick={() => s.removeMark(b)} />
            </div>
          ))}
        </div>
      )}
    </Sheet>
  )
}

// --- what to listen to ---

/** Recent lessons with audio or a course; big cards with covers; "play all". */
function AudioLessons({ onPick, onPlayAll }: { onPick: (lang: string, l: Lesson) => void; onPlayAll?: () => void }) {
  const l = lang()
  const [course, setCourse] = useState<Course | null>(null)
  const [courseLessons, setCourseLessons] = useState<Lesson[] | null>(null)
  const [courseError, setCourseError] = useState<string | null>(null)
  useEffect(() => { if (!recent.value) void loadRecent(); void loadCourses() }, [l])
  useEffect(() => {
    if (!course) return
    setCourseLessons(null); setCourseError(null)
    api.lessons(l, course.id).then((r) => setCourseLessons(r.lessons.filter((x) => (x.duration ?? 0) > 0 || x.audio)),
      (e) => setCourseError((e as Error).message))
  }, [course?.id])
  const list = course ? courseLessons : recent.value?.filter((x) => x.audio || (x.duration ?? 0) > 0) ?? null
  const current = player.value?.lesson.id
  const total = (list ?? []).reduce((n, x) => n + (x.duration ?? 0), 0)
  return (
    <div class="audio-lessons">
      <div class="chip-row">
        <Chip label="Недавние" selected={!course} onClick={() => setCourse(null)} />
        {(courses.value ?? []).map((c) => <Chip key={c.id} label={c.title || `Курс ${c.id}`} selected={course?.id === c.id} onClick={() => setCourse(c)} />)}
      </div>
      {courseError ? <p class="error-text pad">Ошибка: {courseError}</p>
        : !list ? <div class="pad">{[0, 1, 2, 3].map((i) => <div class="skeleton card-skel" key={i} />)}</div>
        : !list.length ? (
          <p class="empty">{course ? 'В этом курсе нет уроков с аудио.'
            : 'Среди последних уроков нет уроков с аудио. Выберите курс выше или откройте урок с аудио → ⋮ → «Слушать в плеере».'}</p>
        ) : (
          <>
            <div class="al-head">
              <div class="grow">
                <div class="al-title">{course ? course.title : 'Недавние'}</div>
                <div class="al-sub">{list.length} {plural(list.length, 'урок', 'урока', 'уроков')}{total > 0 ? ` · ${fmtLength(total)}` : ''}</div>
              </div>
              <button class="btn tonal" disabled={queueLoading.value} onClick={() => void playAll(l, list).then(() => onPlayAll?.())}>
                <Icon name="play" size={20} /> {queueLoading.value ? 'Готовлю…' : 'Слушать все'}
              </button>
            </div>
            <div class="al-list">
              {list.map((x) => (
                <button class="al-card" key={x.id} onClick={() => onPick(l, x)}>
                  <Cover url={x.imageUrl} title={x.title || `Урок ${x.id}`} class="al-cover">
                    {current === x.id && <span class="al-badge"><Icon name="headphones" size={16} /></span>}
                  </Cover>
                  <span class="al-text">
                    <span class="al-name">{x.title || `Урок ${x.id}`}</span>
                    {!!x.courseTitle && <span class="al-course">{x.courseTitle}</span>}
                    <span class={'al-len' + (current === x.id ? ' now' : '')}>
                      {[x.duration ? fmtLength(x.duration) : '', current === x.id ? 'сейчас в плеере' : ''].filter(Boolean).join(' · ') || 'аудио'}
                    </span>
                  </span>
                  <Icon name="play" size={28} class="al-play" />
                </button>
              ))}
            </div>
          </>
        )}
    </div>
  )
}
