// Conversation with Gemini about the whole text or one sentence ([system] holds that context). Port of ChatSession.
import { signal } from '@preact/signals'
import * as gemini from './gemini'
import type { Msg } from './gemini'
import { errorText, geminiFailed } from './app'
import { prefs } from './settings'
import { log } from './applog'

export class ChatSession {
  messages = signal<Msg[]>([])
  input = signal('')
  sending = signal(false)
  error = signal<string | null>(null)
  /** Microphone: recording / recognising the spoken question. */
  recording = signal(false)
  hearing = signal(false)
  private rec: MediaRecorder | null = null
  private fast = false

  /** [kind]: what the chat is about (card label and suggestion chips). */
  constructor(
    readonly title: string, private system: string, readonly context: string,
    readonly kind: 'text' | 'sentence' | 'word' = 'sentence',
  ) {}

  /** [fast]: a spoken question, answered by the light (fast) model. */
  async send(fast = false) {
    const q = this.input.value.trim()
    if (!q || this.sending.value) return
    this.fast = fast
    this.input.value = ''
    this.error.value = null
    this.messages.value = [...this.messages.value, { user: true, text: q }]
    await this.answer()
  }

  /** After an error: ask Gemini again for the last question (it is not added twice). */
  async retry() {
    if (this.sending.value || !this.messages.value.at(-1)?.user) return
    this.error.value = null
    await this.answer()
  }

  /** Microphone button: starts recording; pressed again, the speech is recognised and sent at once. */
  async toggleVoice() {
    if (this.rec) { this.rec.stop(); return }
    if (this.sending.value || this.hearing.value) return
    this.error.value = null
    let stream: MediaStream
    try { stream = await navigator.mediaDevices.getUserMedia({ audio: true }) } catch {
      this.error.value = 'Нет доступа к микрофону (разрешите его в настройках браузера)'
      return
    }
    const type = ['audio/mp4', 'audio/webm;codecs=opus', 'audio/webm'].find((t) => window.MediaRecorder?.isTypeSupported?.(t)) ?? ''
    const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined)
    const chunks: Blob[] = []
    rec.ondataavailable = (e) => { if (e.data.size) chunks.push(e.data) }
    rec.onstop = () => {
      stream.getTracks().forEach((t) => t.stop())
      this.rec = null
      this.recording.value = false
      void this.voiceSend(new Blob(chunks, { type: rec.mimeType || type || 'audio/webm' }))
    }
    rec.start()
    this.rec = rec
    this.recording.value = true
  }

  private async voiceSend(blob: Blob) {
    if (blob.size < 2000) return
    this.hearing.value = true
    try {
      const stt = await import('./importer/stt')
      const p = stt.sttProvider(prefs.sttProvider.value)
      const whisper = p.id === 'groq' || p.id === 'openai'
      const ext = blob.type.includes('mp4') ? 'm4a' : blob.type.includes('ogg') ? 'ogg' : 'webm'
      // Whisper detects the language itself (a question may be Russian or in the lesson's language)
      const t = await stt.transcribe({ blob, name: `voice.${ext}`, mp3: null, pcm: null }, whisper ? '' : (prefs.trLang.value.trim() || 'ru'),
        p, stt.sttKeyFor(p), stt.sttModel(p.id), () => {})
      const q = t.text.trim()
      log('chat', `Голосовой вопрос: ${q.length} знаков`)
      if (!q) { this.error.value = 'Речь не распознана'; return }
      this.input.value = q
      this.hearing.value = false
      await this.send(true)
    } catch (e) {
      this.error.value = (e as Error).message
    } finally { this.hearing.value = false }
  }

  private async answer() {
    this.sending.value = true
    try {
      const a = await gemini.generateFull(this.system, this.messages.value.map(({ user, text }) => ({ user, text })), false, this.fast)
      this.messages.value = [...this.messages.value, { user: false, text: a.text, reasoning: a.reasoning }]
    } catch (e) {
      this.error.value = errorText(e)
      geminiFailed(e, () => void this.retry())
    }
    this.sending.value = false
  }
}
