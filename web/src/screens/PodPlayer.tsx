// The podcast player: the episode's cover and title, the transcript (when there is one; the sentence sounding is
// highlighted, a tap seeks to it) or the "Run AI" button, and the bar with play / seek / speed. Shown as its own
// screen (from the mini player, the podcast list) and in the Player tab while a podcast episode sounds.
import { useEffect, useRef, useState } from 'preact/hooks'
import type { JSX } from 'preact'
import { effect } from '@preact/signals'
import * as audio from '../audio'
import { fmtSpeed, fmtTime } from '../audio'
import { back, say } from '../app'
import { lang, prefs } from '../settings'
import { MainTabs, SpeedSheet } from './Player'
import { EpisodeSheet } from './Jobs'
import { startJob, type BgJob } from '../importer/jobs'
import { PodcastSession } from '../importer/podcasts'
import { ImportSession } from '../importer/session'
import { loadTranscript, podNow, podSents, podStatus, podSourceOf, playEpisode, sentIndexAt, type PodEpisode, type PodSent } from '../podplay'
import { aiProgress, aiRunKey, runAi } from '../podai'
import { IconButton, Menu, MenuItem, TopBar } from '../ui'
import { Icon } from '../icons'
import { Cover } from './Library'
import './PodPlayer.css'

/** The podcast player. [tab]: shown in the Player tab (bottom tabs, no back button). */
export function PodPlayerScreen({ tab }: { tab?: boolean }) {
  const ep = podNow.value
  const sents = podSents.value
  const status = podStatus.value
  const running = !!ep && aiRunKey.value === ep.key
  const [menu, setMenu] = useState(false)
  const [job, setJob] = useState<BgJob | null>(null)
  const nav = tab ? undefined : <IconButton icon="back" label="Назад" onClick={back} />
  if (!ep) {
    return (
      <div class="screen pod-player">
        <TopBar nav={nav} title="Подкаст" />
        <div class="screen-body scroll"><p class="empty">Сейчас ничего не играет. Выберите выпуск в подкасте.</p></div>
        {tab && <MainTabs at="player" />}
      </div>
    )
  }
  const loadAsLesson = async () => {
    setMenu(false)
    const src = podSourceOf(ep.key)
    if (!src) { say('Выпуск не найден в ленте. Откройте его из подкаста.'); return }
    // a transcript already saved for this episode is used instead of recognising it again
    const saved = await loadTranscript(ep.key).catch(() => null)
    setJob(startJob(src.ep, src.pod, lang(), saved))
  }
  const openPodcast = () => {
    setMenu(false)
    const s = new PodcastSession(new ImportSession(), lang())
    s.openFeed({ title: ep.feedTitle, author: '', feed: ep.feedUrl, image: ep.cover })
  }
  const actions = (
    <div class="menu-anchor">
      <IconButton icon="more" label="Ещё" onClick={() => setMenu(true)} />
      {menu && (
        <Menu onClose={() => setMenu(false)}>
          <MenuItem icon="upload" onClick={loadAsLesson}>Загрузить как урок</MenuItem>
          <MenuItem icon="podcast" onClick={openPodcast}>Открыть подкаст</MenuItem>
        </Menu>
      )}
    </div>
  )
  return (
    <div class="screen pod-player">
      <TopBar nav={nav} title={ep.feedTitle} actions={actions} />
      <div class="screen-body scroll pp-body">
        <PodHead ep={ep} />
        {(running || status) && (
          <div class="pp-status-top">
            {status && <span>{status}</span>}
            {running && <div class="pp-thin"><i style={{ width: `${Math.round(aiProgress.value * 100)}%` }} /></div>}
          </div>
        )}
        {sents.length
          ? <PodText key={ep.key} sents={sents} epKey={ep.key} />
          : (
            <div class="pp-run">
              <button class="pp-run-btn" disabled={running} onClick={() => runAi()}>{running ? 'Расшифровка…' : 'Run AI'}</button>
            </div>
          )}
      </div>
      <PodBar ep={ep} sents={sents} />
      {tab && <MainTabs at="player" />}
      {job && <EpisodeSheet j={job} onClose={() => setJob(null)} />}
    </div>
  )
}

function PodHead({ ep }: { ep: PodEpisode }) {
  return (
    <div class="pp-head">
      <Cover url={ep.cover} title={ep.feedTitle || ep.title} class="pp-cover" />
      <h2 class="pp-title">{ep.title}</h2>
      <div class="pp-podcast">{ep.feedTitle}</div>
    </div>
  )
}

