// Settings: a list of sections, each opens its own page. Every change is saved at once. Port of Settings.kt.
import { useState } from 'preact/hooks'
import { Icon, type IconName } from '../icons'
import { back, errorText, go, say } from '../app'
import { prefs, trLang } from '../settings'
import { Prompts } from '../prompts'
import * as api from '../lingq'
import * as gemini from '../gemini'
import { Dialog, IconButton, ListItem, Loading, SecretField, TextField, TopBar } from '../ui'

declare const __BUILD__: string

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
          summary={(gem ? `Gemini · ${prefs.geminiModel.value}` : 'LingQ') + ` → ${trLang()}`}
          onClick={() => go({ k: 'settingsTranslation' })} />
        <Section icon="star" title="Промпты Gemini" summary="Как Gemini переводит и отвечает в чате"
          onClick={() => go({ k: 'settingsPrompts' })} />
        <hr />
        <ListItem
          leading={<Icon name="info" />} headline="LingQ Fix для iPad"
          supporting={`Сборка ${__BUILD__} · обновляется сама при открытии`}
        />
      </div>
      <Hint>
        Ключи и настройки хранятся только на этом устройстве (в браузере). Ключ LingQ уходит только в LingQ
        (через прокси этого сайта), ключ Gemini — только в Google.
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
  const [picking, setPicking] = useState(false)
  const pickModel = async () => {
    const key = prefs.geminiKey.value.trim()
    if (!key) { say('Сначала вставьте ключ Gemini'); return }
    setPicking(true)
    setModels(null)
    try {
      setModels(await gemini.models(key))
    } catch (e) {
      setPicking(false)
      say(errorText(e))
    }
  }
  const options: [string, string, string][] = [
    ['lingq', 'LingQ', 'Варианты пользователей LingQ, машинный перевод предложений'],
    ['gemini', 'Gemini', 'Перевод слова по смыслу предложения, чат. Нужен ключ'],
  ]
  return (
    <Page title="Перевод">
      <TextField label="Переводить на язык" value={prefs.trLang.value} hint="ru, en, uk …"
        onInput={(v) => { prefs.trLang.value = v.trim() }} />
      <h3 class="section-title">Переводчик</h3>
      {options.map(([id, title, hint]) => (
        <label key={id} class="radio-row">
          <input type="radio" name="translator" checked={prefs.translator.value === id}
            onChange={() => { prefs.translator.value = id }} />
          <span><span class="radio-title">{title}</span><Hint>{hint}</Hint></span>
        </label>
      ))}
      <hr />
      <h3 class="section-title">Gemini</h3>
      <SecretField label="Ключ Gemini API" value={prefs.geminiKey.value} onInput={(v) => { prefs.geminiKey.value = v.trim() }} />
      <a class="btn text link" href="https://aistudio.google.com/apikey" target="_blank" rel="noopener">Получить ключ бесплатно</a>
      <div class="flush">
        <ListItem headline="Модель" supporting={prefs.geminiModel.value} trailing={<Icon name="dropDown" />} onClick={() => void pickModel()} />
      </div>
      <Hint>flash — быстро, для перевода слов; pro — умнее, но медленнее</Hint>
      {picking && (
        <Dialog title="Модель Gemini" onClose={() => setPicking(false)}
          actions={<button class="btn text" onClick={() => setPicking(false)}>Закрыть</button>}>
          {!models ? <Loading /> : (
            <div class="dialog-list">
              {models.map((m) => (
                <label key={m} class="radio-row">
                  <input type="radio" checked={m === prefs.geminiModel.value}
                    onChange={() => { prefs.geminiModel.value = m; setPicking(false) }} />
                  <span>{m}</span>
                </label>
              ))}
            </div>
          )}
        </Dialog>
      )}
    </Page>
  )
}

export function SettingsPrompts() {
  const fields: [keyof typeof prefs, string][] = [
    ['promptWord', 'Перевод слова'],
    ['promptSentence', 'Перевод предложения'],
    ['promptRetranslate', 'Повторный перевод ({current} — текущий)'],
    ['promptChatSentence', 'Чат о предложении'],
    ['promptChatText', 'Чат о всём тексте'],
  ]
  const reset = () => {
    prefs.promptWord.value = Prompts.WORD
    prefs.promptSentence.value = Prompts.SENTENCE
    prefs.promptRetranslate.value = Prompts.RETRANSLATE
    prefs.promptChatSentence.value = Prompts.CHAT_SENTENCE
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
