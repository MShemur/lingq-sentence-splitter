// Settings: a list of sections, each opens its own page. Every change is saved at once. Port of Settings.kt.
import { useState } from 'preact/hooks'
import { Icon, type IconName } from '../icons'
import { back, errorText, go, say } from '../app'
import { prefs, trLang, useGemini } from '../settings'
import { AI_PRESETS } from '../openai'
import { Prompts } from '../prompts'
import * as api from '../lingq'
import * as gemini from '../gemini'
import { Chip, Dialog, IconButton, ListItem, Loading, SecretField, TextField, TopBar } from '../ui'
import { HIGHLIGHTS, KEEP_AWAKE, READER_THEMES, darkPage, highlightColors, highlightOf, themeOf } from '../readerLook'

declare const __BUILD__: string
declare const __VERSION__: string

function Page({ title, children }: { title: string; children: preact.ComponentChildren }) {
  return (
    <div class="screen">
      <TopBar nav={<IconButton icon="back" label="Назад" onClick={back} />} title={title} />
      <div class="screen-body scroll"><div class="content page">{children}</div></div>
    </div>
  )
}

const Hint = ({ children }: { children: preact.ComponentChildren }) => <p class="hint">{children}</p>

function Section(p: { icon: IconName; title: string; summary: string; onClick: () => void }) {
  return (
    <ListItem
      leading={<span class="primary"><Icon name={p.icon} /></span>}
      headline={p.title} supporting={p.summary} onClick={p.onClick}
      trailing={<Icon name="chevron" />}
    />
  )
}

export function SettingsRoot() {
  const gem = prefs.translator.value === 'gemini'
  return (
    <Page title="Настройки">
      <div class="flush">
        <Section icon="account" title="Аккаунт LingQ"
          summary={(prefs.apiKey.value.trim() ? 'Ключ задан' : 'Ключ не задан') + ` · язык ${prefs.lang.value}`}
          onClick={() => go({ k: 'settingsLingq' })} />
        <Section icon="edit" title="Перевод"
          summary={(gem ? `Gemini · ${prefs.geminiModel.value}` : prefs.translator.value === 'openai' ? `ИИ · ${prefs.aiModel.value}` : 'LingQ') + ` → ${trLang()}`}
          onClick={() => go({ k: 'settingsTranslation' })} />
        <Section icon="palette" title="Чтение"
          summary={`${themeOf(prefs.readerTheme.value)?.name ?? 'Цвета системы'} · выделение: ${highlightOf(prefs.highlight.value).name.toLowerCase()}`}
          onClick={() => go({ k: 'settingsReading' })} />
        <Section icon="star" title="Промпты ИИ" summary="Как Gemini или другой ИИ переводит и отвечает в чате"
          onClick={() => go({ k: 'settingsPrompts' })} />
        <hr />
        <ListItem
          leading={<Icon name="info" />} headline={`LingQ Fix для iPad v${__VERSION__}`}
          supporting={`Сборка ${__BUILD__} · обновляется сама при открытии`}
        />
      </div>
      <Hint>
        Ключи и настройки хранятся только на этом устройстве (в браузере). Ключ LingQ уходит только в LingQ
        (через прокси этого сайта), ключ Gemini — только в Google, ключ другого ИИ — только в его сервис.
      </Hint>
    </Page>
  )
}

export function SettingsLingq() {
  const [langs, setLangs] = useState<api.Language[] | null>(null)
  const [picking, setPicking] = useState(false)
  const pick = async () => {
    setPicking(true)
    setLangs(null)
    try {
      setLangs(await api.languages(prefs.apiKey.value.trim()))
    } catch (e) {
      setPicking(false)
      say(errorText(e))
    }
  }
  return (
    <Page title="Аккаунт LingQ">
      <SecretField label="API-ключ LingQ" value={prefs.apiKey.value} onInput={(v) => { prefs.apiKey.value = v.trim() }} />
      <a class="btn text link" href="https://www.lingq.com/accounts/apikey/" target="_blank" rel="noopener">Где взять ключ</a>
      <TextField
        label="Изучаемый язык" value={prefs.lang.value} onInput={(v) => { prefs.lang.value = v.trim() }}
        trailing={<button class="btn text" type="button" onClick={() => void pick()}>Выбрать</button>}
      />
      <Hint>Код языка LingQ, например de. Курсы в библиотеке показываются для этого языка.</Hint>
      {picking && (
        <Dialog title="Изучаемый язык" onClose={() => setPicking(false)}
          actions={<button class="btn text" onClick={() => setPicking(false)}>Закрыть</button>}>
          {!langs ? <Loading /> : (
            <div class="dialog-list">
              {langs.map((l) => (
                <label key={l.code} class="radio-row">
                  <input type="radio" checked={l.code === prefs.lang.value}
                    onChange={() => { prefs.lang.value = l.code; setPicking(false) }} />
                  <span>{l.title} ({l.code})</span>
                </label>
              ))}
            </div>
          )}
        </Dialog>
      )}
    </Page>
  )
}

