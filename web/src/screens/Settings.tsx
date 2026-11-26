// Settings: a list of sections, each opens its own page. Every change is saved at once. Port of Settings.kt.
import { useState } from 'preact/hooks'
import { Icon, type IconName } from '../icons'
import { back, errorText, go, say } from '../app'
import { prefs, trLang, useGemini } from '../settings'
import { AI_PRESETS } from '../openai'
import { keysNote, lastSync, setSyncPass, syncError, syncNow, syncRunning } from '../sync'

import { Prompts } from '../prompts'
import * as api from '../lingq'
import * as gemini from '../gemini'
import { Chip, Dialog, IconButton, ListItem, Loading, SecretField, Spinner, TextField, TopBar } from '../ui'
import { FACES, HIGHLIGHTS, KEEP_AWAKE, READER_THEMES, darkPage, grainImage, highlightColors, highlightOf, themeOf } from '../readerLook'
import { groqSummary, STT_PRICES_CHECKED, STT_PROVIDERS, setSttKey, setSttModel, sttCost, sttHourPrice, sttKey, sttModel, sttPrice, sttProvider } from '../importer/stt'
import { clearLog, lastLines, log, logText, logVersion } from '../applog'
import { geminiTtsModels, ttsKeyFor, ttsSpeak, ttsSpeaking, ttsStop, ttsSystemVoiceName, ttsVoicesReady, type TtsProvider } from '../tts'

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

/** Small primary header of a group of settings rows. */
const Group = ({ children }: { children: preact.ComponentChildren }) => <div class="settings-group">{children}</div>

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
        <Group>Сервисы</Group>
        <Section icon="account" title="Аккаунт LingQ"
          summary={(prefs.apiKey.value.trim() ? 'Ключ задан' : 'Ключ не задан') + ` · язык ${prefs.lang.value}`}
          onClick={() => go({ k: 'settingsLingq' })} />
        <Section icon="edit" title="Перевод и ИИ"
          summary={(gem ? `Gemini · ${prefs.geminiModel.value}` : prefs.translator.value === 'openai' ? `ИИ · ${prefs.aiModel.value}` : 'LingQ') + ` → ${trLang()}`}
          onClick={() => go({ k: 'settingsTranslation' })} />
        <Section icon="mic" title="Распознавание речи"
          summary={`${sttProvider(prefs.sttProvider.value).name} · ${sttKey(sttProvider(prefs.sttProvider.value).id) ? 'ключ задан' : 'ключ не задан'}`}
          onClick={() => go({ k: 'settingsSpeech' })} />
        <Section icon="play" title="Озвучка"
          summary={`${TTS_NAMES[ttsCur()]}${ttsCur() === 'system' ? '' : ttsKeyFor(ttsCur()) ? ' · ключ задан' : ' · ключ не задан'}`}
          onClick={() => go({ k: 'settingsTts' })} />
        <Group>Чтение и звук</Group>
        <Section icon="palette" title="Чтение"
          summary={`${themeOf(prefs.readerTheme.value)?.name ?? 'Цвета системы'} · выделение: ${highlightOf(prefs.highlight.value).name.toLowerCase()}`}
          onClick={() => go({ k: 'settingsReading' })} />
        <Group>Данные</Group>
        <Section icon="refresh" title="Синхронизация"
          summary={prefs.syncOn.value === '1' ? (syncError.value ? 'Ошибка' : 'Включена') : 'Выключена'}
          onClick={() => go({ k: 'settingsSync' })} />
        <Section icon="history" title="Резервные копии" summary="Вернуть урок к версии до исправления"
          onClick={() => go({ k: 'backups' })} />
        <Group>Приложение</Group>
        <ListItem
          leading={<span class="primary"><Icon name="info" /></span>} headline="Обновления"
          supporting={`SatzLoop для iPad v${__VERSION__} · сборка ${__BUILD__} · обновляется сама`}
        />
        <Section icon="history" title="Журнал действий" summary="Для сообщения об ошибке · хранится 2 дня"
          onClick={() => go({ k: 'log' })} />
        <Section icon="chat" title="Отзыв / задача" summary="Написать, что поправить; Claude отвечает здесь"
          onClick={() => go({ k: 'feedback' })} />
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
    <Page title="Перевод и ИИ">
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
      <div class="flush">
        <ListItem headline="Промпты ИИ" supporting="Как Gemini или другой ИИ переводит и отвечает в чате"
          leading={<span class="primary"><Icon name="star" /></span>} trailing={<Icon name="chevron" />}
          onClick={() => go({ k: 'settingsPrompts' })} />
      </div>
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

