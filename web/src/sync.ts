// Sync between the iPad (web) and the phone: one JSON file `sync/data.json` on the branch `sync` of the private
// repository, read and written with a GitHub token (Contents: read and write). Port of Android Sync.kt, same format (v 1):
//
// - bookmarks: {"<lessonId>_<sent>": bookmark}, deleted: {"<lessonId>_<sent>": time} — the later event wins;
// - notes: {"<lang>_<lessonId>": {t, pins, words}} — the whole lesson, the later `t` wins;
// - progress: {"<lang>_<lessonId>": {pos, t, read}} — pos by later `t`, read = max;
// - stats: {"<device>": {"<date>": [seconds, words]}} — every device writes only its own part;
// - seen: {"<lang>_<lessonId>": index} — max.
import { signal } from '@preact/signals'
import { prefs } from './settings'
import { bookmarkKey, bookmarks, bookmarksDeleted, replaceAllBookmarks, type Bookmark } from './bookmarks'
import { exportPositions, importPositions } from './progress'
import { exportDays, exportSeen, importStats } from './stats'

const API = 'https://api.github.com/repos/MShemur/lingq-sentence-splitter'
const BRANCH = 'sync'
const PATH = 'sync/data.json'

type Obj = Record<string, any>

function stored(k: string, def: string) { try { return localStorage.getItem(k) ?? def } catch { return def } }
function store(k: string, v: string) { try { localStorage.setItem(k, v) } catch { /* ignore */ } }

/** This device in the stats part ("web-ab12cd"). */
const device = (() => {
  let d = stored('sync.device', '')
  if (!d) { d = 'web-' + Math.random().toString(16).slice(2, 8); store('sync.device', d) }
  return d
})()

export const syncRunning = signal(false)
export const lastSync = signal(Number(stored('sync.last', '0')))
export const syncError = signal<string | null>(null)
let lastTry = 0

/** On start / when the app is left: at most once a minute. */
export function autoSync() {
  if (prefs.syncOn.value !== '1' || !prefs.ghToken.value.trim()) return
  if (Date.now() - lastTry < 60_000) return
  void syncNow()
}

export async function syncNow() {
  if (syncRunning.value) return
  if (!prefs.ghToken.value.trim()) { syncError.value = 'Нет GitHub-токена'; return }
  syncRunning.value = true
  lastTry = Date.now()
  try {
    // two tries: if the phone wrote in between, read again and merge again
    try { await syncOnce() } catch (e) { if (e instanceof Conflict) await syncOnce(); else throw e }
    lastSync.value = Date.now()
    store('sync.last', String(lastSync.value))
    syncError.value = null
  } catch (e) {
    syncError.value = e instanceof Error ? e.message : String(e)
  }
  syncRunning.value = false
}

class Conflict extends Error {}

// --- GitHub ---

async function call(method: string, url: string, body?: Obj): Promise<[number, string]> {
  let r: Response
  try {
    r = await fetch(url, {
      method,
      cache: 'no-store',
      headers: {
        Authorization: `Bearer ${prefs.ghToken.value.trim()}`,
        Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28',
        ...(body ? { 'Content-Type': 'application/json' } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
      keepalive: !!body && JSON.stringify(body).length < 60_000, // may finish after the app was left
    })
  } catch {
    throw new Error('Нет связи с GitHub')
  }
  return [r.status, await r.text()]
}

function fail(code: number, text: string): never {
  let msg = ''
  try { msg = JSON.parse(text).message ?? '' } catch { /* not JSON */ }
  throw new Error(
    code === 401 ? 'GitHub не принял токен'
      : code === 403 || code === 404 ? `У токена нет права записи (Contents: Read and write) — ${msg}`
      : `GitHub: HTTP ${code} ${msg}`,
  )
}

/** The branch for the sync file: made from main the first time. */
async function ensureBranch() {
  const [code] = await call('GET', `${API}/git/ref/heads/${BRANCH}`)
  if (code === 200) return
  const [c2, t2] = await call('GET', `${API}/git/ref/heads/main`)
  if (c2 !== 200) fail(c2, t2)
  const sha = JSON.parse(t2).object.sha
  const [c3, t3] = await call('POST', `${API}/git/refs`, { ref: `refs/heads/${BRANCH}`, sha })
  if ((c3 < 200 || c3 > 299) && c3 !== 422) fail(c3, t3)
}

function fromBase64(b64: string): string {
  const bin = atob(b64.replace(/\s/g, ''))
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
}

function toBase64(s: string): string {
  const bytes = new TextEncoder().encode(s)
  let bin = ''
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000))
  return btoa(bin)
}

/** Remote data and the file's sha (null: no file yet). */
async function download(): Promise<[Obj, string | null]> {
  const [code, text] = await call('GET', `${API}/contents/${PATH}?ref=${BRANCH}`)
  if (code === 404) { await ensureBranch(); return [{}, null] }
  if (code !== 200) fail(code, text)
  const o = JSON.parse(text)
  let b64: string = o.content ?? ''
  if (!b64.trim()) { // over 1 MB: the contents API leaves it out, the blob has it
    const [c2, t2] = await call('GET', `${API}/git/blobs/${o.sha}`)
    if (c2 !== 200) fail(c2, t2)
    b64 = JSON.parse(t2).content
  }
  try { return [JSON.parse(fromBase64(b64)), o.sha] } catch { return [{}, o.sha] }
}

