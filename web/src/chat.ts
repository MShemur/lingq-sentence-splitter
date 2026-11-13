// Conversation with Gemini about the whole text or one sentence ([system] holds that context). Port of ChatSession.
import { signal } from '@preact/signals'
import * as gemini from './gemini'
import type { Msg } from './gemini'
import { errorText, geminiFailed } from './app'
import { prefs } from './settings'
import { Dictation } from './voice'
import { log } from './applog'

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
    /** Chat about the text: the AI may mark words in the lesson ([[отметить: …]] / [[снять: …]]); returns what was done. */
    private onMark?: (mark: boolean, items: [string, string][]) => string,
  ) {}

  /** The AI's mark lines are taken out of the shown answer and done in the lesson; a "✓ …" line says what happened. */
  private applyMarks(a: string): string {
    const f = this.onMark
    if (!f) return a
    const re = /\[\[\s*(отметить|снять)\s*:([\s\S]*?)\]\]/gi
    const done: string[] = []
    for (const m of a.matchAll(re)) {
      const mark = m[1].toLowerCase() === 'отметить'
      const items = m[2].split(/[;\n]/).map((x) => x.trim()).filter(Boolean).map((x): [string, string] => {
        const i = x.indexOf('=')
        const w = (i < 0 ? x : x.slice(0, i)).trim().replace(/^[«»"']+|[«»"']+$/g, '')
        return [w, i < 0 ? '' : x.slice(i + 1).trim()]
      }).filter((x) => x[0])
      if (items.length) done.push(f(mark, items))
    }
    if (!done.length) return a
    log('chat', `ИИ: ${done.join('; ')}`)
    return a.replace(re, '').trimEnd() + '\n\n✓ ' + done.join('\n✓ ')
  }

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
      this.messages.value = [...this.messages.value, { user: false, text: this.applyMarks(a.text), reasoning: a.reasoning }]
    } catch (e) {
      this.error.value = errorText(e)
      geminiFailed(e, () => void this.retry())
    }
    this.sending.value = false
  }
}
