// "Run AI" of the podcast player: the transcription of the episode (its sentences with times). It runs in the background
// (it goes on when the player is left) and is saved part by part, so the text shows while the episode plays.
import { signal } from '@preact/signals'
import { errorText, say } from './app'
import { log } from './applog'
import { lang, prefs } from './settings'
import { mp3Frames } from './importer/audio'
import { shouldMerge, joinText } from './importer/fixer'
import { sttKeyFor, sttModel, sttProvider, transcribe, type TWord } from './importer/stt'
import { podNow, podStatus, saveTranscript, type PodEpisode, type PodSent } from './podplay'

/** Key of the episode whose transcription is going, null = none. */
export const aiRunKey = signal<string | null>(null)
/** Progress of the run, 0..1 (the download, then the parts). */
export const aiProgress = signal(0)

const ENDS = /[.!?…][»”"’'\)\]]*$/

/**
 * Sentences from recognised words: a sentence ends at a word ending with . ! ? …; splits at abbreviations are merged
 * with the fixer's rules (shouldMerge). Times in ms.
 */
export function sentsOf(words: TWord[]): PodSent[] {
  const out: { text: string; start: number; end: number }[] = []
  let text = '', start = 0, end = 0, open = false
  const close = () => {
    if (!open) return
    const prev = out[out.length - 1]
    if (prev && shouldMerge(prev.text, text)) { prev.text = joinText(prev.text, text); prev.end = end }
    else out.push({ text, start, end })
    open = false
    text = ''
  }
  for (const w of words) {
    const t = w.text.trim()
    if (!t) continue
    if (!open) { text = t; start = w.start; open = true } else text = joinText(text, t)
    end = w.end
    if (ENDS.test(t)) close()
  }
  close()
  return out.map((s) => ({ text: s.text.trim(), start: Math.round(s.start * 1000), end: Math.round(s.end * 1000) }))
}

/** The episode's audio: downloaded through the site proxy; the progress goes to podStatus when the size is known. */
async function download(ep: PodEpisode): Promise<Blob> {
  const r = await fetch(`/fetch?url=${encodeURIComponent(ep.audio)}`)
  if (!r.ok) throw new Error(`не удалось загрузить аудио (HTTP ${r.status})`)
  const type = r.headers.get('content-type') || ''
  const total = Number(r.headers.get('content-length')) || 0
  const chunks: BlobPart[] = []
  if (!r.body) return new Blob([await r.blob()], { type })
  const rd = r.body.getReader()
  let got = 0
  for (;;) {
    const { done, value } = await rd.read()
    if (done) break
    chunks.push(value as unknown as BlobPart)
    got += value.length
    if (total > 0) {
      podStatus.value = `Загрузка аудио: ${Math.min(100, Math.floor((got * 100) / total))} %`
      aiProgress.value = Math.min(1, got / total)
    }
  }
  return new Blob(chunks, { type })
}

/** The episode's file name (with an extension: the transcription picks the format by it). */
function fileName(ep: PodEpisode): string {
  const base = ep.audio.split(/[?#]/)[0].split('/').pop() || 'episode'
  return /\.[a-z0-9]{2,4}$/i.test(base) ? base : base + '.mp3'
}

/**
 * Starts the transcription of the episode in the player, in the background. Does nothing if this episode is already
 * being transcribed. The sentences are saved after every part; the status goes to podStatus.
 */
export function runAi(): void {
  const ep = podNow.value
  if (!ep) return
  if (aiRunKey.value === ep.key) return
  if (aiRunKey.value) { say('Уже идёт расшифровка другого выпуска'); return }
  const prov = sttProvider(prefs.sttProvider.value)
  const key = sttKeyFor(prov)
  if (!key) { say('Нет ключа распознавания речи (⚙ Распознавание речи)'); return }
  aiRunKey.value = ep.key
  aiProgress.value = 0
  podStatus.value = 'Загрузка аудио…'
  void work(ep, prov.id, key)
}

async function work(ep: PodEpisode, provId: string, key: string): Promise<void> {
  const prov = sttProvider(provId)
  const words: TWord[] = []
  let chain: Promise<void> = Promise.resolve()
  try {
    const blob = await download(ep)
    const name = fileName(ep)
    const mp3 = /\.mp3$/i.test(name) || blob.type === 'audio/mpeg' ? await mp3Frames(blob) : null
    podStatus.value = 'Расшифровка: подготовка…'
    aiProgress.value = 0
    await transcribe({ blob, name, mp3, pcm: null }, lang(), prov, key, sttModel(prov.id), () => { /* the parts report below */ },
      (pw, i, n) => {
        words.push(...pw)
        const sents = sentsOf(words)
        chain = chain.then(() => saveTranscript(ep.key, sents))
        aiProgress.value = (i + 1) / n
        podStatus.value = i + 1 < n ? `Расшифровка: часть ${i + 2} из ${n}` : 'Расшифровка: последняя часть…'
      })
    await chain
    podStatus.value = ''
    say(words.length ? 'Расшифровка готова' : 'В аудио не нашлось речи')
  } catch (e) {
    await chain.catch(() => undefined)
    log('pod', `Расшифровка ✗ ${errorText(e)}`)
    podStatus.value = `Ошибка: ${errorText(e)}`
  } finally {
    aiRunKey.value = null
    aiProgress.value = 0
  }
}
