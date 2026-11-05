// Conversation with Gemini about the whole text or one sentence ([system] holds that context). Port of ChatSession.
import { signal } from '@preact/signals'
import * as gemini from './gemini'
import type { Msg } from './gemini'
import { errorText } from './app'

export class ChatSession {
  messages = signal<Msg[]>([])
  input = signal('')
  sending = signal(false)
  error = signal<string | null>(null)

  constructor(readonly title: string, private system: string, readonly context: string, readonly wholeText = false) {}

  async send() {
    const q = this.input.value.trim()
    if (!q || this.sending.value) return
    this.input.value = ''
    this.error.value = null
    this.messages.value = [...this.messages.value, { user: true, text: q }]
    this.sending.value = true
    try {
      const a = await gemini.generate(this.system, this.messages.value)
      this.messages.value = [...this.messages.value, { user: false, text: a }]
    } catch (e) {
      this.error.value = errorText(e)
    }
    this.sending.value = false
  }
}