/** Settings → Распознавание речи: service, key, model. Port of Android SettingsSpeech. */
export function SettingsSpeech() {
  const sp = sttProvider(prefs.sttProvider.value)
  void prefs.sttKeys.value; void prefs.sttModels.value // re-render on changes
  const key = sttKey(sp.id)
  const model = sttModel(sp.id) || sp.models[0]
  return (
    <Page title="Распознавание речи">
      <Hint>
        Аудио без текста (подкаст, запись) превращается в урок: текст распознаётся, аудио загружается в LingQ вместе с
        ним, у каждого предложения своё время. Загрузка → Аудио.
      </Hint>
      <h3 class="section-title">Сервис</h3>
      {STT_PROVIDERS.map((p) => (
        <label key={p.id} class="radio-row">
          <input type="radio" name="sttProvider" checked={sp.id === p.id} onChange={() => { prefs.sttProvider.value = p.id }} />
          <span><span class="radio-title">{p.name}{sttKey(p.id) ? ' · ключ задан' : ''}</span><Hint>от {sttHourPrice(Math.min(...p.prices.map((x) => x[1])))} · {p.note}</Hint></span>
        </label>
      ))}
      <hr />
      <SecretField label={`Ключ ${sp.name}`} value={key} onInput={(v) => setSttKey(sp.id, v)} />
      {sp.id === 'groq' && !key && prefs.aiBase.value.includes('groq.com') && prefs.aiKey.value.trim() && (
        <Hint>Пусто: будет взят ключ Groq из настроек перевода.</Hint>
      )}
      <a class="btn text link" href={sp.keyUrl} target="_blank" rel="noopener">Получить ключ {sp.name}</a>
      <h3 class="section-title">Модель</h3>
      <div class="chips">
        {sp.models.map((m) => <Chip key={m} label={m} selected={model === m} onClick={() => setSttModel(sp.id, m)} />)}
      </div>
      <TextField label="Модель" value={model} onInput={(v) => setSttModel(sp.id, v)} />
      {sttPrice(sp, model) != null && (
        <Hint>{model}: {sttHourPrice(sttPrice(sp, model)!)}, 10 минут {sttCost(sp, model, 10)}. Цены проверены {STT_PRICES_CHECKED}.</Hint>
      )}
      <a class="btn text link" href={sp.priceUrl} target="_blank" rel="noopener">Цены {sp.name} на сайте</a>
      {sp.id === 'groq' && (
        <>
          <Hint>{groqSummary()}</Hint>
          <a class="btn text link" href="https://console.groq.com/settings/limits" target="_blank" rel="noopener">Лимиты Groq на сайте</a>
        </>
      )}
      <Hint>
        Аудио уходит в выбранный сервис через прокси этого сайта (он ничего не хранит). Ключи синхронизируются с
        телефоном зашифрованными, если задан пароль синхронизации.
      </Hint>
    </Page>
  )
}

const TTS_NAMES: Record<TtsProvider, string> = { system: 'Системный голос', gemini: 'Gemini', openai: 'OpenAI', elevenlabs: 'ElevenLabs' }
const TTS_GEMINI_VOICES = ['Kore', 'Puck', 'Charon', 'Fenrir', 'Aoede', 'Leda', 'Orus', 'Zephyr']
const TTS_OPENAI_VOICES = ['alloy', 'ash', 'coral', 'echo', 'nova', 'onyx', 'sage', 'shimmer']
const TTS_OPENAI_MODELS = ['gpt-4o-mini-tts', 'tts-1', 'tts-1-hd']
const TTS_SAMPLE = 'Guten Tag! Das ist ein Beispiel.'

/** The provider in use; an unknown saved value counts as the system voice. */
const ttsCur = (): TtsProvider => {
  const v = prefs.ttsProvider.value
  return v === 'gemini' || v === 'openai' || v === 'elevenlabs' ? v : 'system'
}