/**
 * The transcript: one line per sentence. The sentence sounding is marked and kept in view (unless the user scrolled in
 * the last 6 s); a tap seeks to its start. Only the marks change on the audio's ticks, not the sentences.
 */
function PodText({ sents, epKey }: { sents: PodSent[]; epKey: string }) {
  const box = useRef<HTMLDivElement>(null)
  const userAt = useRef(0)
  const lastI = useRef(-2)
  useEffect(() => {
    lastI.current = -2 // new sentences: their marks are set again
    return effect(() => {
      const el = box.current
      if (!el) return
      const i = audio.audioKey.value === epKey ? sentIndexAt(sents, audio.position.value) : -1
      if (i === lastI.current) return
      lastI.current = i
      el.querySelectorAll('.pp-sent.sounding').forEach((x) => x.classList.remove('sounding'))
      const t = el.children[i] as HTMLElement | undefined
      if (!t) return
      t.classList.add('sounding')
      if (Date.now() - userAt.current < 6000) return
      t.scrollIntoView({ block: 'center', behavior: 'smooth' })
    })
  }, [sents, epKey])
  const touched = () => { userAt.current = Date.now() }
  return (
    <div class="pp-text" ref={box} onTouchMove={touched} onWheel={touched}
      style={{ '--fs': prefs.readerFont.value + 'px' } as JSX.CSSProperties}>
      {sents.map((x, i) => (
        <span class="pp-sent" key={i} onClick={() => audio.seekTo(x.start)}>{x.text} </span>
      ))}
    </div>
  )
}

/** Time line, speed, previous / next sentence, back 15 s, forward 30 s, play / pause. */
function PodBar({ ep, sents }: { ep: PodEpisode; sents: PodSent[] }) {
  const mine = audio.audioKey.value === ep.key
  const dur = mine ? audio.duration.value : 0
  const pos = mine ? audio.position.value : audio.savedPos(ep.key)
  const [drag, setDrag] = useState<number | null>(null)
  const [speedSheet, setSpeedSheet] = useState(false)
  const shown = drag ?? pos
  const total = dur > 0 ? dur : (ep.durationS ?? 0) * 1000
  const cur = sentIndexAt(sents, shown)
  const prevSentence = () => {
    if (cur < 0) return
    // a sentence sounding for a while: back to its start; right after its start: the one before
    const s = sents[cur]
    audio.seekTo(shown - s.start > 2000 ? s.start : (sents[cur - 1]?.start ?? 0))
  }
  const nextSentence = () => { const n = sents[cur + 1]; if (n) audio.seekTo(n.start) }
  return (
    <div class="audio-bar pp-bar">
      <div class="ab-line">
        <span class="ab-time">{fmtTime(shown)}</span>
        <input type="range" min={0} max={dur || 1} step={100} value={shown} disabled={!mine || !dur}
          onInput={(e) => setDrag(Number((e.target as HTMLInputElement).value))}
          onChange={(e) => { audio.seekTo(Number((e.target as HTMLInputElement).value)); setDrag(null) }} />
        <span class="ab-time right">{total > 0 ? fmtTime(total) : '–:––'}</span>
      </div>
      {mine && audio.audioError.value && <div class="ab-note error-text">{audio.audioError.value}</div>}
      <div class="ab-buttons">
        <button class="btn text ab-speed" onClick={() => setSpeedSheet(true)}>{fmtSpeed(audio.speed.value)}×</button>
        <IconButton icon="skipPrev" label="Предыдущее предложение" disabled={!mine || !sents.length} onClick={prevSentence} />
        <IconButton icon="replay" label="Назад на 15 секунд" disabled={!mine} onClick={() => audio.seekBy(-15000)} />
        <button class="ab-play" aria-label={mine && audio.playing.value ? 'Пауза' : 'Слушать'}
          onClick={() => { audio.unlock(); if (mine) audio.toggle(); else playEpisode(ep, podSourceOf(ep.key) ?? undefined) }}>
          <Icon name={mine && audio.playing.value ? 'pause' : 'play'} size={30} />
        </button>
        <IconButton icon="fwd" label="Вперёд на 30 секунд" disabled={!mine} onClick={() => audio.seekBy(30000)} />
        <IconButton icon="skipNext" label="Следующее предложение" disabled={!mine || !sents.length} onClick={nextSentence} />
      </div>
      {speedSheet && <SpeedSheet onClose={() => setSpeedSheet(false)} />}
    </div>
  )
}

