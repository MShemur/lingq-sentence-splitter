// Podcast player state: the episode in the player (its audio is the audio engine's track, key "pod_..."), its
// transcript sentences (kept on the device, IndexedDB) and the status of a running transcription.
import { signal } from '@preact/signals'
import * as audio from './audio'
import { go, stack } from './app'
import type { Episode, Podcast } from './importer/podcasts'

/** One sentence of the transcript: text and its place in the audio (ms). */
export type PodSent = { text: string; start: number; end: number }

export type PodEpisode = {
  key: string; feedUrl: string; feedTitle: string; cover: string; guid: string; title: string; audio: string
  date?: string; durationS?: number
}

/** Episode in the player, null = none. */
export const podNow = signal<PodEpisode | null>(null)
/** Transcript sentences shown in the player ([] = none yet). */
export const podSents = signal<PodSent[]>([])
/** '' or the progress text of a running transcription. */
export const podStatus = signal('')

/** The feed's own data of an episode (for "Загрузить как урок"); kept while the app runs. */
export interface PodSource { ep: Episode; pod: Podcast }
const sources = new Map<string, PodSource>()
export const podSourceOf = (key: string): PodSource | null => sources.get(key) ?? null

/** Whether an audio key belongs to a podcast episode (not "<lang>_<lessonId>"). */
export const isPodKey = (k: string | null | undefined): boolean => !!k && k.startsWith('pod_')

/**
 * Key of an episode: 'pod_' + a 64-bit hash of the feed and the guid. The 'x' keeps it from looking like a lesson key
 * ("<lang>_<digits>") to the lesson code.
 */
export function podKey(feedUrl: string, guid: string): string {
  const s = feedUrl + '\n' + guid
  let a = 0x811c9dc5, b = 0x9e3779b9
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    a = Math.imul(a ^ c, 0x01000193)
    b = Math.imul(b ^ c, 0x85ebca6b)
  }
  return 'pod_x' + (a >>> 0).toString(36) + (b >>> 0).toString(36)
}

/** The player's episode from a feed item and its podcast. */
export function podEpisodeOf(ep: Episode, pod: Podcast): PodEpisode {
  const guid = ep.guid || ep.audio
  return {
    key: podKey(pod.feed, guid), feedUrl: pod.feed, feedTitle: pod.title || pod.feed, cover: pod.image, guid,
    title: ep.title || 'Выпуск', audio: ep.audio,
    date: ep.date ? new Date(ep.date).toISOString() : undefined,
    durationS: ep.duration > 0 ? ep.duration : undefined,
  }
}

/** Index of the sentence sounding at [ms] (the last one that has begun), -1 before the first. [sents] sorted by start. */
export function sentIndexAt(sents: PodSent[], ms: number): number {
  let lo = 0, hi = sents.length - 1, r = -1
  while (lo <= hi) {
    const m = (lo + hi) >> 1
    if (sents[m].start <= ms) { r = m; lo = m + 1 } else hi = m - 1
  }
  return r
}

/**
 * Plays [ep] from where it was left and opens the podcast player. Must be called inside the tap (unlocks the audio on
 * iPad / iPhone). [src] keeps the feed item, for "Загрузить как урок".
 */
export function playEpisode(ep: PodEpisode, src?: PodSource): void {
  audio.unlock()
  if (src) sources.set(ep.key, src)
  const changed = podNow.value?.key !== ep.key
  podNow.value = ep
  if (changed) {
    podSents.value = []
    podStatus.value = ''
    void loadTranscript(ep.key).then(
      (s) => { if (podNow.value?.key === ep.key) podSents.value = s ?? [] },
      () => { if (podNow.value?.key === ep.key) podSents.value = [] },
    )
  }
  audio.load({ key: ep.key, url: ep.audio, title: ep.title, image: ep.cover, artist: ep.feedTitle }, audio.savedPos(ep.key))
  audio.play()
  if (stack.value.at(-1)?.k !== 'podPlayer') go({ k: 'podPlayer' })
}

// --- transcripts on the device: IndexedDB 'satzloop-pod', store 'tr' (key = episode key) ---

let dbp: Promise<IDBDatabase> | null = null

function db(): Promise<IDBDatabase> {
  if (!dbp) {
    dbp = new Promise((res, rej) => {
      const r = indexedDB.open('satzloop-pod', 1)
      r.onupgradeneeded = () => { r.result.createObjectStore('tr') }
      r.onsuccess = () => res(r.result)
      r.onerror = () => { dbp = null; rej(r.error) }
    })
  }
  return dbp
}

async function tx<T>(mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  const d = await db()
  return new Promise<T>((res, rej) => {
    const t = d.transaction('tr', mode)
    const r = f(t.objectStore('tr'))
    t.oncomplete = () => res(r.result as T)
    t.onerror = () => rej(t.error)
    t.onabort = () => rej(t.error)
  })
}

/** Keeps the transcript of an episode; the player shows it at once if that episode is in it. */
export async function saveTranscript(key: string, sents: PodSent[]): Promise<void> {
  await tx('readwrite', (s) => s.put(sents, key))
  if (podNow.value?.key === key) podSents.value = sents
}

/** The saved transcript of an episode, null = none. */
export async function loadTranscript(key: string): Promise<PodSent[] | null> {
  const v = await tx<PodSent[] | undefined>('readonly', (s) => s.get(key))
  return v ?? null
}
