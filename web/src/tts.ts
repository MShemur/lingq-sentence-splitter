// Text-to-speech ("Озвучка"): one engine for the whole app, with providers. 'system' is the device's own voice (free,
// no network). The cloud ones (Gemini, OpenAI, ElevenLabs, OpenRouter) are called with the user's key; their audio plays in a
// separate <audio> element (primed inside the tap, so iPad lets it start later) and is cached on this device.
// Contract for the reader: ttsSpeaking, ttsSpeak(text, lang), ttsStop().
import { signal } from '@preact/signals'
import { prefs } from './settings'
import { say } from './app'
import { pause, playing } from './audio'
import * as gemini from './gemini'
import { sttKey } from './importer/stt'
import { log, logError } from './applog'

/** true while loading or speaking. */
export const ttsSpeaking = signal(false)
/** Goes up when the device's voices are loaded (the settings page shows the voice found). */
export const ttsVoicesReady = signal(0)

export type TtsProvider = 'system' | 'gemini' | 'openai' | 'elevenlabs' | 'openrouter'
const CLOUD: TtsProvider[] = ['gemini', 'openai', 'elevenlabs', 'openrouter']

const GEMINI_API = 'https://generativelanguage.googleapis.com/v1beta'
const GEMINI_FALLBACK_MODEL = 'gemini-2.5-flash-preview-tts'
const ELEVEN_MODEL = 'eleven_multilingual_v2'
/** OpenRouter speech through the site's proxy (functions/stt: /stt/openrouter/<path of openrouter.ai/api>). */
const OPENROUTER_SPEECH = '/stt/openrouter/v1/audio/speech'
export const OPENROUTER_DEFAULT_MODEL = 'google/gemini-3.8-flash-tts'
export const OPENROUTER_DEFAULT_VOICE = 'Kore'
const CACHE_MAX = 200
/** Same silent file as audio.ts: starting it inside a tap unlocks the element on iPad / iPhone. */
const SILENCE = 'data:audio/wav;base64,UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA='

/** Token of the newest request: anything older is ignored when it ends. */
let seq = 0
let el: HTMLAudioElement | null = null
/** Object URL of the cloud audio now loaded ('' = none). */
let url = ''

if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
  speechSynthesis.addEventListener('voiceschanged', () => { ttsVoicesReady.value++ })
}

// --- text and settings ---

/** Asterisks of the markdown (** and *) are not read aloud. */
const stripMarkup = (text: string) => text.replace(/\*+/g, ' ').replace(/\s+/g, ' ').trim()

/** Speed from the settings, 0.5..1.5 in steps of 0.1. */
function rateNow(): number {
  const r = Number(prefs.ttsRate.value)
  if (!Number.isFinite(r) || r <= 0) return 1
  return Math.round(Math.min(1.5, Math.max(0.5, r)) * 10) / 10
}

const LOCALES: Record<string, string> = {
  de: 'de-DE', en: 'en-US', ru: 'ru-RU', fr: 'fr-FR', es: 'es-ES', it: 'it-IT', pl: 'pl-PL',
  pt: 'pt-PT', nl: 'nl-NL', sv: 'sv-SE', tr: 'tr-TR', uk: 'uk-UA', ja: 'ja-JP', zh: 'zh-CN',
}

/** 'de' -> 'de-DE'; a value with a region stays as it is. */
function speechLang(lang: string): string {
  const l = lang.trim().replace('_', '-')
  return l.includes('-') ? l : (LOCALES[l.toLowerCase()] ?? l)
}

/** The device's voice of this language: the exact locale first, else any voice of the language. */
function systemVoice(lang: string): SpeechSynthesisVoice | null {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) return null
  const voices = speechSynthesis.getVoices()
  const want = speechLang(lang).toLowerCase()
  const base = want.split('-')[0]
  const norm = (v: SpeechSynthesisVoice) => v.lang.replace('_', '-').toLowerCase()
  return voices.find((v) => norm(v) === want) ?? voices.find((v) => norm(v).split('-')[0] === base) ?? null
}