/** Settings → Озвучка: provider, voice, Gemini model, speed, a sample. Keys live in their own screens. */
export function SettingsTts() {
  void ttsVoicesReady.value // re-render when the device's voices are loaded
  const cur = ttsCur()
  const [models, setModels] = useState<string[] | null>(null)
  const [loadingModels, setLoadingModels] = useState(false)
  const loadModels = async () => {
    setLoadingModels(true)
    try {
      setModels(await geminiTtsModels())
    } catch (e) {
      say(errorText(e))
    } finally {
      setLoadingModels(false)
    }
  }
  const key = cur === 'system' ? '' : ttsKeyFor(cur)
  const rate = Number(prefs.ttsRate.value) || 1
  const keyWhere = cur === 'gemini' ? '«Перевод и ИИ»' : '«Распознавание речи»'
  const voiceName = ttsSystemVoiceName(prefs.lang.value || 'de')
  return (
    <Page title="Озвучка">
      <Hint>
        Голос для чтения вслух. Системный голос бесплатен и работает без интернета. Голоса Gemini, OpenAI и ElevenLabs
        звучат естественнее, но это платные сервисы: нужен ключ этого сервиса.
      </Hint>
      <h3 class="section-title">Голос</h3>
      {(['system', 'gemini', 'openai', 'elevenlabs'] as TtsProvider[]).map((p) => (
        <label key={p} class="radio-row">
          <input type="radio" name="ttsProvider" checked={cur === p} onChange={() => { prefs.ttsProvider.value = p; ttsStop() }} />
          <span>
            <span class="radio-title">{TTS_NAMES[p]}{p !== 'system' && ttsKeyFor(p) ? ' · ключ задан' : ''}</span>
          </span>
        </label>
      ))}
      <hr />
      {cur === 'system' && (
        <Hint>
          {voiceName ? `Голос для «${prefs.lang.value || 'de'}»: ${voiceName}.` : `Голос для «${prefs.lang.value || 'de'}» не найден: будет голос по умолчанию устройства.`}
          {' '}Другой голос — в настройках iPad: «Универсальный доступ» → «Устный контент» → «Голоса».
        </Hint>
      )}
      {cur !== 'system' && (
        <Hint>
          {key ? `Ключ ${TTS_NAMES[cur]} задан и берётся из ${keyWhere}.` : `Ключ ${TTS_NAMES[cur]} не задан: вставьте его в ${keyWhere}. Пока без ключа звучит системный голос.`}
        </Hint>
      )}
      {cur === 'gemini' && (
        <>
          <h3 class="section-title">Голос Gemini</h3>
          <div class="chips">
            {TTS_GEMINI_VOICES.map((v) => (
              <Chip key={v} label={v} selected={prefs.ttsVoice_gemini.value === v} onClick={() => { prefs.ttsVoice_gemini.value = v }} />
            ))}
          </div>
          <h3 class="section-title">Модель Gemini</h3>
          <div class="chips">
            <Chip label="Самая новая (по умолчанию)" selected={prefs.ttsModel_gemini.value === ''} onClick={() => { prefs.ttsModel_gemini.value = '' }} />
            {(models ?? (prefs.ttsModel_gemini.value.trim() ? [prefs.ttsModel_gemini.value.trim()] : [])).map((m) => (
              <Chip key={m} label={m} selected={prefs.ttsModel_gemini.value === m} onClick={() => { prefs.ttsModel_gemini.value = m }} />
            ))}
          </div>
          <button class="btn text" type="button" disabled={loadingModels} onClick={() => void loadModels()}>
            {loadingModels ? 'Загружаю…' : 'Показать модели Gemini для озвучки'}
          </button>
        </>
      )}
      {cur === 'openai' && (
        <>
          <h3 class="section-title">Голос OpenAI</h3>
          <div class="chips">
            {TTS_OPENAI_VOICES.map((v) => (
              <Chip key={v} label={v} selected={prefs.ttsVoice_openai.value === v} onClick={() => { prefs.ttsVoice_openai.value = v }} />
            ))}
          </div>
          <h3 class="section-title">Модель OpenAI</h3>
          <div class="chips">
            {TTS_OPENAI_MODELS.map((m) => (
              <Chip key={m} label={m} selected={prefs.ttsModel_openai.value === m} onClick={() => { prefs.ttsModel_openai.value = m }} />
            ))}
          </div>
        </>
      )}
      {cur === 'elevenlabs' && (
        <>
          <TextField label="ID голоса ElevenLabs" value={prefs.ttsVoice_elevenlabs.value}
            onInput={(v) => { prefs.ttsVoice_elevenlabs.value = v.trim() }} />
          <Hint>ID голоса (Voice ID) берётся в библиотеке голосов ElevenLabs. Модель: eleven_multilingual_v2.</Hint>
        </>
      )}
      <h3 class="section-title">Скорость речи</h3>
      <div class="spacing-row">
        <input type="range" min={0.5} max={1.5} step={0.1} value={rate} aria-label="Скорость речи"
          onInput={(e) => { prefs.ttsRate.value = (e.target as HTMLInputElement).value }} />
        <span class="spacing-val">{rate.toFixed(1).replace('.', ',')}×</span>
      </div>
      <div class="chips">
        <button class="btn tonal" type="button" onClick={() => ttsSpeak(TTS_SAMPLE, 'de')}>Прослушать пример</button>
        {ttsSpeaking.value && <button class="btn outlined" type="button" onClick={() => ttsStop()}>Остановить</button>}
      </div>
      <Hint>
        Облачные голоса отправляют текст только в свой сервис. Готовая озвучка хранится в этом браузере (до 200 фраз).
      </Hint>
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
  const page = { background: t?.bg ?? 'var(--surface)', backgroundImage: grainImage(t?.grain) || undefined, color: t?.fg ?? 'var(--on-surface)' }
  return (
    <Page title="Чтение">
      <Group>Страница</Group>
      <h3 class="section-title">Фон страницы</h3>
      <div class="swatches">
        <button class={'swatch' + (theme === 'system' ? ' on' : '')} onClick={() => { prefs.readerTheme.value = 'system' }}>
          <span class="swatch-box">Aa</span><span class="swatch-name">Как в системе</span>
        </button>
        {READER_THEMES.map((x) => (
          <button key={x.key} class={'swatch' + (theme === x.key ? ' on' : '')} onClick={() => { prefs.readerTheme.value = x.key }}>
            <span class="swatch-box" style={{ background: x.bg, backgroundImage: grainImage(x.grain) || undefined, color: x.fg }}>Aa</span><span class="swatch-name">{x.name}</span>
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
      <div class="chips">
        {[['1', 'Слово на цветном фоне'], ['0', 'Без цвета: цветной только перевод над словом']].map(([v, label]) => (
          <Chip key={v} label={label} selected={(prefs.wordBg.value || '1') === v} onClick={() => { prefs.wordBg.value = v }} />
        ))}
      </div>
      <div class="hl-preview" style={page}>
        Er las das <span class="hl-word"><span style={{ color: c.text }}>книгу</span><span style={{ background: prefs.wordBg.value === '0' ? 'none' : c.bg }}>Buch</span></span> bis zum Ende.
      </div>
      <Group>Текст</Group>
      <h3 class="section-title">Шрифт текста</h3>
      <div class="chips">
        {FACES.map(([v, label, css]) => (
          <Chip key={v} label={<span style={css ? { fontFamily: css } : undefined}>{label}</span>} selected={prefs.readerFace.value === v} onClick={() => { prefs.readerFace.value = v }} />
        ))}
      </div>
      <h3 class="section-title">Выравнивание текста</h3>
      <div class="chips">
        {[['0', 'По левому краю'], ['1', 'По ширине']].map(([v, label]) => (
          <Chip key={v} label={label} selected={(prefs.readerJustify.value || '0') === v} onClick={() => { prefs.readerJustify.value = v }} />
        ))}
      </div>
      <h3 class="section-title">Расстояние между строками</h3>
      <div class="spacing-row">
        <input type="range" min={70} max={200} step={10} value={Number(prefs.lineSpacing.value) || 100} aria-label="Расстояние между строками"
          onInput={(e) => { prefs.lineSpacing.value = (e.target as HTMLInputElement).value }} />
        <span class="spacing-val">{(Number(prefs.lineSpacing.value) || 100) === 100 ? 'обычное' : `${prefs.lineSpacing.value} %`}</span>
      </div>
      <h3 class="section-title">Расстояние между абзацами</h3>
      <div class="spacing-row">
        <input type="range" min={0} max={48} step={4} value={Number(prefs.paraGap.value) >= 0 ? Number(prefs.paraGap.value) : prefs.readerBook.value === '1' ? 0 : 16}
          aria-label="Расстояние между абзацами" onInput={(e) => { prefs.paraGap.value = (e.target as HTMLInputElement).value }} />
        <span class="spacing-val">{Number(prefs.paraGap.value) >= 0 ? `${prefs.paraGap.value} px` : 'обычное'}</span>
      </div>
      <h3 class="section-title">Отступ абзаца</h3>
      <div class="spacing-row">
        <input type="range" min={0} max={3} step={0.25} value={Number(prefs.paraIndent.value) >= 0 ? Number(prefs.paraIndent.value) : 1.5}
          aria-label="Отступ абзаца" onInput={(e) => { prefs.paraIndent.value = (e.target as HTMLInputElement).value }} />
        <span class="spacing-val">{Number(prefs.paraIndent.value) > 0 ? `${String(Number(prefs.paraIndent.value)).replace('.', ',')} em` : 'нет'}</span>
      </div>
      {(prefs.lineSpacing.value !== '100' || prefs.paraGap.value !== '-1') && (
        <div class="chips">
          <Chip label="Вернуть обычные расстояния" onClick={() => { prefs.lineSpacing.value = '100'; prefs.paraGap.value = '-1' }} />
        </div>
      )}
      <h3 class="section-title">Расстояние до перевода над словом</h3>
      <div class="chips">
        {[['-6', 'Вплотную'], ['-4', 'Ближе'], ['-2', 'Чуть ближе'], ['0', 'Обычно'], ['3', 'Дальше']].map(([v, label]) => (
          <Chip key={v} label={label} selected={(prefs.pinGap.value || '0') === v} onClick={() => { prefs.pinGap.value = v }} />
        ))}
      </div>
      <div class="hl-preview reader-text pin-preview" style={{ ...page, '--pin-gap': (Number(prefs.pinGap.value) || 0) + 'px', '--green': c.text, '--green-bg': c.bg } as any}>
        Er las das <span class="w sel"><span class="pin">книгу</span><span class="wt">Buch</span></span> bis zum Ende.
      </div>
      <Group>Режим</Group>
      <label class="switch-row">
        <span class="grow radio-title">Книжный режим</span>
        <input type="checkbox" class="switch" checked={prefs.readerBook.value === '1'}
          onChange={(e) => { prefs.readerBook.value = (e.target as HTMLInputElement).checked ? '1' : '0' }} />
      </label>
      <p class="hint">Текст на весь экран, без панелей и кнопок; страницы листаются свайпом, в альбомной ориентации — две
        рядом. Тап по середине страницы показывает верхнюю панель (там ⋮ → «Книжный режим» выключает его), двойной тап по
        слову — меню слова. Абзацы — отступом первой строки, перевод над словом не раздвигает строки; предложение, как в
        книге, может продолжаться на следующей странице.</p>
      <Group>Экран</Group>
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

/** Settings → Синхронизация with the phone (port of Android SettingsSync). */
export function SettingsSync() {
  const on = prefs.syncOn.value === '1'
  const token = prefs.ghToken.value.trim()
  const running = syncRunning.value
  const err = syncError.value
  const last = lastSync.value
  return (
    <Page title="Синхронизация">
      <p>
        Закладки, выбранные переводы слов, место чтения, «Прочитано N %» и статистика — общие для телефона и iPad.
        Хранятся в вашем приватном репозитории GitHub (ветка sync). Ключи — только зашифрованными, если задан пароль ниже.
      </p>
      <label class="switch-row">
        <span class="grow radio-title">Синхронизировать</span>
        <input type="checkbox" class="switch" checked={on}
          onChange={(e) => { const v = (e.target as HTMLInputElement).checked; prefs.syncOn.value = v ? '1' : '0'; if (v) void syncNow() }} />
      </label>
      <p class={err && !running ? 'error-text' : 'hint'}>
        {running ? 'Синхронизация…'
          : err ? `Ошибка: ${err}`
          : last ? `Последняя синхронизация: ${new Date(last).toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' })}`
          : 'Ещё не синхронизировалось'}
      </p>
      <button class="btn filled" disabled={running || !token} onClick={() => void syncNow()}>
        {running ? <Spinner size={18} /> : 'Синхронизировать сейчас'}
      </button>
      <Hint>Сама запускается при открытии приложения и когда вы из него выходите.</Hint>
      <hr />
      <KeysPassword />
      <hr />
      <h3 class="section-title">Токен GitHub</h3>
      <SecretField label="Токен (fine-grained)" value={prefs.ghToken.value} onInput={(v) => { prefs.ghToken.value = v.trim() }} />
      <Hint>
        Тот же токен, что в Android-приложении для обновлений, но с правом записи: github.com → Settings → Developer
        settings → Fine-grained tokens → ваш токен → Repository permissions → Contents: «Read and write» → Update.
        Токен хранится только в этом браузере и уходит только в GitHub.
      </Hint>
      <a class="btn text link" href="https://github.com/settings/personal-access-tokens" target="_blank" rel="noopener">Открыть токены GitHub</a>
    </Page>
  )
}

/** Sync password: the keys go to the other device encrypted with it. */
function KeysPassword() {
  const [pass, setPass] = useState(prefs.syncPass.value)
  const changed = pass !== prefs.syncPass.value
  return (
    <>
      <h3 class="section-title">Ключи</h3>
      <Hint>
        Ключи LingQ, Gemini и другого ИИ (DeepSeek и т. п.) переходят на другое устройство зашифрованными этим паролем.
        Задайте одинаковый пароль на телефоне и на iPad. Пароль хранится только на устройстве; без него ключи на GitHub
        не прочитать. Забыли пароль — задайте новый, ключи зашифруются заново. GitHub-токен не передаётся: его нужно
        ввести на каждом устройстве.
      </Hint>
      <SecretField label="Пароль синхронизации ключей" value={pass} onInput={setPass} />
      {changed && (
        <button class="btn filled" onClick={() => {
          const p = pass.trim(); setPass(p); setSyncPass(p)
          if (prefs.syncOn.value === '1') void syncNow()
        }}>{pass.trim() ? 'Сохранить пароль' : 'Не синхронизировать ключи'}</button>
      )}
      {keysNote.value && <Hint>{keysNote.value}</Hint>}
    </>
  )
}

/** Settings → Журнал действий: copy or share it for a bug report. */
export function SettingsLog() {
  logVersion.value // refresh on every new line
  const shown = lastLines(400).reverse()
  const copy = async () => {
    log('log', 'Журнал скопирован')
    try { await navigator.clipboard.writeText(logText()); say('Журнал скопирован') } catch { say('Не удалось скопировать') }
  }
  const share = async () => {
    try { await navigator.share({ title: 'SatzLoop: журнал', text: logText() }) } catch { /* cancelled */ }
  }
  return (
    <Page title="Журнал действий">
      <p>
        Что происходило в приложении за последние 2 дня: экраны, нажатия, запросы, ошибки. Ключей и токенов здесь нет.
        Когда что-то пошло не так — скопируйте и пришлите в чат с Claude.
      </p>
      <div class="row-buttons">
        <button class="btn filled" onClick={() => void copy()}>Копировать</button>
        {'share' in navigator && <button class="btn tonal" onClick={() => void share()}>Поделиться</button>}
        <button class="btn text" onClick={clearLog}>Очистить</button>
      </div>
      <Hint>Новые сверху:</Hint>
      <pre class="applog">{shown.join('\n')}</pre>
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
    ['promptClean', 'Очистка текста при загрузке ({text} — кусок текста)'],
    ['promptLevel', 'Уровень текста (JSON: cefr, why)'],
  ]
  const reset = () => {
    prefs.promptWord.value = Prompts.WORD
    prefs.promptSentence.value = Prompts.SENTENCE
    prefs.promptRetranslate.value = Prompts.RETRANSLATE
    prefs.promptChatSentence.value = Prompts.CHAT_SENTENCE
    prefs.promptChatWord.value = Prompts.CHAT_WORD
    prefs.promptChatText.value = Prompts.CHAT_TEXT
    prefs.promptClean.value = Prompts.CLEAN
    prefs.promptLevel.value = Prompts.LEVEL
    say('Стандартные промпты восстановлены')
  }
  return (
    <Page title="Промпты ИИ">
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
