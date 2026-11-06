// Any service with the OpenAI chat API: DeepSeek, Qwen (DashScope), OpenRouter, Groq, Mistral…
// Called straight from the browser (all presets allow CORS). Port of OpenAiCompat in Android Llm.kt.
import { ApiError } from './lingq'
import { prefs } from './settings'
import type { Msg } from './gemini'

/** Ready addresses of OpenAI-compatible services. */
export const AI_PRESETS: [string, string][] = [
  ['DeepSeek', 'https://api.deepseek.com'],
  ['Qwen', 'https://dashscope-intl.aliyuncs.com/compatible-mode/v1'],
  ['OpenRouter', 'https://openrouter.ai/api/v1'],
  ['Groq', 'https://api.groq.com/openai/v1'],
  ['Mistral', 'https://api.mistral.ai/v1'],
]

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const base = () => prefs.aiBase.value.trim().replace(/\/+$/, '')

function check() {
  if (!base()) throw new ApiError('Не задан адрес сервиса ИИ (⚙ Настройки → Перевод)')
  if (!prefs.aiKey.value.trim()) throw new ApiError('Не задан ключ сервиса ИИ (⚙ Настройки → Перевод)')
}

async function send(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch {
    throw new ApiError('Нет связи с сервисом ИИ (адрес верный?)')
  }
}

/** Models of this key that can write text (for the pickers). */
export async function models(): Promise<string[]> {
  check()
  const r = await send(`${base()}/models`, { headers: { Authorization: `Bearer ${prefs.aiKey.value.trim()}` } })
  const text = await r.text()
  if (!r.ok) {
    let msg: string | undefined
    try { msg = JSON.parse(text).error.message } catch { /* not JSON */ }
    throw new ApiError(`ИИ: HTTP ${r.status} ${msg ?? text.slice(0, 200)}`)
  }
  const data: any[] = JSON.parse(text).data ?? []
  return data.map((m) => String(m.id ?? ''))
    .filter((id) => id && !/embed|whisper|tts|audio|image|vision-preview|moderation|rerank/i.test(id))
    .sort()
}

/** Model for chat and retranslation, or ([fast]) the light one for words, phrases and sentences. */
export const modelFor = (fast: boolean) => (fast && prefs.aiFastModel.value.trim()) || prefs.aiModel.value.trim()

/** Text of the answer and the model's reasoning, if it sends one (DeepSeek reasoner etc.). */
export async function generate(system: string | null, messages: Msg[], json = false, fast = false): Promise<{ text: string; reasoning?: string }> {
  check()
  const model = modelFor(fast)
  if (!model) throw new ApiError('Не выбрана модель ИИ (⚙ Настройки → Перевод)')
  try {
    return await request(model, system, messages, json)
  } catch (e) {
    // not every service knows JSON mode: ask again without it (the prompt asks for JSON anyway)
    if (json && String((e as Error).message).includes('HTTP 400')) return request(model, system, messages, false)
    throw e
  }
}

async function request(model: string, system: string | null, messages: Msg[], json: boolean) {
  const msgs: { role: string; content: string }[] = []
  if (system?.trim()) msgs.push({ role: 'system', content: system })
  for (const m of messages) msgs.push({ role: m.user ? 'user' : 'assistant', content: m.text })
  const body: any = { model, messages: msgs, temperature: 0.3 }
  if (json) body.response_format = { type: 'json_object' }
  for (let attempt = 0; ; attempt++) {
    const r = await send(`${base()}/chat/completions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${prefs.aiKey.value.trim()}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    // overloaded / rate limited: wait 2, 4, 8 s and try again
    if ((r.status === 429 || r.status >= 500) && attempt < 3) { await sleep(2000 * 2 ** attempt); continue }
    const text = await r.text()
    if (!r.ok) {
      let msg: string | undefined
      try { msg = JSON.parse(text).error.message } catch { /* not JSON */ }
      throw new ApiError(`ИИ: HTTP ${r.status} ${msg ?? text.slice(0, 200)}`)
    }
    const m = JSON.parse(text).choices?.[0]?.message
    if (!m) throw new ApiError('ИИ не дал ответа')
    const reasoning = String(m.reasoning_content || m.reasoning || '').trim() || undefined
    return { text: String(m.content ?? '').trim(), reasoning }
  }
}