/** Name of the device's voice for this language ('' = none found yet). For the settings page. */
export function ttsSystemVoiceName(lang: string): string {
  void ttsVoicesReady.value
  return systemVoice(lang)?.name ?? ''
}

/** The key of a cloud provider ('' = not set). Gemini: «Перевод и ИИ»; OpenAI and ElevenLabs: «Распознавание речи». */
export function ttsKeyFor(p: TtsProvider): string {
  if (p === 'gemini') return prefs.geminiKey.value.trim()
  if (p === 'openai') return sttKey('openai')
  if (p === 'elevenlabs') return sttKey('elevenlabs')
  if (p === 'openrouter') return ttsOpenrouterKey()
  return ''
}

/** Where the OpenRouter speech key comes from: its own field, else the «Перевод и ИИ» key when that service is OpenRouter. */
export function ttsOpenrouterKeySource(): 'own' | 'ai' | '' {
  if (prefs.ttsKey_openrouter.value.trim()) return 'own'
  return prefs.aiBase.value.includes('openrouter.ai') && prefs.aiKey.value.trim() ? 'ai' : ''
}

function ttsOpenrouterKey(): string {
  const s = ttsOpenrouterKeySource()
  return s === 'own' ? prefs.ttsKey_openrouter.value.trim() : s === 'ai' ? prefs.aiKey.value.trim() : ''
}

// --- system voice ---

function speakSystem(text: string, lang: string, token: number) {
  if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
    ttsSpeaking.value = false
    say('Голос этого устройства недоступен')
    return
  }
  const u = new SpeechSynthesisUtterance(text)
  u.lang = speechLang(lang)
  const v = systemVoice(lang)
  if (v) u.voice = v
  u.rate = rateNow()
  const done = () => { if (token === seq) ttsSpeaking.value = false }
  u.onend = done
  u.onerror = (e) => {
    // 'interrupted' / 'canceled' come from our own stop
    if (e.error !== 'interrupted' && e.error !== 'canceled') log('tts', `Системный голос: ${e.error}`)
    done()
  }
  ttsSpeaking.value = true
  speechSynthesis.speak(u)
}

// --- the element for cloud audio ---

/** Inside the tap: the element starts (silence) so that the real audio can start later on iPad. */
function primed(): HTMLAudioElement {
  if (!el) {
    el = new Audio()
    el.preload = 'auto'
  }
  el.src = SILENCE
  el.play().catch(() => { /* the silence may be replaced at once: not an error */ })
  return el
}

/** Plays the cloud audio; a failure goes to [onFail] (the one place that falls back to the system voice). */
async function playCloud(a: HTMLAudioElement, blob: Blob, rate: number, token: number, onFail: (msg: string) => void) {
  if (token !== seq) return
  if (url) URL.revokeObjectURL(url)
  url = URL.createObjectURL(blob)
  a.src = url
  a.playbackRate = rate
  a.onended = () => { if (token === seq) ttsSpeaking.value = false }
  a.onerror = () => onFail('Не удалось воспроизвести озвучку')
  await a.play()
}

// --- cloud providers ---

const failText = async (r: Response): Promise<string> => {
  const t = await r.text()
  try {
    const o = JSON.parse(t)
    return String(o.detail?.message ?? o.detail ?? o.error?.message ?? o.message ?? t).slice(0, 200)
  } catch {
    return t.slice(0, 200)
  }
}

async function send(what: string, url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch {
    throw new Error(`Нет связи с ${what}`)
  }
}

/** The model picked automatically for one key, kept in memory only (not in the pref). */
let geminiAuto: { key: string; model: string } | null = null