export function SettingsTranslation() {
  const [models, setModels] = useState<string[] | null>(null)
  // which model is being picked: 'main' (chat, retranslation) or 'fast' (words, phrases, sentences)
  const [picking, setPicking] = useState<'main' | 'fast' | null>(null)
  const tr = prefs.translator.value
  const other = tr === 'openai'
  const main = other ? prefs.aiModel : prefs.geminiModel
  const light = other ? prefs.aiFastModel : prefs.geminiFastModel
  const pickModel = async (target: 'main' | 'fast') => {
    if (!useGemini()) { say('Сначала вставьте ключ'); return }
    setPicking(target)
    setModels(null)
    try {
      setModels(await gemini.llmModels())
    } catch (e) {
      setPicking(null)
      say(errorText(e))
    }
  }
  const sig = picking === 'fast' ? light : main
  const choose = (m: string) => { sig.value = m; setPicking(null) }
  const options: [string, string, string][] = [
    ['lingq', 'LingQ', 'Варианты пользователей LingQ, машинный перевод предложений'],
    ['gemini', 'Gemini', 'Перевод слова по смыслу предложения, чат. Нужен ключ Google'],
    ['openai', 'Другой ИИ', 'DeepSeek, Qwen, OpenRouter, Groq, Mistral — любой сервис с API как у OpenAI'],
  ]
  const base = prefs.aiBase.value.trim().replace(/\/+$/, '')
  return (
    <Page title="Перевод">
      <TextField label="Переводить на язык" value={prefs.trLang.value} hint="ru, en, uk …"
        onInput={(v) => { prefs.trLang.value = v.trim() }} />
      <h3 class="section-title">Переводчик</h3>
      {options.map(([id, title, hint]) => (
        <label key={id} class="radio-row">
          <input type="radio" name="translator" checked={tr === id}
            onChange={() => { prefs.translator.value = id }} />
          <span><span class="radio-title">{title}</span><Hint>{hint}</Hint></span>
        </label>
      ))}
      {tr !== 'lingq' && (
        <>
          <hr />
          {!other ? (
            <>
              <h3 class="section-title">Gemini</h3>
              <SecretField label="Ключ Gemini API" value={prefs.geminiKey.value} onInput={(v) => { prefs.geminiKey.value = v.trim() }} />
              <a class="btn text link" href="https://aistudio.google.com/apikey" target="_blank" rel="noopener">Получить ключ бесплатно</a>
            </>
          ) : (
            <>
              <h3 class="section-title">Сервис</h3>
              <div class="chips">
                {AI_PRESETS.map(([name, url]) => (
                  <Chip key={name} label={name} selected={base === url} onClick={() => { prefs.aiBase.value = url }} />
                ))}
              </div>
              <TextField label="Адрес API" value={prefs.aiBase.value} onInput={(v) => { prefs.aiBase.value = v.trim() }} />
              <SecretField label="Ключ API" value={prefs.aiKey.value} onInput={(v) => { prefs.aiKey.value = v.trim() }} />
              <Hint>
                Ключ берётся на сайте сервиса (platform.deepseek.com, openrouter.ai, console.groq.com…). Тексты уходят на
                серверы этого сервиса.
              </Hint>
            </>
          )}
          <div class="flush">
            <ListItem headline="Модель для чата и повторного перевода" supporting={main.value || 'не выбрана'}
              trailing={<Icon name="dropDown" />} onClick={() => void pickModel('main')} />
            <ListItem headline="Модель для слов и предложений" supporting={light.value || 'та же, что для чата'}
              trailing={<Icon name="dropDown" />} onClick={() => void pickModel('fast')} />
          </div>
          <Hint>
            {other
              ? 'Для слов и предложений — быстрая дешёвая модель (deepseek-chat, qwen-flash…). «Думающие» модели (reasoner, r1) — только для чата: они медленные, их рассуждения видны в чате.'
              : 'Слова и предложения — это много мелких запросов: возьмите для них лёгкую модель (flash-lite), у неё больше бесплатный лимит. Для чата — flash или pro.'}
          </Hint>
        </>
      )}
      {picking && (
        <Dialog title={picking === 'fast' ? 'Модель для слов и предложений' : 'Модель для чата'} onClose={() => setPicking(null)}
          actions={<button class="btn text" onClick={() => setPicking(null)}>Закрыть</button>}>
          {!models ? <Loading /> : (
            <div class="dialog-list">
              {(picking === 'fast' ? ['', ...models] : models).map((m) => (
                <label key={m} class="radio-row">
                  <input type="radio" checked={m === sig.value} onChange={() => choose(m)} />
                  <span>{m || 'Та же, что для чата'}</span>
                </label>
              ))}
            </div>
          )}
        </Dialog>
      )}
    </Page>
  )
}

