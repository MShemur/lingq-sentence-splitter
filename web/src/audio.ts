// The lesson audio: one <audio> element for the whole app (keeps playing outside the player tab; lock screen and
// headphone controls through the Media Session API). Loops a stretch A–B for drilling, with a pause between repeats.
// Port of Android AudioEngine (Audio.kt).
import { computed, effect, signal } from '@preact/signals'
import { log } from './applog'
import { setListening, tickTime } from './stats'

/** One lesson of a "play all" queue; [key] = "<lang>_<lessonId>". */
export interface Track { key: string; url: string; title: string; image: string }

/** "<lang>_<lessonId>" of the loaded audio, null = nothing loaded. */
export const audioKey = signal<string | null>(null)
export const audioTitle = signal('')
/** Cover of the lesson playing ('' = none). */
export const audioImage = signal('')
export const playing = signal(false)
export const buffering = signal(false)
export const position = signal(0)
export const duration = signal(0)
export const speed = signal(num('audio.speed', 1))
export const audioError = signal<string | null>(null)
/** Loop A–B in ms (both set and B > A = looping); repeats done; pause between repeats in ms. */
export const loopA = signal(-1)
export const loopB = signal(-1)
export const repeats = signal(0)
export const gap = signal(num('audio.gap', 0))
export const looping = computed(() => loopA.value >= 0 && loopB.value > loopA.value)
/** Lessons in the queue and the one playing (1 = a single lesson). */
export const queueSize = signal(1)
export const queueIndex = signal(0)

/** Called when the queue moves on to the lesson with this key. */
let onTrack: ((key: string) => void) | null = null
export const setOnTrack = (f: ((key: string) => void) | null) => { onTrack = f }

function num(k: string, d: number) { const v = Number(localStorage.getItem(k)); return Number.isFinite(v) && localStorage.getItem(k) != null ? v : d }
function store(k: string, v: string) { try { localStorage.setItem(k, v) } catch { /* ignore */ } }

// --- positions per lesson ---

function loadPos(): Record<string, number> { try { return JSON.parse(localStorage.getItem('audio.pos') ?? '{}') } catch { return {} } }
let saved = loadPos()
export const savedPos = (k: string) => saved[k] ?? 0
function savePos(k: string, ms: number) { saved = { ...saved, [k]: Math.round(ms) }; store('audio.pos', JSON.stringify(saved)) }

// --- the element ---

let el: HTMLAudioElement | null = null
let queue: Track[] = []
/** Seek to apply once the metadata is there (iOS ignores an earlier one). */
let pending = -1
let waitingGap = false
let gapTimer = 0
let ticker = 0
let lastSave = 0
let lastTick = 0
let listened = 0
/** The element was started inside a user gesture, so later play() calls are allowed (iOS). */
let unlocked = false

const SILENCE = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA='

function get(): HTMLAudioElement {
  if (el) return el
  const a = new Audio()
  a.preload = 'auto'
  a.addEventListener('playing', () => { if (a.src === SILENCE) return; playing.value = true; buffering.value = false; startTicker() })
  a.addEventListener('play', () => { if (a.src === SILENCE) return; playing.value = true; startTicker() })
  a.addEventListener('pause', () => { if (a.src === SILENCE) return; playing.value = false; save(); stopTicker() })
  a.addEventListener('waiting', () => { buffering.value = true })
  a.addEventListener('canplay', () => { buffering.value = false })
  a.addEventListener('loadedmetadata', () => {
    if (a.src === SILENCE) return
    if (Number.isFinite(a.duration)) duration.value = Math.round(a.duration * 1000)
    if (pending >= 0) { a.currentTime = pending / 1000; pending = -1 }
    a.playbackRate = speed.value
  })
  a.addEventListener('durationchange', () => { if (a.src !== SILENCE && Number.isFinite(a.duration)) duration.value = Math.round(a.duration * 1000) })
  a.addEventListener('ratechange', () => { if (a.playbackRate !== speed.value && a.src !== SILENCE) a.playbackRate = speed.value })
  a.addEventListener('ended', () => {
    if (a.src === SILENCE) return
    position.value = duration.value
    // a loop that reaches the very end (the whole lesson): the end came before the ticker saw it
    if (looping.value) { repeats.value++; seekTo(loopA.value); play(); return }
    log('audio', 'Конец аудио')
    if (queueIndex.value < queue.length - 1) switchTrack(queueIndex.value + 1, true)
  })
  a.addEventListener('error', () => {
    if (!a.src || a.src === SILENCE) return
    const code = a.error?.code
    audioError.value = `Аудио не загрузилось${code ? ` (код ${code})` : ''}`
    log('audio', `Ошибка плеера: ${audioError.value}`)
  })
  el = a
  mediaSession()
  return a
}