/** Gemini TTS model: the setting, else the first "tts" + "flash" model of the key's list (remembered per key). */
export async function geminiTtsModel(): Promise<string> {
  const pick = prefs.ttsModel_gemini.value.trim()
  if (pick) return pick
  const key = prefs.geminiKey.value.trim()
  if (geminiAuto?.key === key) return geminiAuto.model
  try {
    const list = await geminiTtsModels()
    const model = list.find((m) => m.includes('tts') && m.includes('flash')) ?? GEMINI_FALLBACK_MODEL
    geminiAuto = { key, model }
    return model
  } catch {
    // not remembered: the next tap asks again
    return GEMINI_FALLBACK_MODEL
  }
}

/** The TTS models of the Gemini key (for the settings chips). Throws when there is no key or it is refused. */
export async function geminiTtsModels(): Promise<string[]> {
  return (await gemini.models(prefs.geminiKey.value)).filter((m) => /tts/i.test(m))
}

/** Raw PCM (s16le mono) of Gemini with its sample rate, wrapped into a WAV file. */
function pcmToWav(pcm: Uint8Array<ArrayBuffer>, rate: number): Blob {
  const h = new DataView(new ArrayBuffer(44))
  const str = (o: number, s: string) => { for (let i = 0; i < s.length; i++) h.setUint8(o + i, s.charCodeAt(i)) }
  str(0, 'RIFF'); h.setUint32(4, 36 + pcm.length, true); str(8, 'WAVE')
  str(12, 'fmt '); h.setUint32(16, 16, true); h.setUint16(20, 1, true); h.setUint16(22, 1, true)
  h.setUint32(24, rate, true); h.setUint32(28, rate * 2, true); h.setUint16(32, 2, true); h.setUint16(34, 16, true)
  str(36, 'data'); h.setUint32(40, pcm.length, true)
  return new Blob([h, pcm], { type: 'audio/wav' })
}

async function fetchGemini(key: string, model: string, text: string, voice: string): Promise<Blob> {
  const r = await send('Gemini', `${GEMINI_API}/models/${model}:generateContent`, {
    method: 'POST',
    headers: { 'x-goog-api-key': key, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      contents: [{ parts: [{ text }] }],
      generationConfig: {
        responseModalities: ['AUDIO'],
        speechConfig: { voiceConfig: { prebuiltVoiceConfig: { voiceName: voice } } },
      },
    }),
  })
  if (!r.ok) throw new Error(`Gemini: HTTP ${r.status} ${await failText(r)}`)
  const o = await r.json()
  const inline = (o.candidates?.[0]?.content?.parts ?? []).find((p: any) => p.inlineData?.data)?.inlineData
  if (!inline) throw new Error('Gemini не дал звука')
  const rate = Number(/rate=(\d+)/.exec(String(inline.mimeType ?? ''))?.[1]) || 24000
  const bin = atob(String(inline.data))
  return pcmToWav(Uint8Array.from(bin, (c) => c.charCodeAt(0)), rate)
}

async function fetchOpenAi(key: string, model: string, text: string, voice: string, rate: number): Promise<Blob> {
  // the site's proxy: /stt/openai/<path of api.openai.com/v1>
  const r = await send('OpenAI', '/stt/openai/audio/speech', {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json', accept: 'audio/mpeg' },
    body: JSON.stringify({ model, voice, input: text, response_format: 'mp3', speed: rate }),
  })
  if (!r.ok) throw new Error(`OpenAI: HTTP ${r.status} ${await failText(r)}`)
  return r.blob()
}

async function fetchEleven(key: string, voice: string, text: string): Promise<Blob> {
  const r = await send('ElevenLabs', `/stt/elevenlabs/text-to-speech/${encodeURIComponent(voice)}?output_format=mp3_44100_128`, {
    method: 'POST',
    headers: { 'xi-api-key': key, 'content-type': 'application/json', accept: 'audio/mpeg' },
    body: JSON.stringify({ text, model_id: ELEVEN_MODEL }),
  })
  if (!r.ok) throw new Error(`ElevenLabs: HTTP ${r.status} ${await failText(r)}`)
  return r.blob()
}

/** A speech model of OpenRouter: its voices, response formats, whether it takes the speed, and the estimated price of a minute in USD (null: unknown). */
export type OrModel = { id: string; name: string; voices: string[]; formats: string[]; speed: boolean; perMin: number | null }

