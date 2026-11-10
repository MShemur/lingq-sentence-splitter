// Podcasts: search (Apple Podcasts catalogue) or an RSS link, episodes, download; then the audio import takes over.
// Port of Android Podcasts.kt. Feeds, audio, transcripts and covers come through this site's /fetch proxy.
import { signal } from '@preact/signals'
import { htmlToText, unescape } from './sources'
import type { TSeg } from './stt'
import type { ImportSession } from './session'
import { backTo, go } from '../app'
import { log } from '../applog'

export interface Podcast { title: string; author: string; feed: string; image: string }
export interface Episode {
  title: string; date: number; audio: string; type: string; duration: number
  description: string; transcript: string; transcriptType: string
}

const via = (url: string) => `/fetch?url=${encodeURIComponent(url)}`

async function get(url: string, what: string, signal?: AbortSignal): Promise<Response> {
  let r: Response
  try { r = await fetch(via(url), { signal }) } catch (e) {
    if ((e as Error).name === 'AbortError') throw e
    throw new Error(`${what}: нет связи`)
  }
  if (!r.ok) throw new Error(`${what}: HTTP ${r.status}`)
  return r
}

const COUNTRY: Record<string, string> = { de: 'DE', fr: 'FR', es: 'ES', it: 'IT', nl: 'NL', pt: 'PT', ru: 'RU' }

/** Apple Podcasts catalogue: free, no key. */
export async function search(term: string, lang: string): Promise<Podcast[]> {
  const q = new URLSearchParams({ media: 'podcast', entity: 'podcast', limit: '40', country: COUNTRY[lang] ?? 'US', term })
  const d = await (await get(`https://itunes.apple.com/search?${q}`, 'поиск')).json()
  return (d?.results ?? []).filter((o: any) => o.feedUrl).map((o: any) => ({
    title: String(o.collectionName ?? ''), author: String(o.artistName ?? ''), feed: String(o.feedUrl),
    image: String(o.artworkUrl600 || o.artworkUrl100 || ''),
  }))
}

/** Transcripts with times are useful; plain text / HTML are not. */
const trRank = (t: string) => (/vtt|srt|subrip/.test(t) ? 3 : t.includes('json') ? 2 : 0)

/** "1:02:03", "62:03" or seconds. */
function duration(s: string): number {
  const p = s.trim().split(':').map(Number)
  if (!s.trim() || p.some((x) => !Number.isFinite(x))) return 0
  return Math.floor(p.reduce((a, v) => a * 60 + v, 0))
}

export async function feed(url: string): Promise<[Podcast, Episode[]]> {
  const xml = await (await get(url, 'лента')).text()
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  const channel = [...doc.getElementsByTagName('*')].find((e) => e.localName === 'channel')
  if (!channel || doc.getElementsByTagName('parsererror').length) throw new Error('это не лента подкаста')
  const kids = (e: Element, name: string) => [...e.children].filter((c) => c.localName === name)
  const txt = (e: Element, name: string) => kids(e, name).map((c) => c.textContent?.trim() ?? '').find(Boolean) ?? ''
  const image = kids(channel, 'image').map((c) => c.getAttribute('href') || txt(c, 'url')).find(Boolean) ?? ''
  const eps: Episode[] = []
  for (const it of kids(channel, 'item')) {
    const enc = kids(it, 'enclosure')[0]
    const audio = enc?.getAttribute('url') ?? ''
    if (!audio) continue
    let tr = '', trType = ''
    for (const t of kids(it, 'transcript')) {
      const ty = (t.getAttribute('type') ?? '').toLowerCase()
      if (trRank(ty) > trRank(trType)) { tr = t.getAttribute('url') ?? ''; trType = ty }
    }
    const date = Date.parse(txt(it, 'pubDate'))
    eps.push({
      title: txt(it, 'title'), date: Number.isFinite(date) ? date : 0, audio, type: enc.getAttribute('type') ?? '',
      duration: duration(txt(it, 'duration')),
      description: htmlToText(txt(it, 'description') || txt(it, 'summary') || txt(it, 'encoded')).replace(/\s+/g, ' ').slice(0, 400),
      transcript: tr, transcriptType: trType,
    })
  }
  const title = txt(channel, 'title')
  if (!title && !eps.length) throw new Error('это не лента подкаста')
  return [{ title, author: txt(channel, 'author'), feed: url, image }, eps.sort((a, b) => b.date - a.date)]
}

