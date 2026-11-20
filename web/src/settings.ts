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
  /** PDF book, scanned pages read with Gemini: '1' = captions and boxes go into the lesson text too (each after the
   *  paragraph it stands in; page numbers not). On by default since web v1.76 (a new key: the old one was saved off). */
  pdfCaptions: pref('pdfAsides', '1'),
  // gemini-2.5-* are retired for new users
  geminiModel: pref('geminiModel', DEFAULT_MODEL, (v) => (v.startsWith('gemini-2.5') ? DEFAULT_MODEL : v)),
  /** Light model for the many word / phrase / sentence requests; '' = the same as [geminiModel]. */
  /** Reader page colours ('system' or a key of READER_THEMES), colour of chosen words, keeping the screen on. */
  readerTheme: pref('readerTheme', 'system'),
  highlight: pref('highlight', 'green'),
  keepAwake: pref('keepAwake', 'system'),
  /** px between a translation above a word and the word (negative = closer). */
  pinGap: pref('pinGap', '0'),
  /** Reading font: '' = system, else a font family name ("Avenir Next"). */
  readerFace: pref('readerFace', ''),
  /** '1': the reading text justified (both edges straight). */
  readerJustify: pref('readerJustify', '0'),
  /** Line spacing of the reading text, % of the usual (70..200). */
  lineSpacing: pref('lineSpacing', '100'),
  /** px between paragraphs (0..48); '-1' = usual (book mode 0, else 14). */
  paraGap: pref('paraGap', '-1'),
  /** First-line indent of a paragraph, em (0..3; '0' = none). Book mode and the usual one alike. */
  paraIndent: pref('paraIndent', '1.5'),
  /** '1': book mode (full screen, no bars, swipe only, paragraphs by indent, two pages in landscape). */
  readerBook: pref('readerBook', '0'),
  /** PDF book base zoom: 'width' = the page fills the screen width, 'page' = the whole page fits in. */
  pdfFit: pref('pdfFit', 'width'),
  /** '1': a word with a translation gets the highlight background; '0': none, only the translation is coloured. */
  wordBg: pref('wordBg', '1'),
  /** "Слова по урокам": words met this many times or less in the whole course are not counted (0 = all). */
  wsMin: pref('wsMin', '0'),
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
  promptLevel: pref('promptLevel', Prompts.LEVEL),
  /** Import: quick clean right after pasting / opening a file. */
  /** Long texts are cut into lessons of this many words (at most LingQ's 6000). */
  splitWords: pref('splitWords', '6000'),
  readerFont: pref('readerFont', '20'),
  /** '1': the word window is docked at the right of the reader and always shown. */
  dockPanel: pref('dockPanel', '0'),
  /** Sentence mode: '1' = the sentence translation is shown under the sentence. */
  smTranslation: pref('smTranslation', '1'),
  /** Speech recognition (audio -> lesson): service, keys per service (JSON, synced), models per service (JSON). */
  sttProvider: pref('sttProvider', 'groq'),
  sttKeys: pref('sttKeys', '{}'),
  sttModels: pref('sttModels', '{}'),
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
  level: () => prefs.promptLevel.value.trim() || Prompts.LEVEL,
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