let orCache: OrModel[] | null = null

/** GET an OpenRouter list through the site's proxy (the query string is forwarded); throws when not OK. */
async function openrouterList(path: string): Promise<any[]> {
  const r = await send('OpenRouter', path, { headers: openrouterHeaders() })
  if (!r.ok) throw new Error(`OpenRouter: HTTP ${r.status} ${await failText(r)}`)
  const o: any = await r.json()
  return Array.isArray(o?.data) ? o.data : Array.isArray(o) ? o : []
}

/** The base id of a free variant: "fish-audio/s2.1-pro-free:free" -> "fish-audio/s2.1-pro". */
function baseId(id: string): string {
  return id.replace(/:free$/, '').replace(/-free$/, '')
}

/** What a speech-list entry takes: voices, response formats, speed. */
function speechParams(m: any): { voices: string[]; formats: string[]; speed: boolean } {
  const strings = (x: unknown): string[] => (Array.isArray(x) ? x.map((v) => String(v).trim()).filter(Boolean) : [])
  const speech = (Array.isArray(m?.output_modalities) ? m.output_modalities : []).find((x: any) => x?.type === 'speech')
  const sp: any = speech?.supported_parameters ?? {}
  return { voices: strings(sp.voice?.values), formats: strings(sp.response_format?.values), speed: !!sp.speed }
}

/** Estimate of a minute of speech in USD (1000 chars ≈ 1 min ≈ 1500 audio tokens); null when the pricing is unknown. */
function perMinute(pricing: any): number | null {
  const prompt = parseFloat(pricing?.prompt)
  const completion = parseFloat(pricing?.completion)
  if (!Number.isFinite(prompt) && !Number.isFinite(completion)) return null
  const p = Number.isFinite(prompt) ? prompt : 0
  const c = Number.isFinite(completion) ? completion : 0
  return c > 0 ? c * 1500 + p * 250 : p * 1000
}

/** The speech models of OpenRouter: ids and prices from GET /models?output_modalities=speech (free variants included), voices/formats/speed from GET .../audio/speech/models (same id, else the base id). Falls back to the speech list when the full list fails. Remembered once read. Throws when neither list can be read. */
export async function openrouterModels(): Promise<OrModel[]> {
  if (orCache) return orCache
  const [speechR, fullR] = await Promise.allSettled([
    openrouterList(`${OPENROUTER_SPEECH}/models`),
    openrouterList('/stt/openrouter/v1/models?output_modalities=speech'),
  ])
  if (speechR.status === 'rejected' && fullR.status === 'rejected') throw speechR.reason
  const speech = speechR.status === 'fulfilled' ? speechR.value : []
  const full = fullR.status === 'fulfilled' ? fullR.value : speech
  const bySpeech = new Map<string, any>()
  for (const m of speech) {
    const id = String(m?.id ?? '').trim()
    if (id) bySpeech.set(id, m)
  }
  const out: OrModel[] = []
  const seen = new Set<string>()
  for (const m of full) {
    const id = String(m?.id ?? '').trim()
    if (!id || seen.has(id)) continue
    seen.add(id)
    const sp = speechParams(bySpeech.get(id) ?? bySpeech.get(baseId(id)))
    out.push({ id, name: String(m.name || id), ...sp, perMin: perMinute(m.pricing) })
  }
  orCache = out
  return out
}

/** The model's entry, or null when the list cannot be read (then: mp3, and the element applies the speed). */
async function openrouterInfo(model: string): Promise<OrModel | null> {
  try {
    return (await openrouterModels()).find((m) => m.id === model) ?? null
  } catch {
    return null
  }
}

