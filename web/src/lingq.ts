// LingQ REST API through the same-origin proxy (/api -> https://www.lingq.com/api). Port of LingqApi.kt.
import { prefs } from './settings'

export class ApiError extends Error {}

export interface Course { id: number; title: string; imageUrl?: string }
/** [imageUrl] '' when LingQ has only its stock picture; [words] 0 when unknown. */
export interface Lesson {
  id: number; title: string; imageUrl?: string; level?: string; words?: number; courseTitle?: string
  /** When the lesson was last opened (ms; 0 / missing = unknown). */
  opened?: number
}
export interface Language { code: string; title: string }

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Absolute LingQ URLs (pagination "next") go through the proxy too. */
const local = (url: string) => url.replace(/^https?:\/\/www\.lingq\.com/, '')

export async function call(method: string, url: string, body?: unknown, key = prefs.apiKey.value.trim()): Promise<any> {
  if (!key) throw new ApiError('Не задан API-ключ LingQ (⚙ Настройки)')
  for (let attempt = 0; ; attempt++) {
    let r: Response
    try {
      r = await fetch(local(url), {
        method,
        headers: {
          Authorization: `Token ${key}`,
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
      })
    } catch {
      throw new ApiError('Нет связи с сервером')
    }
    if ((r.status === 429 || r.status >= 500) && attempt < 4) {
      const ra = Number(r.headers.get('Retry-After'))
      await sleep(1000 * (ra > 0 ? ra : 5 * (attempt + 1)))
      continue
    }
    const text = await r.text()
    if (r.status === 401) throw new ApiError('LingQ не принял API-ключ (HTTP 401)')
    if (!r.ok) throw new ApiError(`LingQ: HTTP ${r.status} ${text.slice(0, 200)}`)
    return text ? JSON.parse(text) : null
  }
}

async function pages(first: string, key?: string): Promise<any[]> {
  const out: any[] = []
  let url: string | null = first
  while (url) {
    const d: any = await call('GET', url, undefined, key)
    out.push(...(d?.results ?? []))
    url = d?.next ?? null
  }
  return out
}

export async function languages(key?: string): Promise<Language[]> {
  return (await pages('/api/v2/contexts/', key))
    .filter((c) => c.language)
    .map((c) => ({ code: c.language.code, title: c.language.title ?? c.language.code }))
}

export async function myCourses(lang: string): Promise<Course[]> {
  return (await pages(`/api/v3/${lang}/collections/my/?page=1&page_size=100&sort=recentUsed`))
    .map((c) => ({ id: c.id, title: c.title ?? '', imageUrl: image(c.imageUrl) }))
}

/** LingQ's stock picture for content without one counts as none (a coloured tile is drawn instead). */
const image = (u: unknown) => (typeof u === 'string' && u && !u.includes('/default-content') ? u : '')

const lessonOf = (o: any): Lesson => ({
  id: o.id, title: o.title ?? '', imageUrl: image(o.imageUrl), level: o.level ?? '',
  words: Number(o.wordCount) || 0, courseTitle: o.collectionTitle ?? '',
})

/** Lessons opened lately (LingQ's "my lessons" shelf, most recent first). */
export async function recentLessons(lang: string, count = 20): Promise<Lesson[]> {
  const d = await call('GET', `/api/v3/${lang}/search/?shelf=my_lessons&sortBy=recentlyOpened&page=1&page_size=${count}`)
  return (d?.results ?? []).map((o: any) => ({ ...lessonOf(o), opened: parseTime(o.openDate) }))
}

/** ISO time from LingQ (with or without a zone; none = UTC) -> ms, 0 if missing. */
function parseTime(s: unknown): number {
  if (typeof s !== 'string' || !s) return 0
  const t = Date.parse(/[zZ]|[+-]\d\d:?\d\d$/.test(s) ? s : s + 'Z')
  return Number.isFinite(t) ? t : 0
}

/** Course title (may be empty) and its lessons in course order. */
export async function lessons(lang: string, course: number): Promise<{ title: string; lessons: Lesson[] }> {
  const all = await pages(`/api/v3/${lang}/collections/${course}/lessons/?page=1&page_size=100&sortBy=pos`)
  return { title: all[0]?.collectionTitle ?? '', lessons: all.map(lessonOf) }
}

export async function lesson(lang: string, id: number): Promise<Lesson> {
  const d = await call('GET', `/api/v3/${lang}/lessons/${id}/`)
  return { id, title: d?.title ?? '', imageUrl: image(d?.imageUrl), courseTitle: d?.collectionTitle ?? '' }
}

/** Lesson as the web editor sees it: paragraphs -> sentences (text, translations, index ...). */
export async function editor(lang: string, id: number): Promise<any[]> {
  return (await call('GET', `/api/v3/${lang}/lessons/${id}/editor/`))?.paragraphs ?? []
}

/** Saves the sentence structure exactly as sent (LingQ does not re-split it). */
export async function saveParagraphs(lang: string, id: number, paragraphs: unknown[]) {
  await call('PATCH', `/api/v3/${lang}/lessons/${id}/`, { paragraphs })
}

/** Creates a new (private) course. */
export async function createCourse(lang: string, title: string): Promise<Course> {
  const d = await call('POST', `/api/v3/${lang}/collections/`, { title: title.slice(0, 250), description: '', level: 1 })
  return { id: d.id, title: d.title ?? title, imageUrl: '' }
}

/** Creates a private lesson in [course]; returns its id. */
export async function importLesson(lang: string, course: number, title: string, text: string): Promise<number> {
  const d = await call('POST', `/api/v3/${lang}/lessons/import/`, { title: title.slice(0, 250), text, collection: course, status: 'private', save: true })
  return d.id
}

/**
 * After an import LingQ tokenizes the text for a while (lesson status "I", GET answers HTTP 400 "locked") and then
 * re-splits the sentences, overwriting anything saved before. Wait until done.
 */
export async function waitProcessed(lang: string, id: number, timeoutMs = 180_000) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    let status = 'locked'
    try { status = String((await call('GET', `/api/v3/${lang}/lessons/${id}/`))?.status ?? '') } catch { /* locked */ }
    if (status !== 'I' && status !== 'locked') return
    await sleep(3000)
  }
  throw new ApiError(`LingQ слишком долго обрабатывает урок ${id} — проверьте его позже`)
}

