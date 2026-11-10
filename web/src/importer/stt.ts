// Speech recognition: an audio file (podcast, recording) becomes the text of a lesson, with the time of every word.
// Port of Android Stt (Transcribe.kt). The services are called through this site's proxy (/stt/<service>/…).
import { prefs } from '../settings'
import { log } from '../applog'
import { decodePcm, mp3Pieces, pcmCuts, RATE, wav, type Mp3 } from './audio'

/** One recognised word with its time in the audio, s. */
export interface TWord { text: string; start: number; end: number }
/** A piece of recognised speech (sentence, Whisper segment or paragraph); [newPara] = a paragraph starts here. */
export interface TSeg { text: string; start: number; end: number; newPara?: boolean }
export interface Transcript { text: string; words: TWord[] }

export interface SttProvider {
  id: string; name: string; note: string; keyUrl: string; priceUrl: string
  /** Models (the first is the default) and their price, US$ per hour of audio. */
  prices: [string, number][]
  models: string[]; maxBytes: number
}

/** When the prices below were checked on the services' sites. */
export const STT_PRICES_CHECKED = '10.11.2026'

const provider = (id: string, name: string, note: string, keyUrl: string, priceUrl: string, prices: [string, number][], maxBytes: number): SttProvider =>
  ({ id, name, note, keyUrl, priceUrl, prices, models: prices.map((p) => p[0]), maxBytes })

/** The proxy (Cloudflare) takes requests up to 100 MB. */
const PROXY_MAX = 95_000_000

export const STT_PROVIDERS: SttProvider[] = [
  provider('groq', 'Groq', 'Whisper large-v3: самый дешёвый и быстрый. Есть бесплатный лимит.', 'https://console.groq.com/keys',
    'https://console.groq.com/docs/speech-to-text', [['whisper-large-v3-turbo', 0.04], ['whisper-large-v3', 0.111]], 24_000_000),
  provider('openai', 'OpenAI', 'Whisper. Время слов есть только у whisper-1.', 'https://platform.openai.com/api-keys',
    'https://platform.openai.com/docs/pricing', [['whisper-1', 0.36]], 24_000_000),
  provider('deepgram', 'Deepgram', 'Nova-3: точный, быстрый, с абзацами. При регистрации 200 $ бесплатно.', 'https://console.deepgram.com/',
    'https://deepgram.com/pricing', [['nova-3', 0.258]], PROXY_MAX),
  provider('assemblyai', 'AssemblyAI', 'Точный, с абзацами. При регистрации 50 $ бесплатно.', 'https://www.assemblyai.com/dashboard',
    'https://www.assemblyai.com/pricing', [['universal-2', 0.15], ['universal-3-5-pro', 0.21]], PROXY_MAX),
  provider('elevenlabs', 'ElevenLabs', 'Scribe: очень точный. В бесплатном тарифе 4,5 часа.', 'https://elevenlabs.io/app/settings/api-keys',
    'https://elevenlabs.io/pricing/api', [['scribe_v2', 0.22], ['scribe_v1', 0.4]], PROXY_MAX),
]

const priceOf = (p: SttProvider, model: string) => p.prices.find((x) => x[0] === (model || p.models[0]))?.[1]

/** "≈ 0,7 цента", "≈ 12 центов", "≈ 1,20 $" for [minutes] of audio; null when the price is unknown. */
export function sttCost(p: SttProvider, model: string, minutes: number): string | null {
  const price = priceOf(p, model)
  if (price == null) return null
  const usd = (price * minutes) / 60, cents = usd * 100
  if (cents < 0.1) return 'меньше 0,1 цента'
  if (cents < 10) return `≈ ${cents.toFixed(1).replace('.', ',')} цента`
  if (cents < 100) {
    const n = Math.floor(cents), m10 = n % 10, m100 = n % 100
    return `≈ ${n} ${m10 === 1 && m100 !== 11 ? 'цент' : m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'цента' : 'центов'}`
  }
  return `≈ ${usd.toFixed(2).replace('.', ',')} $`
}

/** "0,04 $ за час". */
export const sttHourPrice = (v: number) => `${String(Number(v.toFixed(3))).replace('.', ',')} $ за час`
export const sttPrice = priceOf

export const sttProvider = (id: string) => STT_PROVIDERS.find((p) => p.id === id) ?? STT_PROVIDERS[0]