async function upload(data: Obj, sha: string | null) {
  const body: Obj = { message: `sync from ${device} [skip ci]`, content: toBase64(JSON.stringify(data)), branch: BRANCH }
  if (sha) body.sha = sha
  const [code, text] = await call('PUT', `${API}/contents/${PATH}`, body)
  if (code === 409 || (code === 422 && text.includes('sha'))) throw new Conflict()
  if (code < 200 || code > 299) fail(code, text)
}

async function syncOnce() {
  const local = exportLocal()
  const [remote, sha] = await download()
  const merged = merge(local, remote)
  importLocal(merged)
  // written only when something changed (every write is a commit)
  if (canon(merged) !== canon({ ...remote, v: 1 })) await upload(merged, sha)
}

/** JSON with sorted keys, to compare data regardless of key order. */
function canon(x: unknown): string {
  if (Array.isArray(x)) return '[' + x.map(canon).join(',') + ']'
  if (x && typeof x === 'object') {
    return '{' + Object.keys(x).sort().map((k) => JSON.stringify(k) + ':' + canon((x as Obj)[k])).join(',') + '}'
  }
  return JSON.stringify(x)
}

// --- local data ---

const NOTES = 'reader.'

function exportLocal(): Obj {
  const bm: Obj = {}
  for (const b of bookmarks.value) bm[bookmarkKey(b)] = { ...b }
  const notes: Obj = {}
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (!k?.startsWith(NOTES)) continue
      try { const o = JSON.parse(localStorage.getItem(k) ?? ''); notes[k.slice(NOTES.length)] = { t: 0, ...o } } catch { /* skip */ }
    }
  } catch { /* storage blocked */ }
  return {
    v: 1, bookmarks: bm, deleted: { ...bookmarksDeleted }, notes,
    progress: exportPositions(), stats: { [device]: exportDays() }, seen: exportSeen(),
  }
}

function importLocal(m: Obj) {
  const list: Bookmark[] = Object.values(m.bookmarks ?? {}).map((b: any) => ({
    lang: String(b.lang), lessonId: Number(b.lessonId), lessonTitle: String(b.lessonTitle ?? ''), sent: Number(b.sent),
    text: String(b.text ?? ''), time: Number(b.time) || 0, tr: String(b.tr ?? ''),
  }))
  replaceAllBookmarks(list.sort((a, b) => a.time - b.time), m.deleted ?? {})
  // notes: newer lessons overwrite (an open reader picks them up when the lesson is opened again)
  for (const [k, remote] of Object.entries<Obj>(m.notes ?? {})) {
    let localT = -1
    try { const cur = localStorage.getItem(NOTES + k); if (cur) localT = Number(JSON.parse(cur).t) || 0 } catch { /* none */ }
    if ((Number(remote.t) || 0) > localT) store(NOTES + k, JSON.stringify(remote))
  }
  importPositions(m.progress ?? {})
  const others: Obj = {}
  for (const [d, days] of Object.entries(m.stats ?? {})) if (d !== device) others[d] = days
  importStats(others, m.seen ?? {})
}

// --- merge ---

function merge(a: Obj, b: Obj): Obj {
  // bookmarks / deletions: the later event wins
  const bmA: Obj = a.bookmarks ?? {}, bmB: Obj = b.bookmarks ?? {}
  const delA: Obj = a.deleted ?? {}, delB: Obj = b.deleted ?? {}
  const bm: Obj = {}, del: Obj = {}
  const keys = new Set([...Object.keys(bmA), ...Object.keys(bmB), ...Object.keys(delA), ...Object.keys(delB)])
  for (const k of keys) {
    const both = [bmA[k], bmB[k]].filter(Boolean)
    const item = both.length ? { ...both.reduce((x, y) => ((Number(y.time) || 0) > (Number(x.time) || 0) ? y : x)) } : null
    const itemT = item ? Number(item.time) || 0 : -1
    const delT = Math.max(k in delA ? Number(delA[k]) : -1, k in delB ? Number(delB[k]) : -1)
    if (item && itemT >= delT) {
      // keep a translation from either side
      if (!String(item.tr ?? '').trim()) { const w = both.find((x) => String(x.tr ?? '').trim()); if (w) item.tr = w.tr }
      bm[k] = item
    } else if (delT >= 0) del[k] = delT
  }
  // notes: whole lesson, later t
  const notes = mergeBy(a.notes, b.notes, (x, y) => ((Number(y.t) || 0) > (Number(x.t) || 0) ? y : x))
  // progress: position by later t, read = max
  const progress = mergeBy(a.progress, b.progress, (x, y) => ({
    ...((Number(y.t) || 0) > (Number(x.t) || 0) ? y : x),
    read: Math.max(Number(x.read) || 0, Number(y.read) || 0),
  }))
  // stats: one part per device; this device's own part is the freshest here
  const stats = { ...(b.stats ?? {}), ...(a.stats ?? {}) }
  // seen: max
  const seen: Obj = {}
  for (const s of [a.seen ?? {}, b.seen ?? {}]) for (const [k, v] of Object.entries(s)) seen[k] = Math.max(seen[k] ?? -1, Number(v))
  return { v: 1, bookmarks: bm, deleted: del, notes, progress, stats, seen }
}

function mergeBy(a: Obj | undefined, b: Obj | undefined, pick: (x: Obj, y: Obj) => Obj): Obj {
  const out: Obj = { ...(a ?? {}) }
  for (const [k, y] of Object.entries<Obj>(b ?? {})) out[k] = out[k] ? pick(out[k], y) : y
  return out
}
