// LingQ REST API through the same-origin proxy (/api -> https://www.lingq.com/api). Port of LingqApi.kt.
import { prefs } from './settings'

export class ApiError extends Error {}

export interface Course { id: number; title: string }
export interface Lesson { id: number; title: string }
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
    .map((c) => ({ id: c.id, title: c.title ?? '' }))
}

/** Course title (may be empty) and its lessons in course order. */
export async function lessons(lang: string, course: number): Promise<{ title: string; lessons: Lesson[] }> {
  const all = await pages(`/api/v3/${lang}/collections/${course}/lessons/?page=1&page_size=100&sortBy=pos`)
  return { title: all[0]?.collectionTitle ?? '', lessons: all.map((l) => ({ id: l.id, title: l.title ?? '' })) }
}

export async function lesson(lang: string, id: number): Promise<Lesson> {
  const d = await call('GET', `/api/v3/${lang}/lessons/${id}/`)
  return { id, title: d?.title ?? '' }
}

/** Lesson as the web editor sees it: paragraphs -> sentences (text, translations, index ...). */
export async function editor(lang: string, id: number): Promise<any[]> {
  return (await call('GET', `/api/v3/${lang}/lessons/${id}/editor/`))?.paragraphs ?? []
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