/** Audio in the format the model takes: mp3, else wav, else pcm (wrapped into WAV). Speed goes in the request only if the model takes it. */
async function fetchOpenRouter(key: string, model: string, text: string, voice: string, rate: number, info: OrModel | null): Promise<Blob> {
  const f = info?.formats ?? []
  const format = f.length === 0 || f.includes('mp3') ? 'mp3' : f.includes('wav') ? 'wav' : 'pcm'
  // a model with a voice list gets one of its voices; a model without one (Fish Audio: free voice ids) gets the typed id
  // or none at all (its own default voice)
  const list = info?.voices ?? []
  const v = list.length ? (list.includes(voice) ? voice : list.includes(OPENROUTER_DEFAULT_VOICE) ? OPENROUTER_DEFAULT_VOICE : list[0]) : voice
  const body: Record<string, unknown> = { model, input: text, response_format: format }
  if (v) body.voice = v
  if (info?.speed) body.speed = rate
  const r = await send('OpenRouter', OPENROUTER_SPEECH, {
    method: 'POST',
    headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json', accept: 'audio/*' },
    body: JSON.stringify(body),
  })
  if (!r.ok) throw new Error(`OpenRouter: HTTP ${r.status} ${await failText(r)}`)
  if (format === 'pcm') return pcmToWav(new Uint8Array(await r.arrayBuffer()), model.startsWith('heygen/') ? 44100 : 24000)
  return r.blob()
}

function openrouterHeaders(): Record<string, string> {
  const key = ttsOpenrouterKey()
  return { accept: 'application/json', ...(key ? { authorization: `Bearer ${key}` } : {}) }
}

/** OpenRouter audio from the cache or the network. [sped]: the speed is already in the audio (else the element plays it faster). */
async function openrouterAudio(key: string, text: string, rate: number): Promise<{ blob: Blob; sped: boolean }> {
  const voice = prefs.ttsVoice_openrouter.value.trim()
  const model = prefs.ttsModel_openrouter.value.trim() || OPENROUTER_DEFAULT_MODEL
  const info = await openrouterInfo(model)
  const sped = !!info?.speed
  const cacheKey = ['openrouter', model, voice, rate, text].join('|')
  const hit = await cacheGet(cacheKey)
  if (hit) return { blob: hit, sped }
  const blob = await fetchOpenRouter(key, model, text, voice, rate, info)
  void cachePut(cacheKey, blob)
  return { blob, sped }
}

/** Audio of [text] from a cloud provider: from the cache on this device, else fetched and cached. [sped]: the speed is already in the audio. */
async function cloudAudio(p: TtsProvider, text: string, rate: number): Promise<{ blob: Blob; sped: boolean }> {
  const key = ttsKeyFor(p)
  const names: Record<string, string> = { gemini: 'Gemini', openai: 'OpenAI', elevenlabs: 'ElevenLabs', openrouter: 'OpenRouter' }
  if (!key) {
    const where = p === 'gemini' ? '⚙ Перевод и ИИ'
      : p === 'openrouter' ? '⚙ Озвучка или ⚙ Перевод и ИИ'
      : '⚙ Распознавание речи'
    throw new Error(`Нет ключа для озвучки: ${names[p]} (${where})`)
  }
  if (p === 'openrouter') return openrouterAudio(key, text, rate)
  const voice = p === 'gemini' ? prefs.ttsVoice_gemini.value
    : p === 'openai' ? prefs.ttsVoice_openai.value
    : prefs.ttsVoice_elevenlabs.value.trim()
  if (p === 'elevenlabs' && !voice) throw new Error('Нет ID голоса ElevenLabs (⚙ Озвучка)')
  const model = p === 'gemini' ? await geminiTtsModel()
    : p === 'openai' ? (prefs.ttsModel_openai.value.trim() || 'gpt-4o-mini-tts')
    : ELEVEN_MODEL
  // only OpenAI takes the speed in the request; Gemini and ElevenLabs are sped up by the element, so the rate is not in their key
  const cacheKey = [p, model, voice, p === 'openai' ? rate : '', text].join('|')
  const hit = await cacheGet(cacheKey)
  if (hit) return { blob: hit, sped: p === 'openai' }
  const blob = p === 'gemini' ? await fetchGemini(key, model, text, voice)
    : p === 'openai' ? await fetchOpenAi(key, model, text, voice, rate)
    : await fetchEleven(key, voice, text)
  void cachePut(cacheKey, blob)
  return { blob, sped: p === 'openai' }
}

// --- cache in IndexedDB: 'satzloop-tts', at most CACHE_MAX entries, the oldest go first ---

let dbp: Promise<IDBDatabase> | null = null
function db(): Promise<IDBDatabase> {
  dbp ??= new Promise((ok, fail) => {
    const r = indexedDB.open('satzloop-tts', 1)
    r.onupgradeneeded = () => {
      const s = r.result.createObjectStore('audio', { keyPath: 'key' })
      s.createIndex('t', 't')
    }
    r.onsuccess = () => ok(r.result)
    r.onerror = () => fail(r.error)
  })
  return dbp
}

async function cacheGet(key: string): Promise<Blob | null> {
  try {
    const d = await db()
    return await new Promise((ok) => {
      const q = d.transaction('audio', 'readonly').objectStore('audio').get(key)
      q.onsuccess = () => ok((q.result?.blob as Blob | undefined) ?? null)
      q.onerror = () => ok(null)
    })
  } catch {
    return null
  }
}

async function cachePut(key: string, blob: Blob): Promise<void> {
  try {
    const d = await db()
    await new Promise<void>((ok, fail) => {
      const tx = d.transaction('audio', 'readwrite')
      tx.objectStore('audio').put({ key, blob, t: Date.now() })
      tx.oncomplete = () => ok()
      tx.onerror = () => fail(tx.error)
    })
    await cacheTrim(d)
  } catch {
    /* the cache is optional */
  }
}

async function cacheTrim(d: IDBDatabase): Promise<void> {
  await new Promise<void>((ok) => {
    const tx = d.transaction('audio', 'readwrite')
    const s = tx.objectStore('audio')
    const count = s.count()
    count.onsuccess = () => {
      let extra = count.result - CACHE_MAX
      if (extra <= 0) return
      const cur = s.index('t').openCursor()
      cur.onsuccess = () => {
        const c = cur.result
        if (!c || extra-- <= 0) return
        c.delete()
        c.continue()
      }
    }
    tx.oncomplete = () => ok()
    tx.onerror = () => ok()
  })
}

// --- the contract ---

/** Reads [text] aloud in [lang] ('de'). Stops anything before. Call it inside a tap handler (iPad). */
export function ttsSpeak(text: string, lang: string): void {
  ttsStop()
  const clean = stripMarkup(text)
  if (!clean) return
  // the lesson's audio would talk over the voice
  if (playing.value) pause()
  const token = seq
  const provider = prefs.ttsProvider.value as TtsProvider
  if (!CLOUD.includes(provider)) {
    speakSystem(clean, lang, token)
    return
  }
  // the element is primed now, inside the tap; the network call comes after
  const a = primed()
  ttsSpeaking.value = true
  const rate = rateNow()
  // one failure point per request: the first failure says the message and speaks with the system voice; later ones
  // (the element's own error event after that) do nothing
  let failed = false
  const onFail = (msg: string) => {
    if (failed || token !== seq) return
    failed = true
    say(`${msg}. Читаю системным голосом`)
    speakSystem(clean, lang, token)
  }
  void (async () => {
    try {
      const { blob, sped } = await cloudAudio(provider, clean, rate)
      await playCloud(a, blob, sped ? 1 : rate, token, onFail)
    } catch (e) {
      if (token === seq) logError('tts', `${provider} ✗`, e)
      onFail((e as Error).message || String(e))
    }
  })()
}

/** Stops the voice (system or cloud) and forgets the request in progress. */
export function ttsStop(): void {
  seq++
  if (typeof window !== 'undefined' && 'speechSynthesis' in window) speechSynthesis.cancel()
  try { el?.pause() } catch { /* ignore */ }
  if (url) { URL.revokeObjectURL(url); url = '' }
  ttsSpeaking.value = false
}