function readMap(s: string): Record<string, string> {
  try { const o = JSON.parse(s); return o && typeof o === 'object' ? o : {} } catch { return {} }
}
/** Keys per service (synced, encrypted) and models per service (this device only). */
export const sttKeys = () => readMap(prefs.sttKeys.value)
export const sttKey = (id: string) => String(sttKeys()[id] ?? '').trim()
export function setSttKey(id: string, key: string) {
  const m = sttKeys()
  if (key.trim()) m[id] = key.trim(); else delete m[id]
  prefs.sttKeys.value = JSON.stringify(m)
}
/** AssemblyAI retired "best" / "nano": a saved one falls back to the default model. */
const RETIRED = new Set(['best', 'nano'])
export const sttModel = (id: string) => { const m = String(readMap(prefs.sttModels.value)[id] ?? '').trim(); return RETIRED.has(m) ? '' : m }
export function setSttModel(id: string, model: string) {
  const m = readMap(prefs.sttModels.value)
  m[id] = model.trim()
  prefs.sttModels.value = JSON.stringify(m)
}

/** The key for [p]; Groq falls back to the key of the translation AI when that one is Groq. */
export const sttKeyFor = (p: SttProvider) =>
  sttKey(p.id) || (p.id === 'groq' && prefs.aiBase.value.includes('groq.com') ? prefs.aiKey.value.trim() : '')

/** Whisper services accept these formats as they are. */
const WHISPER_TYPES = new Set(['flac', 'mp3', 'mp4', 'mpeg', 'mpga', 'm4a', 'ogg', 'wav', 'webm'])

/** The audio to recognise: a file or a cut part; [mp3] its frames when it is an MP3, [pcm] when already decoded. */
export interface SttInput { blob: Blob; name: string; mp3: Mp3 | null; pcm: Int16Array | null }

/** Recognises [a] in language [lang]; [status] reports progress. */
export async function transcribe(a: SttInput, lang: string, p: SttProvider, key: string, model: string, status: (s: string) => void): Promise<Transcript> {
  if (!key) throw new Error(`не задан ключ ${p.name} (⚙ Настройки → Распознавание речи)`)
  const m = model || p.models[0]
  const ext = a.name.split('.').pop()?.toLowerCase() ?? ''
  const whisper = p.id === 'groq' || p.id === 'openai'
  const t0 = Date.now()
  let pieces: { blob: Blob; name: string; offset: number }[]
  if (a.blob.size <= p.maxBytes && (!whisper || WHISPER_TYPES.has(ext))) pieces = [{ blob: a.blob, name: a.name, offset: 0 }]
  else if (a.mp3) {
    status('Готовлю аудио…')
    pieces = (await mp3Pieces(a.blob, a.mp3)).map((x, i) => ({ ...x, name: `part${i}.mp3` }))
  } else {
    // too big for one request (or a format the service does not take): 16 kHz mono WAV pieces of ~8 min
    status('Готовлю аудио…')
    const pcm = a.pcm ?? await decodePcm(a.blob)
    const cuts = pcmCuts(pcm)
    pieces = cuts.slice(0, -1).map((c, i) => ({ blob: wav(pcm, c, cuts[i + 1]), name: `part${i}.wav`, offset: c / RATE }))
  }
  const segs: TSeg[] = [], words: TWord[] = []
  for (const [i, pc] of pieces.entries()) {
    status(pieces.length > 1 ? `Распознаю: ${p.name}, часть ${i + 1} из ${pieces.length}…` : `Распознаю: ${p.name}…`)
    const [s, w] = await send(p, key, m, lang, pc.blob, pc.name)
    segs.push(...s.map((x) => ({ ...x, start: x.start + pc.offset, end: x.end + pc.offset })))
    words.push(...w.map((x) => ({ ...x, start: x.start + pc.offset, end: x.end + pc.offset })))
  }
  const text = paragraphs(dropHallucinations(segs))
  log('stt', `${p.name} ${m}: ${words.length} слов, ${Math.round((Date.now() - t0) / 1000)} с${pieces.length > 1 ? ` (частей: ${pieces.length})` : ''}`)
  return { text, words }
}

type Result = [TSeg[], TWord[]]