/** Inside a tap: lets the element start later on its own (after the lesson is loaded) on iPad / iPhone. */
export function unlock() {
  if (unlocked) return
  const a = get()
  unlocked = true
  if (a.src && a.src !== SILENCE) return
  a.src = SILENCE
  a.play().then(() => a.pause(), () => { unlocked = false })
}

function setSrc(t: Track, startMs: number) {
  const a = get()
  save()
  audioKey.value = t.key
  audioTitle.value = t.title
  audioImage.value = t.image
  audioError.value = null
  clearLoop()
  duration.value = 0
  position.value = startMs
  pending = startMs > 0 ? startMs : -1
  a.src = t.url
  a.playbackRate = speed.value
  a.load()
  metadata()
}

/** Loads the audio of a lesson (if it is not the one loaded), at [startMs]. */
export function load(t: Track, startMs: number) {
  if (audioKey.value === t.key) return
  queue = [t]; queueSize.value = 1; queueIndex.value = 0
  setSrc(t, startMs)
  log('audio', `Загружено аудио урока ${t.key} с ${fmtTime(startMs)}`)
}

/** "Слушать все": the lessons one after another, the first from [startMs]. */
export function loadQueue(tracks: Track[], startMs: number) {
  if (!tracks.length) return
  queue = tracks; queueSize.value = tracks.length; queueIndex.value = 0
  audioKey.value = null
  setSrc(tracks[0], startMs)
  log('audio', `Очередь: ${tracks.length} уроков`)
}

function switchTrack(i: number, finished: boolean) {
  const t = queue[i]
  if (!t) return
  const old = audioKey.value
  if (old && finished) savePos(old, 0) // finished: next time from the start
  queueIndex.value = i
  setSrc(t, finished ? 0 : savedPos(t.key))
  log('audio', `Дальше в очереди: ${t.key}`)
  onTrack?.(t.key)
  play()
}

export function nextTrack() { if (queueIndex.value < queue.length - 1) switchTrack(queueIndex.value + 1, false) }
export function prevTrack() { if (queueIndex.value > 0) switchTrack(queueIndex.value - 1, false) }

export function play() {
  const a = el
  if (!a || !audioKey.value) return
  waitingGap = false
  if (a.ended) a.currentTime = 0
  a.play().catch((e) => {
    if ((e as Error).name === 'AbortError') return
    audioError.value = (e as Error).name === 'NotAllowedError' ? 'Нажмите ▶, чтобы слушать' : `Аудио: ${(e as Error).message}`
    playing.value = false
  })
}

export function pause() { waitingGap = false; clearTimeout(gapTimer); el?.pause() }
export function toggle() { if (playing.value || waitingGap) pause(); else play() }

const now = () => (el && pending < 0 ? Math.round(el.currentTime * 1000) : position.value)

export function seekTo(ms: number) {
  const a = el
  if (!a) return
  const t = Math.max(0, duration.value > 0 ? Math.min(ms, duration.value) : ms)
  if (a.readyState >= 1) { a.currentTime = t / 1000; pending = -1 } else pending = t
  position.value = t
  updatePositionState()
}

export const seekBy = (ms: number) => seekTo(now() + ms)

export function setSpeed(v: number) {
  speed.value = Math.round(v * 100) / 100
  if (el) el.playbackRate = speed.value
  store('audio.speed', String(speed.value))
}

export function setGap(ms: number) { gap.value = ms; store('audio.gap', String(ms)) }

/** Repeats [a]..[b]; [stay]: keep playing from where it is (the whole lesson), else start at [a]. */
export function setLoop(a: number, b: number, stay = false) {
  loopA.value = Math.max(0, a); loopB.value = b; repeats.value = 0
  log('audio', `Повтор ${fmtTime(loopA.value)}–${fmtTime(loopB.value)}`)
  if (!stay) seekTo(loopA.value)
  play()
}

export function setA(a: number) { loopA.value = Math.max(0, a); if (loopB.value <= loopA.value) loopB.value = -1; repeats.value = 0 }

export function setB(b: number) {
  if (loopA.value < 0 || b <= loopA.value) loopA.value = -1
  loopB.value = b; repeats.value = 0
  if (looping.value) { log('audio', `Повтор ${fmtTime(loopA.value)}–${fmtTime(loopB.value)}`); seekTo(loopA.value); play() }
}

export function clearLoop() { loopA.value = -1; loopB.value = -1; repeats.value = 0; waitingGap = false; clearTimeout(gapTimer) }

