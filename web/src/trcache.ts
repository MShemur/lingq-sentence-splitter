// Sentence translations made by the AI / machine translation (and "translate again"), kept in this browser per lesson
// and target language: sentence text -> translation. Applied when the lesson opens, so they are not asked for again.
// LingQ's stored translations are not kept here, nor errors; not synced. Port of Android TranslationCache (Reader.kt).

type Map_ = Record<string, string>

let dbp: Promise<IDBDatabase> | null = null
function db(): Promise<IDBDatabase> {
  dbp ??= new Promise((ok, fail) => {
    const r = indexedDB.open('satzloop-tr', 1)
    r.onupgradeneeded = () => { r.result.createObjectStore('tr') }
    r.onsuccess = () => ok(r.result)
    r.onerror = () => fail(r.error)
  })
  return dbp
}

export const trKey = (lang: string, id: number, target: string) => `${lang}_${id}_${target}`

export async function loadTr(key: string): Promise<Map_> {
  try {
    const d = await db()
    return await new Promise((ok) => {
      const req = d.transaction('tr', 'readonly').objectStore('tr').get(key)
      req.onsuccess = () => ok((req.result as Map_ | undefined) ?? {})
      req.onerror = () => ok({})
    })
  } catch { return {} }
}

const pending = new Map<string, Map_>()
let timer = 0

/** Remembers [tr] for sentence [text] of lesson [key] (written a moment later, several at once). */
export function rememberTr(key: string, all: Map_) {
  pending.set(key, all)
  clearTimeout(timer)
  timer = window.setTimeout(flush, 1500)
}

async function flush() {
  if (!pending.size) return
  const list = [...pending]
  pending.clear()
  try {
    const d = await db()
    const s = d.transaction('tr', 'readwrite').objectStore('tr')
    for (const [k, v] of list) s.put(v, k)
  } catch { /* storage blocked: translations are just asked for again */ }
}

window.addEventListener('pagehide', () => { void flush() })
