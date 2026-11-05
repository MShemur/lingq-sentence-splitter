// Google Gemini API, called straight from the browser (it allows CORS). Port of Gemini.kt.
import { ApiError } from './lingq'
import { DEFAULT_MODEL, prefs } from './settings'
import { fill } from './prompts'

const API = 'https://generativelanguage.googleapis.com/v1beta'

export interface Msg { user: boolean; text: string }
export interface WordMeaning { best: string; options: string[]; note: string }

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Models this key may use for text generation (for the picker in settings). */
export async function models(key: string): Promise<string[]> {
  if (!key.trim()) throw new ApiError('Не задан ключ Gemini')
  const out: string[] = []
  let token = ''
  do {
    const r = await fetch(`${API}/models?pageSize=200${token ? '&pageToken=' + token : ''}`, { headers: { 'x-goog-api-key': key.trim() } })
    const text = await r.text()
    if (!r.ok) throw new ApiError(`Gemini: HTTP ${r.status} ${text.slice(0, 200)}`)
    const o = JSON.parse(text)
    for (const m of o.models ?? []) {
      const name = String(m.name ?? '').replace(/^models\//, '')
      if ((m.supportedGenerationMethods ?? []).includes('generateContent') && name.startsWith('gemini')) out.push(name)
    }
    token = o.nextPageToken ?? ''
  } while (token)
  return out.sort().reverse()
}

export async function generate(system: string | null, messages: Msg[], json = false): Promise<string> {
  const key = prefs.geminiKey.value.trim()
  if (!key) throw new ApiError('Не задан ключ Gemini (⚙ Настройки)')
  const m = prefs.geminiModel.value.trim() || DEFAULT_MODEL
  try {
    return await request(key, m, system, messages, json, true)
  } catch (e) {
    // older/newer models may not know the thinking option: try once without it
    const msg = String((e as Error).message)
    if (msg.includes('HTTP 400') && /hink/i.test(msg)) return request(key, m, system, messages, json, false)
    throw e
  }
}

async function request(key: string, m: string, system: string | null, messages: Msg[], json: boolean, lowThinking: boolean): Promise<string> {
  const body: any = {}
  if (system?.trim()) body.systemInstruction = { parts: [{ text: system }] }
  body.contents = messages.map((x) => ({ role: x.user ? 'user' : 'model', parts: [{ text: x.text }] }))
  const cfg: any = { temperature: 0.3 }
  if (json) cfg.responseMimeType = 'application/json'
  // Flash models answer much faster with little "thinking"
  if (lowThinking && m.includes('flash')) cfg.thinkingConfig = m.startsWith('gemini-2') ? { thinkingBudget: 0 } : { thinkingLevel: 'minimal' }
  body.generationConfig = cfg

  for (let attempt = 0; ; attempt++) {
    let r: Response
    try {
      r = await fetch(`${API}/models/${m}:generateContent`, {
        method: 'POST',
        headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    } catch {
      throw new ApiError('Нет связи с Gemini')
    }
    if ((r.status === 429 || r.status >= 500) && attempt < 3) { await sleep(2000 * (attempt + 1)); continue }
    const text = await r.text()
    if (!r.ok) {
      let msg: string | undefined
      try { msg = JSON.parse(text).error.message } catch { /* not JSON */ }
      throw new ApiError(`Gemini: HTTP ${r.status} ${msg ?? text.slice(0, 200)}`)
    }
    const parts = JSON.parse(text).candidates?.[0]?.content?.parts
    if (!parts) throw new ApiError('Gemini не дал ответа')
    return parts.map((p: any) => p.text ?? '').join('').trim()
  }
}

export async function word(prompt: string, lang: string, target: string, w: string, sentence: string): Promise<WordMeaning> {
  const raw = await generate(null, [{ user: true, text: fill(prompt, { lang, target, word: w, sentence }) }], true)
  try {
    const o = JSON.parse(raw.slice(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
    const options = Array.isArray(o.options) ? o.options.map(String).filter((x: string) => x.trim()) : []
    return { best: String(o.best ?? ''), options, note: String(o.note ?? '') }
  } catch {
    return { best: raw.split('\n')[0].slice(0, 60), options: [], note: '' }
  }
}

export const retranslate = (prompt: string, lang: string, target: string, sentence: string, current: string) =>
  generate(null, [{ user: true, text: fill(prompt, { lang, target, sentence, current }) }])

export const sentence = (prompt: string, lang: string, target: string, s: string) =>
  generate(null, [{ user: true, text: fill(prompt, { lang, target, sentence: s }) }])