/** Popular user meanings of a word (most popular first), as in LingQ's word pop-up. */
export async function hints(lang: string, term: string, locale: string): Promise<string[]> {
  const q = new URLSearchParams({ term, locale })
  const a: any[] = (await call('GET', `/api/v2/${lang}/hints/search/?${q}`)) ?? []
  const out = new Map<string, string>()
  for (const o of a) {
    if (o.flagged) continue
    const t = String(o.text ?? '').trim()
    if (t && !out.has(t.toLowerCase())) out.set(t.toLowerCase(), t)
  }
  return [...out.values()]
}

/** Machine translation (LingQ's translate service). */
export async function translate(lang: string, target: string, text: string): Promise<string> {
  const d = await call('POST', `/api/v2/${lang}/translate/`, { source: lang, target, text })
  return d?.translations?.[0]?.translatedText ?? ''
}

/**
 * LingQ's "retranslate sentence" (ChatGPT): translates sentence [index] (1-based, as in the lesson)
 * again, stores it in the lesson and returns the new translations by sentence index.
 */
export async function retranslate(lang: string, lessonId: number, index: number, target: string): Promise<Map<number, string>> {
  const a: any[] = (await call('POST', `/api/v3/${lang}/lessons/${lessonId}/translation/`,
    { sentence: index, language: target, method: 'chatgpt', sentence_bulk: 1 })) ?? []
  const out = new Map<number, string>()
  for (const o of a) for (const t of o.translations ?? []) if (t.language === target) out.set(o.index, t.text)
  return out
}