/** Downloads [url]; [progress] gets (bytes done, total or -1). */
export async function download(url: string, progress: (done: number, total: number) => void, abort: AbortSignal): Promise<Blob> {
  const r = await get(url, 'скачивание', abort)
  const total = Number(r.headers.get('content-length')) || -1
  const type = r.headers.get('content-type') ?? ''
  if (!r.body) return r.blob()
  const reader = r.body.getReader()
  const parts: Uint8Array[] = []
  let done = 0, last = 0
  for (;;) {
    const { value, done: end } = await reader.read()
    if (end) break
    parts.push(value)
    done += value.length
    if (done - last > 256_000) { last = done; progress(done, total) }
  }
  progress(done, total)
  return new Blob(parts as BlobPart[], { type })
}

/** A small file (cover picture); null on any error. */
export async function bytes(url: string): Promise<Blob | null> {
  try { const b = await (await get(url, 'обложка')).blob(); return b.size ? b : null } catch { return null }
}

/** "01:02:03.500", "02:03,500" -> s (-1 if unreadable). */
function clockSec(s: string): number {
  const p = s.replace(',', '.').split(':').map(Number)
  return p.some((x) => !Number.isFinite(x)) ? -1 : p.reduce((a, v) => a * 60 + v, 0)
}

/** A podcast transcript (WebVTT, SRT or Podcasting 2.0 JSON) as timed pieces. */
export async function transcript(url: string, type: string): Promise<TSeg[]> {
  const text = await (await get(url, 'расшифровка')).text()
  if (type.includes('json') || text.trimStart().startsWith('{')) {
    return (JSON.parse(text)?.segments ?? []).map((o: any) => ({
      text: String(o.body ?? '').trim(), start: Number(o.startTime) || 0, end: Number(o.endTime) || 0,
    })).filter((s: TSeg) => s.text)
  }
  // VTT / SRT: "00:01:02.500 --> 00:01:05.000" then text lines up to a blank line
  const out: TSeg[] = []
  const lines = text.replace(/\r/g, '').split('\n')
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].includes('-->')) continue
    const [a, b] = lines[i].split('-->').map((x) => clockSec(x.trim().split(' ')[0]))
    let sb = ''
    for (i++; i < lines.length && lines[i].trim(); i++) sb += lines[i].trim() + ' '
    const t = sb.replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
    if (t && a >= 0) out.push({ text: unescape(t), start: a, end: Math.max(a, b) })
  }
  return out
}

// --- subscriptions (this device only) ---

export interface Sub { podcast: Podcast; seen: number }

function loadSubs(): Sub[] {
  try { const a = JSON.parse(localStorage.getItem('podcasts.subs') ?? '[]'); return Array.isArray(a) ? a : [] } catch { return [] }
}
export const subs = signal<Sub[]>(loadSubs())
function saveSubs(l: Sub[]) {
  subs.value = l
  try { localStorage.setItem('podcasts.subs', JSON.stringify(l)) } catch { /* ignore */ }
}
export const isSub = (feed: string) => subs.value.some((s) => s.podcast.feed === feed)
export function addSub(p: Podcast, seen: number) { if (!isSub(p.feed)) saveSubs([...subs.value, { podcast: p, seen }]) }
export function removeSub(feed: string) { saveSubs(subs.value.filter((s) => s.podcast.feed !== feed)) }
export function markSeen(feed: string, t: number) {
  saveSubs(subs.value.map((s) => (s.podcast.feed === feed && t > s.seen ? { ...s, seen: t } : s)))
}

/** File extension of an episode's audio. */
export function episodeExt(ep: Episode): string {
  const e = ep.audio.split('?')[0].split('/').pop()?.split('.').pop()?.toLowerCase() ?? ''
  if (['mp3', 'm4a', 'aac', 'ogg', 'opus', 'mp4', 'wav', 'flac'].includes(e)) return e
  return /mp4|m4a/.test(ep.type) ? 'm4a' : ep.type.includes('ogg') ? 'ogg' : 'mp3'
}


// --- screens' state ---

/** Search, subscriptions, the open feed and the download of an episode; [imp] takes the audio afterwards. */
export class PodcastSession {
  readonly k = 'podcasts'
  query = signal('')
  results = signal<Podcast[] | null>(null)
  busy = signal(false)
  error = signal<string | null>(null)
  /** New episodes per subscribed feed (filled in the background). */
  newCount = signal<Record<string, number>>({})
  // the open feed
  podcast = signal<Podcast | null>(null)
  episodes = signal<Episode[] | null>(null)
  feedError = signal<string | null>(null)
  /** Episodes newer than this were not seen before opening the feed. */
  seenBefore = Infinity
  // download
  downloading = signal<Episode | null>(null)
  progress = signal(0)
  progressText = signal('')
  private abort: AbortController | null = null