function send(p: SttProvider, key: string, model: string, lang: string, body: Blob, name: string): Promise<Result> {
  switch (p.id) {
    case 'openai': return whisper('openai', key, model, lang, body, name)
    case 'deepgram': return deepgram(key, model, lang, body)
    case 'assemblyai': return assembly(key, model, lang, body)
    case 'elevenlabs': return eleven(key, model, lang, body, name)
    default: return whisper('groq', key, model, lang, body, name)
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Runs a request through the proxy (again after 2, 4, 8 s when the service is busy); the answer as JSON. */
async function call(service: string, path: string, init: RequestInit, what: string): Promise<any> {
  const t0 = Date.now()
  for (let attempt = 0; ; attempt++) {
    let r: Response
    try {
      r = await fetch(`/stt/${service}/${path}`, init)
    } catch {
      log('stt', `${what} ✗ нет связи`)
      // a service may also cut the upload off when it does not take the key
      throw new Error(`${what}: нет связи или сервис оборвал загрузку (проверьте ключ)`)
    }
    log('stt', `${what} → ${r.status} (${Date.now() - t0} мс)`)
    if ((r.status === 429 || r.status >= 500) && attempt < 3) { await sleep(2000 << attempt); continue }
    if (r.status === 401 || r.status === 403) throw new Error(`${what} не принял ключ (HTTP ${r.status})`)
    const text = await r.text()
    if (!r.ok) {
      let o: any = null
      try { o = JSON.parse(text) } catch { /* not JSON */ }
      const msg = o?.error?.message || (typeof o?.error === 'string' && o.error) || o?.detail?.message || o?.err_msg || text.slice(0, 200)
      throw new Error(`${what}: HTTP ${r.status} ${msg}`)
    }
    return JSON.parse(text)
  }
}

const num = (v: unknown) => Number(v) || 0

/** Groq / OpenAI: Whisper segments (text with punctuation) and words (times). */
async function whisper(service: string, key: string, model: string, lang: string, body: Blob, name: string): Promise<Result> {
  const f = new FormData()
  f.append('file', body, name)
  f.append('model', model)
  f.append('language', lang)
  f.append('response_format', 'verbose_json')
  f.append('timestamp_granularities[]', 'word')
  f.append('timestamp_granularities[]', 'segment')
  f.append('temperature', '0')
  const o = await call(service, 'audio/transcriptions', { method: 'POST', headers: { Authorization: `Bearer ${key}` }, body: f },
    service === 'openai' ? 'OpenAI' : 'Groq')
  const words: TWord[] = (o.words ?? []).map((w: any) => ({ text: String(w.word ?? '').trim(), start: num(w.start), end: num(w.end) }))
  let segs: TSeg[] = (o.segments ?? []).map((s: any) => ({ text: String(s.text ?? '').trim(), start: num(s.start), end: num(s.end) }))
  if (!segs.length) segs = [{ text: String(o.text ?? '').trim(), start: 0, end: words[words.length - 1]?.end ?? 0 }]
  return [segs, words]
}

/** Deepgram: sentences inside paragraphs, words with punctuation. */
async function deepgram(key: string, model: string, lang: string, body: Blob): Promise<Result> {
  const q = new URLSearchParams({ model, language: lang, smart_format: 'true', punctuate: 'true', paragraphs: 'true' })
  const o = await call('deepgram', `listen?${q}`, {
    method: 'POST', headers: { Authorization: `Token ${key}`, 'Content-Type': body.type || 'application/octet-stream' }, body,
  }, 'Deepgram')
  const alt = o?.results?.channels?.[0]?.alternatives?.[0]
  if (!alt) throw new Error('Deepgram не дал ответа')
  const words: TWord[] = (alt.words ?? []).map((w: any) => ({ text: String(w.punctuated_word || w.word || ''), start: num(w.start), end: num(w.end) }))
  const segs: TSeg[] = []
  for (const para of alt.paragraphs?.paragraphs ?? []) {
    (para.sentences ?? []).forEach((s: any, i: number) => segs.push({ text: String(s.text ?? ''), start: num(s.start), end: num(s.end), newPara: i === 0 }))
  }
  if (!segs.length) segs.push({ text: String(alt.transcript ?? ''), start: 0, end: words[words.length - 1]?.end ?? 0 })
  return [segs, words]
}

/** AssemblyAI: upload, start, wait; paragraphs and words (ms). */
async function assembly(key: string, model: string, lang: string, body: Blob): Promise<Result> {
  const h = { Authorization: key }
  const up = (await call('assemblyai', 'upload', { method: 'POST', headers: { ...h, 'Content-Type': 'application/octet-stream' }, body }, 'AssemblyAI')).upload_url
  const job: Record<string, unknown> = { audio_url: up, language_code: lang, punctuate: true, format_text: true }
  if (model) job.speech_model = model
  const id = (await call('assemblyai', 'transcript', {
    method: 'POST', headers: { ...h, 'Content-Type': 'application/json' }, body: JSON.stringify(job),
  }, 'AssemblyAI')).id
  const deadline = Date.now() + 60 * 60_000
  let t: any
  for (;;) {
    await sleep(3000)
    t = await call('assemblyai', `transcript/${id}`, { headers: h }, 'AssemblyAI')
    if (t.status === 'completed') break
    if (t.status === 'error') throw new Error(`AssemblyAI: ${t.error}`)
    if (Date.now() > deadline) throw new Error('AssemblyAI не успел за час')
  }
  const words: TWord[] = (t.words ?? []).map((w: any) => ({ text: String(w.text ?? ''), start: num(w.start) / 1000, end: num(w.end) / 1000 }))
  let paras: any = null
  try { paras = await call('assemblyai', `transcript/${id}/paragraphs`, { headers: h }, 'AssemblyAI') } catch { /* no paragraphs */ }
  let segs: TSeg[] = (paras?.paragraphs ?? []).map((p: any) => ({ text: String(p.text ?? ''), start: num(p.start) / 1000, end: num(p.end) / 1000, newPara: true }))
  if (!segs.length) segs = [{ text: String(t.text ?? ''), start: 0, end: words[words.length - 1]?.end ?? 0 }]
  return [segs, words]
}

/** ElevenLabs Scribe: words and spaces with times; sentences are cut at . ! ? */
async function eleven(key: string, model: string, lang: string, body: Blob, name: string): Promise<Result> {
  const f = new FormData()
  f.append('file', body, name)
  f.append('model_id', model)
  f.append('language_code', lang)
  f.append('timestamps_granularity', 'word')
  f.append('tag_audio_events', 'false')
  const o = await call('elevenlabs', 'speech-to-text', { method: 'POST', headers: { 'xi-api-key': key }, body: f }, 'ElevenLabs')
  const tokens: any[] = (o.words ?? []).filter((t: any) => t.type !== 'audio_event')
  const isWord = (t: any) => (t.type ?? 'word') === 'word'
  const words: TWord[] = tokens.filter(isWord).map((t) => ({ text: String(t.text ?? ''), start: num(t.start), end: num(t.end) }))
  const segs: TSeg[] = []
  let sb = '', start = -1
  for (const tk of tokens) {
    const txt = String(tk.text ?? '')
    if (start < 0 && txt.trim()) start = num(tk.start)
    sb += txt
    if (isWord(tk) && /[.!?…]$/.test(txt.trimEnd())) { segs.push({ text: sb.trim(), start, end: num(tk.end) }); sb = ''; start = -1 }
  }
  if (sb.trim()) segs.push({ text: sb.trim(), start: Math.max(0, start), end: words[words.length - 1]?.end ?? 0 })
  if (!segs.length) segs.push({ text: String(o.text ?? ''), start: 0, end: 0 })
  return [segs, words]
}

/** A ready transcript (podcast VTT / SRT / JSON) cut to [fromMs, toMs): times of words spread over each piece. */
/**
 * The stretch of the podcast's own text [full] that the recognition [rec] covers, found by 5-word runs at the start
 * and end of the recognition (the whole file = all text); the recognised words keep giving the times. null = no match.
 */
export function matchText(full: string, rec: Transcript, whole: boolean): Transcript | null {
  if (whole) return { text: full.trim(), words: rec.words }
  const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')
  const toks = [...full.matchAll(/\S+/g)].filter((m) => norm(m[0]))
  const fw = toks.map((m) => norm(m[0]))
  const rw = rec.words.map((w) => norm(w.text)).filter(Boolean)
  const n = 5
  if (rw.length < 20 || fw.length < n) return null
  const at = (k: number, i: number) => { for (let j = 0; j < n; j++) if (fw[k + j] !== rw[i + j]) return false; return true }
  const find = (i: number, from: number) => { for (let k = from; k <= fw.length - n; k++) if (at(k, i)) return k; return -1 }
  let start = -1
  for (let i = 0; i < Math.min(60, rw.length - n); i++) { const k = find(i, 0); if (k >= 0) { start = Math.max(0, k - i); break } }
  if (start < 0) return null
  let end = -1
  for (let i = rw.length - n; i >= Math.max(0, rw.length - n - 60); i--) {
    const k = find(i, start)
    if (k >= 0) { end = Math.min(fw.length, k + n + (rw.length - n - i)); break }
  }
  if (end <= start || end - start < rw.length / 2 || end - start > rw.length * 2) return null
  const last = toks[end - 1]
  return { text: full.slice(toks[start].index!, last.index! + last[0].length).trim(), words: rec.words }
}

export function fromSegments(all: TSeg[], fromMs: number, toMs: number | null): Transcript {
  const a = fromMs / 1000, b = toMs != null ? toMs / 1000 : Infinity
  const segs = all.filter((s) => s.end > a && s.start < b).map((s) => ({ ...s, start: Math.max(0, s.start - a), end: Math.max(0, s.end - a) }))
  const words = segs.flatMap((s) => {
    const ws = s.text.split(/\s+/).filter(Boolean)
    const total = Math.max(1, ws.reduce((n, w) => n + w.length, 0))
    let t = s.start
    return ws.map((w) => { const d = ((s.end - s.start) * w.length) / total; const x = { text: w, start: t, end: t + d }; t += d; return x })
  })
  return { text: paragraphs(segs), words }
}

/** Whisper sometimes "hears" subtitle credits in silence, or repeats a line: such pieces are dropped. */
function dropHallucinations(segs: TSeg[]): TSeg[] {
  const junk = /Untertitel (der|im Auftrag|von)|Amara\.org|Copyright WDR|Vielen Dank fürs Zuschauen/i
  const out: TSeg[] = []
  let repeats = 0
  for (const s of segs) {
    if (!s.text.trim() || junk.test(s.text)) continue
    repeats = out.length && out[out.length - 1].text === s.text ? repeats + 1 : 0
    if (repeats >= 2) continue
    out.push(s)
  }
  return out
}

/** Text with paragraphs: where the service marked them, otherwise at a sentence end after a pause or ~90 words. */
function paragraphs(segs: TSeg[]): string {
  const given = segs.slice(1).some((s) => s.newPara)
  let out = '', words = 0
  let prev: TSeg | null = null
  for (const s of segs) {
    const t = s.text.trim()
    if (!t) continue
    if (prev) {
      const sentenceEnd = /[.!?…»“"]$/.test(prev.text.trimEnd())
      const brk = given ? !!s.newPara : sentenceEnd && (s.start - prev.end >= 1.2 || words >= 90)
      if (brk) { out += '\n\n'; words = 0 } else out += ' '
    }
    out += t
    words += t.split(' ').length
    prev = s
  }
  return out
}

/**
 * Times of LingQ sentences from the recognised words: [start, end] in s per sentence. The words are matched in
 * order (a few words of look-ahead), so small edits of the text are fine; unmatched sentences get the gap between
 * their neighbours.
 */
export function align(sentences: string[], words: TWord[]): [number, number][] {
  const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')
  const ws = words.map((w) => norm(w.text))
  let j = 0
  const raw = sentences.map((s): [number, number] | null => {
    let first = -1, last = -1
    for (const t of s.split(/\s+/).map(norm).filter(Boolean)) {
      let k = j
      const lim = Math.min(ws.length, j + 8)
      while (k < lim && ws[k] !== t) k++
      if (k < lim) { if (first < 0) first = k; last = k; j = k + 1 }
    }
    return first < 0 ? null : [words[first].start, words[last].end]
  })
  const end = words[words.length - 1]?.end ?? 0
  return raw.map((r, i) => {
    if (r) return r
    let a = 0, b = end
    for (let k = i - 1; k >= 0; k--) if (raw[k]) { a = raw[k]![1]; break }
    for (let k = i + 1; k < raw.length; k++) if (raw[k]) { b = raw[k]![0]; break }
    return [a, Math.max(a, b)]
  })
}
