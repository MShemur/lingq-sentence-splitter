// Conversation with Gemini about the whole text or one sentence ([system] holds that context). Port of ChatSession.
import { signal } from '@preact/signals'
import * as gemini from './gemini'
import type { Msg } from './gemini'
import { errorText, geminiFailed } from './app'
import { prefs } from './settings'
import { Dictation } from './voice'

export class ChatSession {
  messages = signal<Msg[]>([])
  input = signal('')
  sending = signal(false)
  error = signal<string | null>(null)
  /** Microphone: recording / recognising the spoken question. */
  voice = new Dictation()
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
    if (this.sending.value) return
    this.error.value = null
    await this.voice.toggle(async (q) => { this.input.value = q; await this.send(true) }, (m) => { this.error.value = m })
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
