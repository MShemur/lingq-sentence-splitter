// Conversation with Gemini about the whole text or one sentence ([system] holds that context). Port of ChatSession.
import { signal } from '@preact/signals'
import * as gemini from './gemini'
import type { Msg } from './gemini'
import { errorText, geminiFailed } from './app'

export class ChatSession {
  messages = signal<Msg[]>([])
  input = signal('')
  sending = signal(false)
  error = signal<string | null>(null)

  /** [kind]: what the chat is about (card label and suggestion chips). */
  constructor(
    readonly title: string, private system: string, readonly context: string,
    readonly kind: 'text' | 'sentence' | 'word' = 'sentence',
  ) {}

  async send() {
    const q = this.input.value.trim()
    if (!q || this.sending.value) return
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

  private async answer() {
    this.sending.value = true
    try {
      const a = await gemini.generate(this.system, this.messages.value)
      this.messages.value = [...this.messages.value, { user: false, text: a }]
    } catch (e) {
      this.error.value = errorText(e)
      geminiFailed(e, () => void this.retry())
    }
    this.sending.value = false
  }
}