/** Stop and unload. */
export function stop() {
  save()
  pause()
  if (el) { el.removeAttribute('src'); el.load() }
  audioKey.value = null; queue = []; queueSize.value = 1; queueIndex.value = 0
  clearLoop()
  playing.value = false
  if ('mediaSession' in navigator) navigator.mediaSession.metadata = null
  log('audio', 'Аудио остановлено')
}

// --- while playing: position for the screen, the loop, listening time ---

// 50 ms only while a loop must turn exactly at its end, else 250 ms; the position on screen changes ~4 times a
// second either way (the bar, the mini player and the bold sentence follow it)
let tickMs = 250
let shownAt = 0
const tickEvery = () => (looping.value ? 50 : 250)

function startTicker() {
  if (ticker) return
  lastTick = Date.now()
  tickMs = tickEvery()
  ticker = window.setInterval(tick, tickMs)
}

function stopTicker() { clearInterval(ticker); ticker = 0 }

effect(() => setListening(playing.value))
effect(() => { const ms = tickEvery(); if (ticker && ms !== tickMs) { stopTicker(); startTicker() } })

function tick() {
  const a = el
  if (!a) return
  const pos = now()
  const t0 = Date.now()
  if (t0 - shownAt >= 240 || Math.abs(pos - position.value) > 1500) { position.value = pos; shownAt = t0 }
  if (looping.value && !waitingGap && (pos >= loopB.value || pos < loopA.value - 1500)) {
    repeats.value++
    if (gap.value > 0) {
      waitingGap = true
      a.pause()
      gapTimer = window.setTimeout(() => { waitingGap = false; seekTo(loopA.value); a.play().catch(() => {}) }, gap.value)
    } else seekTo(loopA.value)
  }
  const t = Date.now()
  if (!a.paused) {
    listened += Math.min(1000, t - lastTick)
    if (listened >= 15_000) { listened -= 15_000; tickTime(true) } // listening counts as study time (as audio)
  }
  lastTick = t
  if (t - lastSave > 5000) { save(); updatePositionState() }
}

function save() {
  const k = audioKey.value
  if (!k || !el || el.src === SILENCE) return
  lastSave = Date.now()
  savePos(k, now())
}

window.addEventListener('pagehide', save)

// --- lock screen / headphones ---

function metadata() {
  if (!('mediaSession' in navigator)) return
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: audioTitle.value, artist: 'SatzLoop',
      artwork: audioImage.value ? [{ src: audioImage.value, sizes: '512x512' }] : [{ src: '/icon-512.png', sizes: '512x512', type: 'image/png' }],
    })
  } catch { /* old browser */ }
}

function updatePositionState() {
  if (!('mediaSession' in navigator) || !el || !(duration.value > 0)) return
  try {
    navigator.mediaSession.setPositionState({
      duration: duration.value / 1000, playbackRate: speed.value, position: Math.min(now(), duration.value) / 1000,
    })
  } catch { /* ignore */ }
}

function mediaSession() {
  if (!('mediaSession' in navigator)) return
  const ms = navigator.mediaSession
  const set = (a: MediaSessionAction, h: MediaSessionActionHandler) => { try { ms.setActionHandler(a, h) } catch { /* unsupported */ } }
  set('play', () => play())
  set('pause', () => pause())
  set('seekbackward', () => seekBy(-5000))
  set('seekforward', () => seekBy(5000))
  // headphone / lock-screen ⏮ ⏭: 5 s back / forward (as on Android; the previous sentence rewound to the start)
  set('previoustrack', () => seekBy(-5000))
  set('nexttrack', () => seekBy(5000))
  set('seekto', (d) => { if (d.seekTime != null) seekTo(d.seekTime * 1000) })
}

export function fmtTime(ms: number) {
  const s = Math.floor(Math.max(0, ms) / 1000)
  const two = (n: number) => String(n).padStart(2, '0')
  return s >= 3600 ? `${Math.floor(s / 3600)}:${two(Math.floor(s / 60) % 60)}:${two(s % 60)}` : `${Math.floor(s / 60)}:${two(s % 60)}`
}

/** 1 -> "1.0", 0.85 -> "0.85". */
export const fmtSpeed = (v: number) => (Number.isInteger(v) ? v.toFixed(1) : String(Math.round(v * 100) / 100))

/** Length of a lesson in seconds -> "12 мин" / "1 ч 20 мин". */
export function fmtLength(sec: number) {
  const m = Math.round(sec / 60)
  return m < 60 ? `${m} мин` : `${Math.floor(m / 60)} ч ${m % 60} мин`
}
