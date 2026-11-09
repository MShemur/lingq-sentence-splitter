// Journal of the last actions (screens, taps, requests, errors) for bug reports: kept in this browser for 48 hours /
// 3000 lines. Never contains keys or tokens (only hosts, paths and status codes). Port of Android AppLog.
import { signal } from '@preact/signals'

declare const __VERSION__: string

const KEY = 'applog'
const KEEP_MS = 48 * 3600_000
const MAX = 3000

type Line = [number, string]

let lines: Line[] = load()
let saveTimer = 0

/** Changes on every new line (the journal screen refreshes). */
export const logVersion = signal(0)

function load(): Line[] {
  try {
    const a = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    const limit = Date.now() - KEEP_MS
    return Array.isArray(a) ? a.filter((l) => Array.isArray(l) && l[0] >= limit).slice(-MAX) : []
  } catch { return [] }
}

function save() {
  clearTimeout(saveTimer)
  saveTimer = 0
  try { localStorage.setItem(KEY, JSON.stringify(lines)) } catch {
    // storage full: keep the newer half
    lines = lines.slice(-Math.floor(lines.length / 2))
    try { localStorage.setItem(KEY, JSON.stringify(lines)) } catch { /* give up */ }
  }
}

const two = (n: number, w = 2) => String(n).padStart(w, '0')
function stamp(t: number) {
  const d = new Date(t)
  return `${two(d.getDate())}.${two(d.getMonth() + 1)} ${two(d.getHours())}:${two(d.getMinutes())}:${two(d.getSeconds())}.${two(d.getMilliseconds(), 3)}`
}

export function log(tag: string, msg: string) {
  const now = Date.now()
  lines.push([now, `${stamp(now)} [${tag}] ${msg.replace(/[\n\t]/g, ' ').slice(0, 500)}`])
  const limit = now - KEEP_MS
  while (lines.length && (lines[0][0] < limit || lines.length > MAX)) lines.shift()
  logVersion.value++
  if (!saveTimer) saveTimer = window.setTimeout(save, 1000)
}

export function logError(tag: string, msg: string, e: unknown) {
  const err = e instanceof Error ? `${e.name}: ${e.message}` : String(e)
  log(tag, `${msg}: ${err.slice(0, 300)}`)
}

/** Path of a URL without the query (no keys ever get into the journal). */
export const shortUrl = (url: string) => ('/' + url.split('?')[0].replace(/^[a-z]+:\/\/[^/]*/i, '').replace(/^\/+/, '')).slice(0, 160)

/** The whole journal with a header (version, browser) — what is copied. */
export function logText(): string {
  return `SatzLoop web ${__VERSION__}, ${navigator.userAgent}\nЖурнал за последние 48 ч (${lines.length} строк)\n\n` +
    lines.map((l) => l[1]).join('\n')
}

export const lastLines = (n: number) => lines.slice(-n).map((l) => l[1])

export function clearLog() {
  lines = []
  logVersion.value++
  save()
}

// written when the page is left (the timer may not get to run)
window.addEventListener('pagehide', () => { if (saveTimer) save() })
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden' && saveTimer) save() })
