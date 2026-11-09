import { signal, effect, type Signal } from '@preact/signals'
import { Prompts } from './prompts'

export const DEFAULT_MODEL = 'gemini-3.8-flash'

/** A setting kept in localStorage (on this device only) and saved on every change. */
function pref(key: string, def: string, fix: (v: string) => string = (v) => v): Signal<string> {
  let v = def
  try { v = localStorage.getItem('pref.' + key) ?? def } catch { /* private mode */ }
  const s = signal(fix(v))
  effect(() => {
    try { localStorage.setItem('pref.' + key, s.value) } catch { /* ignore */ }
  })
  return s
}

export const prefs = {
  apiKey: pref('apiKey', ''),
  lang: pref('lang', 'de'),
  trLang: pref('trLang', 'ru'),
  /** "lingq", "gemini" or "openai" (another AI): who translates words and sentences in the reader. */
  translator: pref('translator', 'lingq'),
  geminiKey: pref('geminiKey', ''),
  // gemini-2.5-* are retired for new users
  geminiModel: pref('geminiModel', DEFAULT_MODEL, (v) => (v.startsWith('gemini-2.5') ? DEFAULT_MODEL : v)),
  /** Light model for the many word / phrase / sentence requests; '' = the same as [geminiModel]. */
  /** Reader page colours ('system' or a key of READER_THEMES), colour of chosen words, keeping the screen on. */
  readerTheme: pref('readerTheme', 'system'),
  highlight: pref('highlight', 'green'),
  keepAwake: pref('keepAwake', 'system'),
  /** "Другой ИИ": an OpenAI-compatible service (address, key, main and light model). */
  aiBase: pref('aiBase', 'https://api.deepseek.com'),
  aiKey: pref('aiKey', ''),
  aiModel: pref('aiModel', 'deepseek-chat'),
  aiFastModel: pref('aiFastModel', ''),
  /** Sync with the phone through the private GitHub repo: fine-grained token (Contents: read and write), on/off. */
  ghToken: pref('ghToken', ''),
  syncOn: pref('syncOn', '0'),
  /** Password the keys are encrypted with for sync (only in this browser; blank = keys are not synced). */
  syncPass: pref('syncPass', ''),
  geminiFastModel: pref('geminiFastModel', '', (v) => (v.startsWith('gemini-2.5') ? '' : v)),
  promptWord: pref('promptWord', Prompts.WORD),
  promptSentence: pref('promptSentence', Prompts.SENTENCE),
  promptRetranslate: pref('promptRetranslate', Prompts.RETRANSLATE),
  promptChatSentence: pref('promptChatSentence', Prompts.CHAT_SENTENCE),
  promptChatWord: pref('promptChatWord', Prompts.CHAT_WORD),
  promptChatText: pref('promptChatText', Prompts.CHAT_TEXT),
  promptClean: pref('promptClean', Prompts.CLEAN),
  /** Import: quick clean right after pasting / opening a file. */
  autoClean: pref('autoClean', '1'),
  readerFont: pref('readerFont', '20'),
  /** '1': the word window is docked at the right of the reader and always shown. */
  dockPanel: pref('dockPanel', '0'),
  /** Sentence mode: '1' = the sentence translation is shown under the sentence. */
  smTranslation: pref('smTranslation', '1'),
}

/** Prompt with fallback to the default when the field was emptied. */
export const prompt = {
  word: () => prefs.promptWord.value.trim() || Prompts.WORD,
  sentence: () => prefs.promptSentence.value.trim() || Prompts.SENTENCE,
  retranslate: () => prefs.promptRetranslate.value.trim() || Prompts.RETRANSLATE,
  chatSentence: () => prefs.promptChatSentence.value.trim() || Prompts.CHAT_SENTENCE,
  chatWord: () => prefs.promptChatWord.value.trim() || Prompts.CHAT_WORD,
  chatText: () => prefs.promptChatText.value.trim() || Prompts.CHAT_TEXT,
  clean: () => prefs.promptClean.value.trim() || Prompts.CLEAN,
}

export const lang = () => prefs.lang.value.trim() || 'de'
export const trLang = () => prefs.trLang.value.trim() || 'ru'
/** "Другой ИИ" is the translator (an OpenAI-compatible service instead of Gemini). */
export const otherAi = () => prefs.translator.value === 'openai'
/** A language model translates (Gemini, or another AI) and its key is there. */
export const useGemini = () =>
  (prefs.translator.value === 'gemini' && prefs.geminiKey.value.trim() !== '') ||
  (otherAi() && prefs.aiKey.value.trim() !== '' && prefs.aiBase.value.trim() !== '')
/** Name of the language model in use, for messages. */
export const aiName = () => (otherAi() ? 'ИИ' : 'Gemini')