/** Settings → Чтение: page colours, colour of chosen words, screen timeout. Port of Android SettingsReading. */
export function SettingsReading() {
  const theme = prefs.readerTheme.value
  const hl = prefs.highlight.value
  const t = themeOf(theme)
  const dark = darkPage()
  const c = highlightColors(hl, dark)
  const page = { background: t?.bg ?? 'var(--surface)', color: t?.fg ?? 'var(--on-surface)' }
  return (
    <Page title="Чтение">
      <h3 class="section-title">Фон страницы</h3>
      <div class="swatches">
        <button class={'swatch' + (theme === 'system' ? ' on' : '')} onClick={() => { prefs.readerTheme.value = 'system' }}>
          <span class="swatch-box">Aa</span><span class="swatch-name">Как в системе</span>
        </button>
        {READER_THEMES.map((x) => (
          <button key={x.key} class={'swatch' + (theme === x.key ? ' on' : '')} onClick={() => { prefs.readerTheme.value = x.key }}>
            <span class="swatch-box" style={{ background: x.bg, color: x.fg }}>Aa</span><span class="swatch-name">{x.name}</span>
          </button>
        ))}
      </div>
      <h3 class="section-title">Цвет выделения слов</h3>
      <div class="swatches">
        {HIGHLIGHTS.map((h) => {
          const hc = highlightColors(h.key, dark)
          const on = hl === h.key
          return (
            <button key={h.key} class="hl-swatch" onClick={() => { prefs.highlight.value = h.key }}>
              <span class="hl-dot" style={{ background: `linear-gradient(${hc.bg}, ${hc.bg}), ${page.background}`, borderColor: on ? hc.text : undefined, borderWidth: on ? 3 : 1, color: hc.text }}>
                {on && <Icon name="check" size={20} />}
              </span>
              <span class="swatch-name">{h.name}</span>
            </button>
          )
        })}
      </div>
      <div class="hl-preview" style={page}>
        Er las das <span class="hl-word"><span style={{ color: c.text }}>книгу</span><span style={{ background: c.bg }}>Buch</span></span> bis zum Ende.
      </div>
      <h3 class="section-title">Экран не гаснет при чтении</h3>
      {KEEP_AWAKE.map(([k, label]) => (
        <label key={k} class="radio-row">
          <input type="radio" name="keepAwake" checked={prefs.keepAwake.value === k} onChange={() => { prefs.keepAwake.value = k }} />
          <span>{label}</span>
        </label>
      ))}
      <Hint>
        Пока урок открыт, экран не погаснет столько времени после последнего касания, даже если в iPad стоит меньше.
        {!('wakeLock' in navigator) && ' Этот браузер так не умеет — работает только «Как в системе».'}
      </Hint>
    </Page>
  )
}

export function SettingsPrompts() {
  const fields: [keyof typeof prefs, string][] = [
    ['promptWord', 'Перевод слова'],
    ['promptSentence', 'Перевод предложения'],
    ['promptRetranslate', 'Повторный перевод ({current} — текущий)'],
    ['promptChatSentence', 'Чат о предложении'],
    ['promptChatWord', 'Чат о слове («Спросить» в окне слова)'],
    ['promptChatText', 'Чат о всём тексте'],
  ]
  const reset = () => {
    prefs.promptWord.value = Prompts.WORD
    prefs.promptSentence.value = Prompts.SENTENCE
    prefs.promptRetranslate.value = Prompts.RETRANSLATE
    prefs.promptChatSentence.value = Prompts.CHAT_SENTENCE
    prefs.promptChatWord.value = Prompts.CHAT_WORD
    prefs.promptChatText.value = Prompts.CHAT_TEXT
    say('Стандартные промпты восстановлены')
  }
  return (
    <Page title="Промпты Gemini">
      <Hint>
        Подстановки: {'{lang}'} язык текста, {'{target}'} язык перевода, {'{word}'} слово, {'{sentence}'} предложение,
        {' {text}'} весь текст. Промпт слова должен просить ответ JSON с полями best, options, note.
      </Hint>
      {fields.map(([k, label]) => (
        <TextField key={k} label={label} multiline value={prefs[k].value} onInput={(v) => { prefs[k].value = v }} />
      ))}
      <button class="btn outlined" onClick={reset}>Вернуть стандартные</button>
    </Page>
  )
}
