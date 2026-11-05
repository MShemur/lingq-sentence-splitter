// Chat with Gemini about the text or one sentence. Port of ChatScreen in Chat.kt.
import { useEffect, useRef } from 'preact/hooks'
import { Markdown } from '../markdown'
import { back } from '../app'
import type { ChatSession } from '../chat'
import { Icon } from '../icons'
import { IconButton, Spinner, TopBar } from '../ui'

const SUGGESTIONS = {
  text: ['Разбери грамматику', 'Объясни трудные слова', 'Переведи дословно', 'Приведи похожие примеры'],
  sentence: ['Разбери грамматику', 'Объясни трудные слова', 'Переведи дословно', 'Приведи похожие примеры'],
  word: ['Что значит здесь?', 'Другие значения', 'Начальная форма и грамматика', 'Примеры употребления', 'Синонимы'],
}
const LABEL = { text: 'Текст', sentence: 'Предложение', word: 'Слово' }

export function ChatScreen({ c }: { c: ChatSession }) {
  const end = useRef<HTMLDivElement>(null)
  const msgs = c.messages.value
  const sending = c.sending.value
  useEffect(() => { if (msgs.length) end.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }) }, [msgs.length, sending])

  return (
    <div class="screen chat">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title={c.title} />
      <div class="screen-body scroll">
        <div class="content chat-list">
          <div class="card outlined">
            <div class="card-label">{LABEL[c.kind]}</div>
            <div class="clamp6">{c.context}</div>
          </div>
          {!msgs.length && (
            <div class="chips">
              {SUGGESTIONS[c.kind].map((q) => (
                <button key={q} class="chip" onClick={() => { c.input.value = q; void c.send() }}>{q}</button>
              ))}
            </div>
          )}
          {msgs.map((m, i) => (
            <div key={i} class={'bubble ' + (m.user ? 'user' : 'model')}>
              {m.user ? m.text : <Markdown text={m.text} />}
            </div>
          ))}
          {sending && <div class="typing"><Spinner size={18} /> Gemini пишет…</div>}
          {c.error.value && (
            <div class="chat-error">
              <p class="error-text">Ошибка: {c.error.value}</p>
              <button class="btn outlined" onClick={() => void c.retry()}>Повторить</button>
            </div>
          )}
          <div ref={end} />
        </div>
      </div>
      <form class="chat-input" onSubmit={(e) => { e.preventDefault(); void c.send() }}>
        <textarea
          rows={1} placeholder="Спросите о тексте…" value={c.input.value}
          onInput={(e) => {
            const t = e.target as HTMLTextAreaElement
            c.input.value = t.value
            t.style.height = 'auto'
            t.style.height = Math.min(t.scrollHeight, 140) + 'px'
          }}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void c.send() } }}
        />
        <button class="fab-send" type="submit" aria-label="Отправить" disabled={sending || !c.input.value.trim()}>
          <Icon name="send" />
        </button>
      </form>
    </div>
  )
}
