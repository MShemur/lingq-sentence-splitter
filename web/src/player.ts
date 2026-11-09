// The player tab's state: which lesson it shows, "play all" queues, audio bookmarks. Port of the player part of AppState.kt.
import { signal } from '@preact/signals'
import * as api from './lingq'
import type { Lesson } from './lingq'
import * as audio from './audio'
import { ReaderSession } from './reader/session'
import { say, stack, type Screen } from './app'
import type { Bookmark } from './bookmarks'
import { log, logError } from './applog'

/** The lesson in the player tab (its own session: pins, translations), null = nothing chosen. */
export const player = signal<ReaderSession | null>(null)

function session(lang: string, l: Lesson) {
  const s = new ReaderSession(lang, l)
  s.player = true
  return s
}

/** Bottom tabs of the main screen: Библиотека / Плеер. */
export function switchTab(k: 'courses' | 'player') {
  const list = stack.value
  if (list.length === 1 && list[0].k === k) return
  stack.value = [{ k } as Screen]
  const depth = typeof history.state?.depth === 'number' ? history.state.depth : 0
  if (depth > 0) try { history.go(-depth) } catch { /* ignore */ }
  log('nav', `Вкладка ${k === 'player' ? 'Плеер' : 'Библиотека'}`)
}

/** Listen to a lesson in the player tab (starts playing when its audio is known). */
export function playLesson(lang: string, l: Lesson) {
  audio.unlock()
  const cur = player.value
  if (!cur || cur.lesson.id !== l.id || cur.lang !== lang) {
    const s = session(lang, l)
    s.autoPlay = true
    player.value = s
    // the address is known already: start at once (still inside the tap)
    if (l.audio) { s.autoPlay = false; s.togglePlay() }
  } else if (audio.audioKey.value !== cur.audioKey || !audio.playing.value) cur.togglePlay()
  switchTab('player')
}

/** "Слушать все": lessons are being prepared (their audio addresses fetched). */
export const queueLoading = signal(false)
const queueLessons = new Map<string, [string, Lesson]>()

/** Plays [lessons] one after another (the player shows the text of the one sounding). */
export async function playAll(lang: string, lessons: Lesson[]) {
  if (!lessons.length || queueLoading.value) return
  audio.unlock()
  queueLoading.value = true
  try {
    const ready: Lesson[] = []
    for (let i = 0; i < lessons.length; i += 4) {
      const part = await Promise.all(lessons.slice(i, i + 4).map(async (l) => {
        if (l.audio) return l
        try { const f = await api.lesson(lang, l.id); return { ...l, audio: f.audio, imageUrl: l.imageUrl || f.imageUrl } } catch { return l }
      }))
      ready.push(...part.filter((l) => l.audio))
    }
    if (!ready.length) { say('У этих уроков нет аудио'); return }
    queueLessons.clear()
    for (const l of ready) queueLessons.set(`${lang}_${l.id}`, [lang, l])
    const first = ready[0]
    const cur = player.value
    if (!cur || cur.lesson.id !== first.id || cur.lang !== lang) player.value = session(lang, first)
    audio.setOnTrack(onTrack)
    audio.loadQueue(ready.map((l) => ({ key: `${lang}_${l.id}`, url: l.audio!, title: l.title, image: l.imageUrl ?? '' })),
      audio.savedPos(`${lang}_${first.id}`))
    audio.play()
    switchTab('player')
  } catch (e) {
    logError('audio', 'Слушать все', e)
    say((e as Error).message || 'Не удалось собрать очередь')
  } finally {
    queueLoading.value = false
  }
}

/** The queue went on to the next lesson: the player shows its text. */
function onTrack(k: string) {
  const v = queueLessons.get(k)
  if (!v) return
  if (player.value?.audioKey !== k) { const s = session(v[0], v[1]); player.value = s; void s.load() }
}

/** Audio bookmark: the player tab, from that place. */
export const openAudioBookmark = (b: Bookmark) => listenAt(b.lang, { id: b.lessonId, title: b.lessonTitle }, b.ms ?? 0)

/** The player tab, lesson [l] from [ms]. */
export function listenAt(lang: string, l: Lesson, ms: number) {
  audio.unlock()
  const cur = player.value
  if (cur && cur.lesson.id === l.id && cur.lang === lang && cur.audioUrl.value) {
    if (audio.audioKey.value === cur.audioKey) { audio.seekTo(ms); audio.play() } else { cur.startAt = ms; cur.togglePlay() }
  } else {
    const s = session(lang, l)
    s.startAt = ms
    player.value = s
    if (l.audio) s.togglePlay() // still inside the tap
    else s.autoPlay = true
  }
  switchTab('player')
}
