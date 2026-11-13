// Podcast subscriptions, synced with the phone as "podcasts": {feedUrl: {title, author, image, seen, t}} or
// {del: true, t} for a removed one; t = when added / removed (ms, 0 = before sync knew it). Android PodcastSubs.
import { signal } from '@preact/signals'
import type { Podcast } from './podcasts'

export interface Sub { podcast: Podcast; seen: number; t?: number }

function loadSubs(): Sub[] {
  try { const a = JSON.parse(localStorage.getItem('podcasts.subs') ?? '[]'); return Array.isArray(a) ? a : [] } catch { return [] }
}
function loadRemoved(): Record<string, number> {
  try { return JSON.parse(localStorage.getItem('podcasts.removed') ?? '{}') ?? {} } catch { return {} }
}
export const subs = signal<Sub[]>(loadSubs())
let removed = loadRemoved()

function saveSubs(l: Sub[]) {
  subs.value = l
  try { localStorage.setItem('podcasts.subs', JSON.stringify(l)) } catch { /* ignore */ }
}
function saveRemoved() {
  try { localStorage.setItem('podcasts.removed', JSON.stringify(removed)) } catch { /* ignore */ }
}

export const isSub = (feed: string) => subs.value.some((s) => s.podcast.feed === feed)
export function addSub(p: Podcast, seen: number) {
  if (isSub(p.feed)) return
  delete removed[p.feed]; saveRemoved()
  saveSubs([...subs.value, { podcast: p, seen, t: Date.now() }])
}
export function removeSub(feed: string) {
  removed = { ...removed, [feed]: Date.now() }; saveRemoved()
  saveSubs(subs.value.filter((s) => s.podcast.feed !== feed))
}
export function markSeen(feed: string, t: number) {
  saveSubs(subs.value.map((s) => (s.podcast.feed === feed && t > s.seen ? { ...s, seen: t } : s)))
}

export function exportSubs(): Record<string, unknown> {
  const o: Record<string, unknown> = {}
  for (const s of subs.value) {
    o[s.podcast.feed] = { title: s.podcast.title, author: s.podcast.author, image: s.podcast.image, seen: s.seen, t: s.t ?? 0 }
  }
  for (const [f, t] of Object.entries(removed)) if (!(f in o)) o[f] = { del: true, t }
  return o
}

/** The merged list from sync replaces the local one (local order first, new ones at the end). */
export function importSubs(o: Record<string, any>) {
  const mine = new Map(subs.value.map((s) => [s.podcast.feed, s]))
  const order = [...mine.keys()]
  const keys = Object.keys(o).sort((a, b) => {
    const i = order.indexOf(a), j = order.indexOf(b)
    return (i < 0 ? Infinity : i) - (j < 0 ? Infinity : j)
  })
  const out: Sub[] = []
  const rm: Record<string, number> = {}
  for (const f of keys) {
    const x = o[f] ?? {}
    if (x.del) { rm[f] = Number(x.t) || 0; continue }
    const m = mine.get(f)
    out.push({
      podcast: { title: String(x.title || m?.podcast.title || ''), author: String(x.author ?? ''), feed: f, image: String(x.image ?? '') },
      seen: Math.max(Number(x.seen) || 0, m?.seen ?? 0), t: Number(x.t) || 0,
    })
  }
  removed = rm; saveRemoved()
  if (JSON.stringify(out) !== JSON.stringify(subs.value)) saveSubs(out)
}

/** Merge of two "podcasts" objects: per feed the later add / remove wins; "seen" = max. */
export function mergeSubs(a: Record<string, any> | undefined, b: Record<string, any> | undefined): Record<string, any> {
  const out: Record<string, any> = { ...(a ?? {}) }
  for (const [k, y] of Object.entries(b ?? {})) {
    const x = out[k]
    if (!x) { out[k] = y; continue }
    const newer = (Number(y.t) || 0) > (Number(x.t) || 0) ? y : x
    out[k] = newer.del ? newer : { ...newer, seen: Math.max(Number(x.seen) || 0, Number(y.seen) || 0) }
  }
  return out
}

// --- "В LingQ": the course used last time for a podcast ---

export function courseFor(feed: string): number | null {
  try { const o = JSON.parse(localStorage.getItem('podcasts.course') ?? '{}'); return Number(o[feed]) || null } catch { return null }
}
export function lastPodcastCourse(): number | null {
  try { return Number(localStorage.getItem('podcasts.lastCourse')) || null } catch { return null }
}
export function rememberCourse(feed: string, id: number) {
  try {
    const o = JSON.parse(localStorage.getItem('podcasts.course') ?? '{}')
    if (feed) o[feed] = id
    localStorage.setItem('podcasts.course', JSON.stringify(o))
    localStorage.setItem('podcasts.lastCourse', String(id))
  } catch { /* ignore */ }
}