  constructor(readonly imp: ImportSession, readonly lang: string) {}

  async search() {
    const q = this.query.value.trim()
    if (!q) { this.results.value = null; return }
    if (/^https?:/i.test(q)) { this.openFeed({ title: '', author: '', feed: q, image: '' }); return }
    this.busy.value = true; this.error.value = null
    try { this.results.value = await search(q, this.lang) } catch (e) {
      log('podcast', `Поиск ✗ ${(e as Error).message}`); this.error.value = `Поиск не удался: ${(e as Error).message}`
    }
    this.busy.value = false
  }

  refreshNew() {
    const todo = subs.value.filter((s) => !(s.podcast.feed in this.newCount.value))
    let i = 0
    const next = async (): Promise<void> => {
      const s = todo[i++]
      if (!s) return
      try {
        const [, eps] = await feed(s.podcast.feed)
        this.newCount.value = { ...this.newCount.value, [s.podcast.feed]: eps.filter((e) => e.date > s.seen).length }
      } catch { /* shown as no badge */ }
      return next()
    }
    for (let k = 0; k < 3; k++) void next()
  }

  openFeed(p: Podcast) {
    this.podcast.value = p; this.episodes.value = null; this.feedError.value = null
    this.seenBefore = subs.value.find((s) => s.podcast.feed === p.feed)?.seen ?? Infinity
    go({ k: 'podcastFeed', s: this })
    void (async () => {
      try {
        const [info, eps] = await feed(p.feed)
        this.podcast.value = { title: info.title || p.title, author: info.author || p.author, feed: p.feed, image: info.image || p.image }
        this.episodes.value = eps
        if (isSub(p.feed)) {
          markSeen(p.feed, Math.max(0, ...eps.map((e) => e.date)))
          this.newCount.value = { ...this.newCount.value, [p.feed]: 0 }
        }
      } catch (e) {
        log('podcast', `Лента ${p.feed} ✗ ${(e as Error).message}`)
        this.feedError.value = `Не удалось открыть ленту: ${(e as Error).message}`
      }
    })()
  }

  toggleSub() {
    const p = this.podcast.value
    if (!p) return
    if (isSub(p.feed)) removeSub(p.feed)
    else addSub(p, Math.max(0, ...(this.episodes.value ?? []).map((e) => e.date)))
  }

  cancel() { this.abort?.abort(); this.abort = null; this.downloading.value = null }

  /** Downloads the episode (and its transcript and the cover), then the audio import asks which part to take. */
  async pick(ep: Episode) {
    if (this.downloading.value) return
    this.downloading.value = ep; this.progress.value = 0; this.progressText.value = 'Скачиваю…'
    this.feedError.value = null
    const pod = this.podcast.value
    const abort = (this.abort = new AbortController())
    try {
      const mb = (n: number) => Math.round(n / 1_000_000)
      const blob = await download(ep.audio, (done, total) => {
        this.progress.value = total > 0 ? done / total : 0
        this.progressText.value = `Скачиваю: ${mb(done)} из ${total > 0 ? mb(total) : '?'} МБ`
      }, abort.signal)
      let tr: TSeg[] | null = null
      if (ep.transcript) {
        try { tr = await transcript(ep.transcript, ep.transcriptType) } catch (e) { log('podcast', `Расшифровка ✗ ${(e as Error).message}`) }
        if (!tr?.length) tr = null
      }
      const cover = pod?.image ? await bytes(pod.image) : null
      log('podcast', `«${ep.title.slice(0, 60)}»: ${mb(blob.size)} МБ, расшифровка: ${tr ? 'есть' : 'нет'}`)
      this.downloading.value = null
      await backTo((s) => s === this.imp)
      await this.imp.pickAudio(blob, `${ep.title || 'Выпуск'}.${episodeExt(ep)}`, tr)
      this.imp.setCover(cover, pod?.title ?? '') // after pickAudio, which clears the old cover
    } catch (e) {
      if ((e as Error).name === 'AbortError') return
      log('podcast', `Скачивание ✗ ${(e as Error).message}`)
      this.feedError.value = `Не удалось скачать: ${(e as Error).message}`
      this.downloading.value = null
    }
  }
}
