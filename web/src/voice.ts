// Dictation: the microphone records until pressed again, then the speech becomes text (the speech-recognition service
// from settings). While recording, [level] follows the loudness so the screen shows that the device hears something.
import { signal } from '@preact/signals'
import { prefs } from './settings'
import { log } from './applog'

export class Dictation {
  recording = signal(false)
  hearing = signal(false)
  /** Loudness 0..1 while recording (about 20 times a second). */
  level = signal(0)
  private rec: MediaRecorder | null = null
  private stopMeter: (() => void) | null = null

  get busy() { return this.recording.value || this.hearing.value }

  /** Starts recording; called again, stops and hands the recognised text to [done]. Errors go to [fail]. */
  async toggle(done: (text: string) => void | Promise<void>, fail: (msg: string) => void) {
    if (this.rec) { this.rec.stop(); return }
    if (this.hearing.value) return
    let stream: MediaStream
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }) } catch {
      fail('Нет доступа к микрофону (разрешите его в настройках браузера)')
      return
    }
    const type = ['audio/mp4', 'audio/webm;codecs=opus', 'audio/webm'].find((t) => window.MediaRecorder?.isTypeSupported?.(t)) ?? ''
    const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined)
    const chunks: Blob[] = []
    rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
    rec.onstop = () => {
      stream.getTracks().forEach((t) => t.stop())
      this.stopMeter?.(); this.stopMeter = null
      this.rec = null
      this.recording.value = false
      this.level.value = 0
      void this.recognise(new Blob(chunks, { type: rec.mimeType || type || 'audio/webm' }), done, fail)
    }
    rec.start()
    this.rec = rec
    this.recording.value = true
    this.meter(stream)
  }

  /** Stops without recognising (screen closed). */
  cancel() {
    if (!this.rec) return
    const r = this.rec
    r.onstop = null
    r.stream.getTracks().forEach((t) => t.stop())
    try { r.stop() } catch { /* already stopped */ }
    this.stopMeter?.(); this.stopMeter = null
    this.rec = null
    this.recording.value = false
    this.level.value = 0
  }

  private meter(stream: MediaStream) {
    try {
      const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
      const ctx = new Ctx()
      const an = ctx.createAnalyser()
      an.fftSize = 512
      ctx.createMediaStreamSource(stream).connect(an)
      const buf = new Uint8Array(an.fftSize)
      const id = setInterval(() => {
        an.getByteTimeDomainData(buf)
        let sum = 0
        for (const v of buf) { const x = (v - 128) / 128; sum += x * x }
        const rms = Math.sqrt(sum / buf.length)
        this.level.value = Math.min(1, rms * 4)
      }, 50)
      this.stopMeter = () => { clearInterval(id); void ctx.close() }
    } catch { /* no level, the pulsing dot still shows */ }
  }

  private async recognise(blob: Blob, done: (text: string) => void | Promise<void>, fail: (msg: string) => void) {
    if (blob.size < 2000) { fail('Слишком короткая запись'); return }
    this.hearing.value = true
    try {
      const stt = await import('./importer/stt')
      const p = stt.sttProvider(prefs.sttProvider.value)
      const whisper = p.id === 'groq' || p.id === 'openai'
      const ext = blob.type.includes('mp4') ? 'm4a' : blob.type.includes('ogg') ? 'ogg' : 'webm'
      // Whisper detects the language itself (Russian or the lesson's language)
      const t = await stt.transcribe({ blob, name: `voice.${ext}`, mp3: null, pcm: null }, whisper ? '' : (prefs.trLang.value.trim() || 'ru'),
        p, stt.sttKeyFor(p), stt.sttModel(p.id), () => {})
      const text = t.text.trim()
      log('voice', `Диктовка: ${text.length} знаков`)
      this.hearing.value = false
      if (!text) { fail('Речь не распознана'); return }
      await done(text)
    } catch (e) {
      fail((e as Error).message)
    } finally { this.hearing.value = false }
  }
}
