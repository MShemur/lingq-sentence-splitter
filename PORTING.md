# Porting queue between the Android app and the web version

When a session adds or changes a reader feature on one platform, it adds a line here under the other
platform ("to do on web" / "to do on Android"): what changed, the commit, the file. The session working on
the other platform checks this file at the start, ports the change, and moves the line to "Done" with its commit.
Platform-specific things (APK updates, iPad gestures) are not listed.

## To do on web

(the web version starts from `web/PLAN.md`, which already covers Android v1.23)

- android v1.69–v1.70: podcast search has NO suggestion chips (the user listens to native shows: Lanz und Precht, Wissen
  Weekly, Surplus). Speech recognition prices: each model has a price in US$ per hour (checked 10.11.2026: Groq
  whisper-large-v3-turbo 0.04, whisper-large-v3 0.111; OpenAI whisper-1 0.36; Deepgram nova-3 0.258; AssemblyAI
  universal-2 0.15, universal-3-5-pro 0.21 (best / nano are deprecated); ElevenLabs scribe_v2 0.22 (new default),
  scribe_v1 0.40). Settings: each provider row "от 0,04 $ за час · note"; under the model "<model>: 0,04 $ за час,
  10 минут ≈ 0,7 цента. Цены проверены 10.11.2026." and a button "Цены <name> на сайте" (groq console docs
  speech-to-text, platform.openai.com/docs/pricing, deepgram.com/pricing, assemblyai.com/pricing,
  elevenlabs.io/pricing/api). Part sheet: a primary line with the cost of the chosen part ("Groq: ≈ 0,4 цента"; with
  the podcast transcript "бесплатно, готовая расшифровка"), "Весь файл (1:02:10, ≈ 2 цента)". Format: < 0,1 цента /
  "≈ 0,7 цента" / "≈ 12 центов" / "≈ 1,20 $". Android `Transcribe.kt` (`SttProvider.prices / priceUrl`, `sttCost`,
  `sttHourPrice`, `STT_PRICES_CHECKED`).

## To do on Android


## Done

- android v1.68 -> web v1.30: podcasts + covers. Import: tiles "Подкаст" and "Аудиофайл → текст". Podcasts screen: search field
  ("Название подкаста или ссылка на RSS"; an http link opens the feed) via Apple Podcasts search
  (`itunes.apple.com/search?media=podcast&entity=podcast&country=DE&term=`, country from the language), suggestion chips
  for German (Langsam gesprochene Nachrichten, Top-Thema mit Vokabeln, Easy German, Slow German, Deutschlandfunk, Coffee
  Break German); "Мои подкасты" = subscriptions (local, not synced) with "N нов." badges (episodes newer than last
  opening). Feed screen: cover, title, author, Подписаться / Вы подписаны ✓, episodes (new dot, date · duration · "есть
  расшифровка", 2 lines of description). Tap = download (progress card with Отмена), then the audio part sheet; when the
  feed has `podcast:transcript` (VTT / SRT / JSON) a switch "Готовая расшифровка подкаста" (on by default) takes its text
  for the chosen part instead of recognition (word times spread over each cue). Covers: EPUB cover (cover-image /
  meta cover / image named cover) or the podcast artwork → checkbox with a thumbnail next to the course choice: new course
  = on ("Обложка для нового курса"), existing = off ("Поставить эту обложку курсу «…»"). LingQ: POST collections
  multipart with `image` (fallback without), PATCH `/v3/{lang}/collections/{id}/` multipart `image` (fields seen via
  OPTIONS). Android `Podcasts.kt`, `Epub.cover`, `LingqApi.createCourse(cover) / setCourseImage`. Web: feeds and
  episode MP3s need the proxy (no CORS on most podcast hosts).
  Web v1.30: `importer/podcasts.ts` (search, feed via DOMParser, download with progress / cancel, transcripts,
  subscriptions in localStorage, PodcastSession), `screens/Podcasts.tsx`, `stt.fromSegments`, EPUB cover in
  `sources.ts`, `lingq.createCourse(cover) / setCourseImage`. Feeds, episodes, transcripts and covers come through the
  Pages Function `functions/fetch.ts` (/fetch?url=…: GET only, public http(s), HTML refused).

- android v1.67 -> web v1.29: audio → lesson (speech recognition). Import tile "Аудио → текст (подкаст, запись)" → sheet "Какую часть
  распознать?": start field (мин:сек, also 1:05:00), length chips 5/10/15/20/30 мин / До конца (default 10), "Распознать",
  "Весь файл". The part is cut without re-encoding (MP3 frames, AAC/Vorbis/Opus remuxed; else 16 kHz WAV); title gets
  ", 12:30–22:30". Providers (Settings → "Распознавание речи": radio list, key per provider, model chips + field):
  Groq / OpenAI (`/audio/transcriptions`, verbose_json, timestamp_granularities word+segment), Deepgram (`/v1/listen`
  nova-3, paragraphs), AssemblyAI (upload → transcript → poll → /paragraphs), ElevenLabs (`/v1/speech-to-text` scribe_v1).
  Groq key falls back to the translation AI key when aiBase is Groq. Files > 24 MB for Whisper services: decoded to
  16 kHz mono, 8-min WAV pieces cut at the quietest 0.1 s of the 30 s before each border, times offset. Whisper junk
  ("Untertitel der Amara.org…", 3× repeated lines) dropped; paragraphs = provider's, else at sentence end after a 1.2 s
  pause or ~90 words. Text goes into the paste editor (auto clean as usual) with a line "<file> — загрузится в урок" +
  "Без аудио". Upload: multipart `POST /v3/{lang}/lessons/import/` with an `audio` file part (not split into parts),
  after processing each sentence gets `timestamp` [start, end] from the words (in-order match, 8-word look-ahead, gaps
  filled from neighbours) and paragraphs are PATCHed. Sync: keys object gets `"stt": {provider: key}` (only when set) —
  **web must keep / merge this field**, otherwise the two devices re-send keys on every sync. Android `Transcribe.kt`
  (`Stt`, `AudioCut`, `AudioPrep`, `SettingsSpeech`, `AudioPartPicker`), `ImportSession.pickAudio / transcribePart /
  setTimes`, `LingqApi.importLessonAudio / audioState`. Web: decodeAudioData + WAV for cutting; providers may need the
  Pages Function proxy for CORS.
  Web v1.29: `importer/audio.ts` (MP3 cut by frames without re-encoding, other formats decoded to 16 kHz WAV via
  decodeAudioData; MP3 pieces > limit cut at the quietest 0.1 s found by decoding only the 30 s before a border),
  `importer/stt.ts` (providers, align), `screens/AudioPicker.tsx`, Settings → «Распознавание речи». All services go
  through the Pages Function `functions/stt/[[path]].ts` (/stt/<service>/…, CORS; max ~95 MB per request).
  Sync keeps / merges `stt` like Android. A part of a non-MP3 file is uploaded to LingQ as WAV (no remux on the web).

- android v1.66 -> web v1.28: PDF import, choose pages. A PDF with more than one page opens a sheet "Какие страницы взять?":
  page previews in a grid (tap = pick / unpick, ✓ badge, thick primary border), a field "Страницы, например 3-5, 8"
  (1-based, commas / dashes; error text if it cannot be read), buttons Все / Сбросить, "Взять N стр.". Only the chosen
  pages are read; the text layer is checked per page (fewer than 80 letters = a scan) and only those pages go to OCR
  (Android ML Kit; web has no OCR: report "страница N — скан, без текста" or skip). Lesson title gets ", стр. 3–5, 8"
  unless all pages were taken. One-page PDFs are read at once. Android `PdfPick.kt` (`PdfDoc`, `PdfText.pages`,
  `parsePages`, `pagesLabel`, `PdfPagePicker`), `ImportSession.importPdfPages`. Web: pdf.js renders the previews.
  Web v1.28: `importer/pdf.ts` (openPdf: previews via pdf.js one at a time, text per page, parsePages, pagesLabel),
  `screens/PdfPicker.tsx` (previews rendered when scrolled into view); scanned pages are skipped with a snackbar
  "Страница N — скан, без текста: пропущено" (no OCR on the web); all chosen pages scans = error.

- android v1.63 -> web v1.27 (code review, speed / simplify; the same issues were found on the web):
  - Audio ticker: 50 ms only while a loop or a one-sentence stop is on, else 250 ms; the position shown on screen
    changes ~4 times a second (`audio.ts:229` setInterval 50 ms re-renders the bar and mini player 20x/s; a
    `computed` signal for the time text is enough).
  - Course word counts: at most once a day per course (time kept with the counts), not on every start
    (`app.ts:197`, `Library.tsx:46`).
  - Sentence translations made by the AI / machine (and "translate again") kept on the device per lesson
    (sentence text -> translation, not LingQ's stored one, no errors; NOT in the synced notes); applied when the
    lesson opens. Android `TranslationCache` in `Reader.kt`. Web: IndexedDB or localStorage per lesson.
  - Web only (from the same review): FullText in the player re-renders the whole lesson on every new sentence -
    toggle `.sounding` by `[data-se]` in an effect + `content-visibility: auto` on `.para`; applog saved to
    localStorage ~1 s after every line - save every 10 s and on pagehide, fewer lines or IndexedDB; dead code:
    `audio.ts` replaySentence / nextSentence / setStarts / starts (+ the setStarts call in session.ts:190),
    `Reader.tsx` EditButton, `app.ts` replaceTop, doubled comments (Reader.tsx:702, session.ts:628); `norm()`
    defined twice in session.ts (791, 946).
- android v1.64 -> web v1.27 (review item, optional for the web, lower priority there): reader words no longer recompose on a
  selection drag / word window: the highlight is drawn (`drawBehind` reading the state), word positions via
  `onPlaced`; pages are laid out in the background in one pass per page start (`TextMetrics.bottoms`, heights of
  every prefix of a paragraph) instead of re-measuring each prefix. Web: `Reader.tsx` SentenceFlow re-renders all
  words of 3 pages on any pins / provisional / popup / sel change - same idea (classes toggled in an effect).


- android v1.61 -> web v1.27: statistics split reading / audio. Day data is now `[seconds, words, listenSeconds]` (seconds = total,
  listening included; old 2-number entries = no listening). Timer: audio playing -> counts as listening, else reading
  in the reader (as before); total unchanged everywhere (today card in the library stays one number). Sync `stats`
  per device carries the third number; read it with a default 0 and keep it when merging. Stats screen only: today card
  shows "Чтение X · Аудио Y" under the total; week / month / year cards add "чтение … / аудио …"; 2-week chart = stacked
  bars (reading bottom, primary; audio top, tertiary; 2 dp gap) with a legend, the picked day's line shows the split.
  `Stats.kt` (`addTime(sec, listening)`, `day`, `since`, `DaysChart`).
  Web v1.27: ticker 50/250 ms + position shown ~4/s (`audio.ts`); course words recounted at most daily
  (`courseWordsT`); AI / machine sentence translations in IndexedDB `satzloop-tr` per lesson + target (`trcache.ts`);
  FullText bolds the sounding sentence by DOM classes, `content-visibility` on its paragraphs; journal written
  every 10 s and when hidden; dead code removed. Reader: each sentence is a memoized `SentView` getting its pins /
  window / selection / phrases as data (re-renders only the sentence touched). Stats: day = [sec, words, listen],
  `tickTime(listening)`, reading timer stays out while audio plays; stats screen split + stacked chart.

- web v1.26 -> android v1.62 (`AudioEngine.loopWhole/loopLabel`, ENDED while looping starts over; `ReaderSession.selectStart/selectTo` autoShown, `trCoords` + `WordMenuLayer` puts the menu under the translation row; chat icon in `SentenceTranslation`): player repeat sheet has "Повторять весь урок" (loop 0..end, keeps playing from where it is; starts over
  at the end; the pill / status say "весь урок"). Sentence translation row: chat button next to ↻ again.
  Reader long press: the sentence translation shows as soon as the long press fires (finger still down); the menu
  at the word still opens on lift, placed under the translation row; if the press turns into a phrase drag the
  auto-shown translation is hidden again. (Web also had to follow the finger on window: the re-rendered word lost
  the lift.)

- web v1.25 -> android v1.60 (same bug was there: tap with the docked panel asked the AI twice; mini player opened an empty player after the screen was recreated). Android: `ReaderSession.ai/hints` share in-flight requests (CompletableDeferred), `Extras.kt TranslationText`, `Audio.kt LoopSheet/LoopTile` + loop pill, `Player.kt TextWindow`, `AppState.openSounding`. Also "Повторять весь урок" tile as on the web. Headphone prev/next = ±5 s not ported (not asked).
  - One AI request per word place: a tap pins the word and the docked word window opens at once; two separate
    requests answered differently, so the pin showed one meaning and the window "the right one is …". Share the
    in-flight request (the same for LingQ hints).
  - Sentence translation while it loads: shimmering placeholder lines (1–2, by sentence length) instead of an empty
    row / "…"; the text fades in; "translate again" shimmers the text.
  - Player repeat sheet redone: a running loop shows a status card + a big filled "Остановить повтор" on top;
    "Повторять это предложение" is a big tile; segment = segmented [Только что прозвучало | Отсюда вперёд] + one row
    of 30 с / 1 / 2 / 5 мин; own A–B folded; pause = segmented. "Остановить аудио" removed from it. In the bar a
    running loop is a pill "Повтор 0:13 – 0:16 · 3-й раз" with × to stop at once.
  - Player text window: no hint line under the text, bigger text (current ×1.55, neighbours ×1.15 of reader font).
  - Mini player tap opens the player on the lesson that is sounding.

- android 90660e4 -> web v1.24: UX tidy-up: reader long press (no drag) = small menu right under the word (no direct sentence translation any
  more): Перевод предложения / В закладки / Исправить предложение / Слушать отсюда (only with audio; opens the player
  there) / Чат о предложении; same in sentence mode. Sentence-translation row keeps only ↻. Reader ⋮ grouped with small
  headings: Урок (Слушать в плеере, Определить уровень) / Вид (A+, A−, Оформление, Снять переводы) / Помощь (Как
  пользоваться); removed Чат о тексте (bottom bar has it), Статистика (today card), Журнал (Settings). Library top bar:
  no stats icon. Player: lesson title with ⌄ opens the lesson list (removed from ⋮). Bookmarks: chips Все / Текст / Аудио.
  Prompts settings: "Уровень текста" prompt. `Reader.kt WordMenuLayer/ReaderMenuItems`, `Player.kt`, `Bookmarks.kt`.
  Web v1.24: `WordMenuAt` (menu placed under the word's rect) + `ReaderMenuItems` in the reader and sentence mode,
  player items reuse it; `listenAt` in player.ts (audio bookmarks use it too); `prompt.level()`; Журнал stays in
  Settings only.

- android -> web v1.23: (replaces the player-in-reader line below): the player is NOT in the reader any more. Main screen has bottom tabs
  "Библиотека" / "Плеер" (like LingQ). Player tab: lesson header + "Другой урок" (recent lessons that have `audioUrl`);
  collapsed: a card with previous / current (bold) / next sentence, tap -> whole text (follows the audio, pauses following
  6 s after the user scrolls), sounding sentence bold (whole sentence, not words); tap a word = pin translation, long press =
  sentence translation in a strip above the player; word/phrase sheets as in the reader. Controls + loop sheet as below.
  Reader: menu ⋮ "Слушать в плеере" (only if the lesson has audio). Notification tap opens the player tab. Android `Player.kt`.
- android -> web v1.23:: lesson audio player. LingQ editor JSON has `audio` (MP3 URL or null) and every sentence `timestamp: [startSec, endSec]`.
  Bottom bar "Аудио" (only when the lesson has audio) shows a player bar under the text and in sentence mode: time line,
  speed menu (0.5–1.5, remembered), ⏮/⏭ previous/next sentence (⏮ restarts the sentence if >1.5 s into it), ±5 s, play/pause,
  🔁 loop sheet: this sentence (start−0.1 s … end+0.15 s), last 1/2/3/5 min, next 1/2/3/5 min from the current sentence start,
  own A–B snapped to the current sentence start/end, pause between repeats (0/1/2/3/5 s), off, stop audio. Sounding sentence
  coloured; text page / sentence-mode page follow the audio while playing; ▶ in the sentence-translation row = play from it.
  Position per lesson remembered; listening counts as reading time in stats. Background playback via Media3 session service.
  Android `Audio.kt` (`AudioEngine`, `AudioBar`, `LoopSheet`), `Reader.kt` (audio part of `ReaderSession`).
  Web: <audio> element + Media Session API (lock screen on iPad); loop by checking currentTime in timeupdate/rAF.

- android -> web v1.23:: player text gestures changed: tap a word = seek to the start of its sentence (playing state kept); long press
  (without dragging) = bottom-sheet menu: Перевод слова (word window) / Перевод предложения (strip at the bottom) /
  Повторять это предложение / Начало повтора (A = sentence start) / Конец повтора (B = sentence end); a snackbar says
  what is still missing. Drag over words = phrase translation as before. `ReaderSession.tapWord/selectEnd/wordMenu`, `Player.kt WordMenu`.

- android -> web v1.23:: player word menu is a small dropdown right under the long-pressed word with 4 items: Перевод слова / Перевод
  предложения / Начало повтора / Конец повтора. Audio bookmarks: bookmark JSON gets optional `ms` (audio position; sync key
  "<lessonId>_a<ms>", text bookmarks keep "<lessonId>_<sent>"); player header: bookmark icon = mark the sounding sentence
  start (one per sentence, a second mark moves it), ⋮ → "Аудиозакладки (N)" sheet (tap = play from there, delete) and
  "Другой урок". Bookmarks screen shows them with a headphones icon and "1:23 · " before the text; long press →
  "Слушать с этого места" opens the player there. `Bookmarks.kt` (`addAudio`, `audioFor`), `Player.kt`, `AppState.openAudioBookmark`.

- android -> web v1.23:: player lesson list = big elevated cards (96 dp cover from `imageUrl`, title, course, length from `duration`,
  "сейчас в плеере" badge); chips row "Недавние" + my courses (course lessons from collections/{id}/lessons with
  `duration > 0`; that list has no audioUrl -> fetched from lessons/{id}/ `audioUrl`); header "N уроков · 1 ч 20 мин" +
  "Слушать все": a queue of all lessons in the list, played one after another (Media3 playlist; on transition the player
  shows the next lesson's text; finished lesson's saved position reset to 0); player bar shows "‹ Урок  2 из 5  Урок ›".
  `Player.kt AudioLessons/AudioLessonCard`, `AppState.playAll`, `AudioEngine.loadQueue`.

- android -> web v1.23: "modern look" batch: app is now called SatzLoop (new adaptive icon: violet→teal, text lines in a loop arrow);
  screen transitions (deeper = slide from right, back = from left, tabs = cross-fade); library top card: greeting by time
  of day, ring "minutes today / 20 min goal", words today, 🔥 streak (tap = stats); listening anywhere counts as time;
  skeleton placeholders (breathing grey shapes) instead of spinners for courses, "Продолжить" and the player list;
  mini player above the bottom tabs / on other screens (cover, title, time, −5 s, play/pause; tap = player tab);
  player: speed = slider sheet 0.5–2.0 step 0.05 + presets; double tap left/right half of the sentence window = ∓5 s
  with a "« 5 с" bubble; window ↔ full text cross-fade. Home screen widget of the player (Android only).
  `Extras.kt` (`skeleton`, `TodayCard`, `MiniPlayer`, `SpeedSheet`), `Ui.kt`, `Player.kt`.

- android -> web v1.23:: AI sets the lesson level. `PATCH /api/v3/{lang}/lessons/{id}/ {"level": N}` (verified: N = 1..6 =
  Beginner 1, Beginner 2, Intermediate 1, Intermediate 2, Advanced 1, Advanced 2 ~ A1..C2). Prompt `Prompts.LEVEL`
  (first 6000 chars, JSON {"cefr","why"}), light model. Import: checkbox "ИИ определит уровень сложности (A1–C2)",
  on by default, remembered; runs after LingQ processed the lesson (waitProcessed), log line "… уровень B1".
  Reader ⋮ "Определить уровень (ИИ)": whole lesson text -> sets the level -> dialog "Уровень: B1 · Intermediate 1" + reason.
  `Llm.level`, `LingqApi.setLevel`, `ImportSession.aiLevel`, `ReaderSession.detectLevel`.
  Web v1.23: all of the above in one go. `audio.ts` (one <audio>, Media Session: lock screen ▶/⏸, ±5 s, ⏮ = sentence
  start, ⏭ = next lesson in the queue / next sentence; loop by a 50 ms ticker; positions in localStorage `audio.pos`;
  unlock() inside the tap so iOS lets it start after the lesson loaded), `player.ts` (player session, playLesson,
  playAll, openAudioBookmark, switchTab), `screens/Player.tsx` (tab, window, full text, bar, speed / loop / marks
  sheets, lesson list, MainTabs, MiniPlayer). Today card, skeletons, layer transitions push/pop/fade, SatzLoop name +
  icon (PNG rendered from icon.svg). Level: `gemini.level`, `api.setLevel`, reader ⋮, import checkbox.
  No home-screen widget on the web.

- android 33c623b -> web v1.22: action journal for bug reports: screens, taps on words/phrases/sentences, LingQ requests (method,
  path without query, status, ms), AI requests (model, ms, ok/error, fallback), sync, snackbars, crashes; kept 48 h /
  3000 lines; Settings and reader ⋮ → "Журнал действий" with Копировать / Поделиться / Очистить, newest first; header
  with version and device. No keys/tokens. Android `AppLog.kt`. Web: localStorage ring buffer is enough.
  Web v1.22: `applog.ts` (localStorage `applog`, 48 h / 3000 lines, saved 1 s after a line and on pagehide);
  hooks in `app.ts` (go/back/say/geminiFailed), `lingq.ts` `call` (method, path, status, ms), `gemini.ts`
  `generateFull` (Gemini model or the other AI's host + model, ms, chars / error, fallback), `sync.ts`, reader
  session (tap, word window, phrase, sentence translation, sentence mode, load error, edit); window error /
  unhandledrejection as [crash]. Settings and reader ⋮ → "Журнал действий" (Копировать, Поделиться via
  navigator.share, Очистить).

- android 816b32a -> web v1.21: edit a sentence: pencil in the sentence-translation row -> window with the sentence and its translation;
  "Сохранить" re-reads the lesson editor JSON, keeps a backup, changes only that sentence (`text`, `cleanText`,
  own-language entry in `translations`) and PATCHes the paragraphs; the sentence is replaced on screen, its word
  and phrase pins are dropped if the text changed. `Reader.kt` (`saveEdit`, `SentenceEditDialog`).
  Reader menu ⋮ also has "Статистика".
- android 1c87273 -> web v1.21: keys sync (encrypted): sync data gets `secrets: {v:1, iter, salt, iv, ct, t}` = JSON
  {"lingq","gemini","aiBase","aiKey","aiModel","aiFastModel"} encrypted with a sync password set on each device
  (Settings → Синхронизация → "Пароль синхронизации ключей"; stored only on the device). PBKDF2-HMAC-SHA256,
  `iter` rounds (210000), 16-byte salt -> AES-256-GCM, 12-byte iv, tag appended to ct (WebCrypto does the same),
  standard base64. Newer `t` wins; `t` = time the keys last changed on that device (detect by a hash of the
  plain JSON). First sync after setting the password: take the remote keys first (blank values never erase
  local ones), then send the union if this device had more. Never overwrite remote secrets that the password
  cannot decrypt (show "Пароль не подходит"). GitHub token is not synced. Android `Sync.kt` (`syncKeys`, `KeyVault`).
  Web v1.21: `reader/session.ts` (`saveEdit`, `findSentence`), `Reader.tsx` (`SentenceEditDialog`, pencil in both
  translation rows), menu "Статистика"; `sync.ts` (`syncKeys`, WebCrypto vault), Settings → Синхронизация → Ключи.
  Checked: web opens keys sealed the Android way (Node PBKDF2 + AES-GCM) and Node opens web's; wrong password
  keeps the remote secrets. Also fixed: web sync before v1.21 dropped `secrets` from the file on write.

- web v1.20 -> android: phrase translation no longer breaks in two at a dash inside the selection
  (`phraseOf` counts a non-word chunk strictly inside the range; the dash is highlighted too).

- android cabc9fd -> web v1.20 (fixes): (1) a dash between spaces ("Dürer – Maler") vanished: a non-word token " – " was split
  into "before space"/"after space" and the middle was dropped; now each part between spaces is its own chunk
  (`Sent.chunks`). Check the web tokenizer for the same bug. (2) "Продолжить" row: newest first - LingQ's
  `openDate` + lessons opened in the app (kept locally) + reading-progress time, sorted by time desc, top 10
  (`AppState.mergeRecent`). (3) Word prompt gets a note: a verb whose separable prefix stands later in the
  sentence is translated with the prefix ("fing … an" -> anfangen); also for the prefix itself (`SEPARABLE`).
  (4) Several words selected: their translation is pinned above all of them as one (highlighted together),
  saved in lesson notes as "phrases": {"sent:from:to": text}; tapping any of its words removes it; overlapping
  phrases give way; "Очистить переводы" clears them too. `Reader.kt` (`phrasePins`, `ChunkRow`).
  Web v1.20: same bug was there, fixed (`session.ts` chunks); "Продолжить" = LingQ openDate + localStorage `recent`
  + position time (`app.ts` `recent` computed, `lessonOpened`); SEPARABLE note on word prompt and phrase prompt;
  phrase pins in notes `phrases` (synced with the notes), `Reader.tsx` `SentenceFlow` (`.phrase`). A dash chunk
  inside a phrase belongs to it (Android `phraseOf` looks only at words, so a phrase over "Nürnberg – Wiege"
  splits in two there: worth the same fix).

- web v1.19: Reader menu ⋮ → "Как пользоваться": dialog with gestures and buttons (Слова / Предложения / Страницы и режимы /
  Меню и кнопки). Android 699067e `ReaderHelp` (not listed here, found in the log). Web adds the iPad extras: docked panel
  pin, ‹ › buttons and arrow keys. `reader/Help.tsx`.

- web v1.18: Import redesign around text cleaning (the main use: pasted text full of junk). Source tiles Вставить / Файл
  (TXT, MD, EPUB, PDF) / Галерея / Камера; the text in an editable field; a "Очистка текста" card: "Быстро" =
  rules on the device (page numbers, repeated header/footer lines, footnote marks, [12], links, share/print/ad
  lines, soft hyphens; joins "Kup-
ferstich" and lines broken inside a sentence), "С ИИ" = quick clean first,
  then the light model with prompt CLEAN (editable) in pieces of ~1800 words, progress; "Вернуть как было";
  switch "Быстро чистить сразу при вставке" (on by default). Android `Cleaner.kt` (`TextCleaner`), `Gemini.kt`
  (`Prompts.CLEAN`), `ImportSession` (paste / cleanQuick / cleanAi / undoClean), `Ui.kt` ImportScreen.
  OCR of photos / scanned PDFs on Android is ML Kit (on the web: skip, or Gemini vision later).
  Web (no photos / OCR, as agreed): library button "Загрузить" -> tiles Вставить (clipboard; Safari may refuse,
  then an empty field) / Файл (TXT, MD, EPUB via fflate, PDF text layer via pdf.js; a scanned PDF is reported).
  Cleaning, course choice / new course, auto fix (`importer/fixer.ts` = Fixer.kt, checked against
  lingq_fix_splits.py on 21 sentence pairs and 21 real lessons), split long texts, report; backups in IndexedDB
  (3 days), Settings → Резервные копии with restore. `importer/*`, `screens/Import.tsx`.

- web v1.17: **Sync phone <-> iPad** (Settings → Синхронизация; switch, "Синхронизировать сейчас", last time / error).
  File `sync/data.json` on branch `sync` of MShemur/lingq-sentence-splitter via GitHub contents API with the
  same fine-grained token (Contents: Read and write). GET contents?ref=sync (404 -> create branch `sync` from
  main via git/refs, file missing); merge local + remote; PUT with sha, branch "sync", message
  "sync from <device> [skip ci]" ([skip ci] keeps Actions and Cloudflare from building); 409/422 -> read+merge again.
  Runs on start and when the app is left (at most once a minute) and by the button. JSON v1:
  `bookmarks {"<lessonId>_<sent>": {lang, lessonId, lessonTitle, sent, text, tr, time}}`,
  `deleted {"<lessonId>_<sent>": time}` (later of item.time / deletion wins; keep a non-empty tr from either side),
  `notes {"<lang>_<lessonId>": {t, pins {"sent:token": meaning}, words {word: meaning}}}` (whole lesson, later t),
  `progress {"<lang>_<lessonId>": {pos, t, read}}` (pos by later t, read = max),
  `stats {"<device>": {"YYYY-MM-DD": [seconds, words]}}` (each device writes only its own key, e.g. "android-ab12cd",
  "web-xxxxxx"; totals = sum over devices), `seen {"<lang>_<lessonId>": index}` (max). Android `Sync.kt`.
  Web must store the same pieces with the same keys and timestamps (ms) to merge.
  Web: `sync.ts`, Settings → Синхронизация (switch, button, status, own token field: the web had no GitHub token).
  Straight from the browser (api.github.com allows CORS). Web notes / positions now carry `t`; bookmark deletions
  kept in localStorage `bookmarksDeleted`; other devices' days in `statsOthers` (statistics show the sum).
  Checked against a mocked GitHub with phone-format data (not yet against the real repo: no token here).

- web v1.16: Third translator "Другой ИИ": any OpenAI-compatible service (presets DeepSeek https://api.deepseek.com,
  Qwen https://dashscope-intl.aliyuncs.com/compatible-mode/v1, OpenRouter https://openrouter.ai/api/v1,
  Groq https://api.groq.com/openai/v1, Mistral https://api.mistral.ai/v1), address + key + main / light model
  (models from GET {base}/models). POST {base}/chat/completions, Bearer key, temperature 0.3, JSON mode
  response_format json_object for words (retry without it on HTTP 400), retries 2/4/8 s. If the answer has
  `reasoning_content` (DeepSeek reasoner), the chat shows it folded: "Как модель рассуждала ▾".
  Android `Llm.kt` (`Llm`, `OpenAiCompat`, `AI_PRESETS`), `Settings.kt` SettingsTranslation, `Chat.kt` Reasoning.
  Web: `openai.ts`; called straight from the browser (all five presets allow CORS, checked), no proxy.

- web v1.15: Two Gemini models in Settings → Перевод: "Модель для чата и повторного перевода" (geminiModel) and "Модель для
  слов и предложений" (geminiFastModel, blank = same as chat; hint: take flash-lite, bigger free limit). Words,
  phrases, sentence translations and bookmark translations use the light one; the limit window switches the model
  of the request that failed. Android `AppState.geminiFast`, `Settings.kt`, `ModelSwitch.apply`.

- web v1.15: Statistics (library top bar, chart icon): reading time counts in 15 s steps while a lesson is open and the app
  is in front, unless nothing was touched for 3 min; words = words of sentences seen for the first time in a lesson
  (highest sentence index counted per lesson). Screen: "Сегодня" card (time, words, streak = days in a row with
  >= 1 min), cards Неделя (from Monday) / Месяц / Год, bar chart "Минуты чтения за 2 недели" (tap a day for its
  numbers). Android `Stats.kt` (`ReadingStats`, `ReadingTimer`, `StatsScreen`). Will be synced later.
  Web: localStorage `stats`; the timer runs only while the reader is the top screen and the page is visible.

- web v1.15: Settings → "Чтение" (also from the reader menu "Оформление и экран"): page colours of the reader
  (Как в системе, Белая FFFFFF/1C1B1F, Кремовая FBF7EE/2D2A26, Сепия F4ECD8/5B4636, Мятная CCE8CF/233626,
  Серая E6E6E6/222222, Сумерки 3A3A3C/E3E3E3, Тёмная 1E1E1E/DADADA, Чёрная 000000/C8C8C8; surfaces of bars and
  sheets mixed from them), colour of chosen words + translations above them (Зелёный, Жёлтый как в LingQ, Голубой,
  Розовый, Сиреневый, Оранжевый; light/dark variants) with a preview line, and "Экран не гаснет": system /
  5 / 10 / 15 / 30 min after the last touch / while the lesson is open (web: Screen Wake Lock API).
  Android `ReaderLook.kt`. Web: CSS variables on the reader screen (`readerLook.ts`), Wake Lock API (Safari 16.4+).

- web v1.14: Library as covers (Material 3, like LingQ): courses in a grid of square covers (`imageUrl` from
  `collections/my/`) with the title under them; on top a horizontal row "Продолжить" with the 10 lessons opened
  last: `GET /api/v3/{lang}/search/?shelf=my_lessons&sortBy=recentlyOpened&page=1&page_size=10` (lessons with
  `imageUrl`, `collectionTitle`, `level`, `wordCount`, `newWordsCount`). Lessons of a course also as a grid of
  4:3 covers: level badge top-left, thin reading-progress bar at the bottom of the cover, title, "N слов" (no new-word count); under each course cover "12 345 слов" = sum of `wordCount` of its lessons (fetched in the background, 3 at a time, cached; refreshed once per start and whenever the course is opened);
  course header with its cover and "N уроков". Without a picture: colour gradient with the first letter.
  Android `Library.kt`. Web: LingQ's stock picture (`/default-content`) counts as none, so those get the
  gradient tile.

- android 0fa91c9: Page-turn buttons: no circle/background/border/shadow any more, just a faint arrow (~45% opacity; a light
  tint only while pressed). The user found them too conspicuous. web v1.13 styles `.turn-btn`.

- android 0fa91c9: Paged text: pages are laid out from the bare text only. Pinned word translations, shown sentence translations
  and bookmark marks no longer re-split the pages or move text to other pages; a page that grows scrolls up and
  down by itself (only that page). The spare room left at the top/bottom of a page for that is removed (web: 6px). No visible scrollbar on the pages (web v1.12).
  web v1.11 `Reader.tsx` (`paginate`, `Paragraph plain`).
- android 0fa91c9: Remember where reading stopped in every lesson: the first sentence on the page (sentence mode: its sentence) is
  saved on every page turn; the lesson opens there next time (a bookmark's "go to source" still wins). The lesson
  list shows "Прочитано N %" with a small progress bar: furthest point read = (last sentence on screen + 1) /
  sentences, never goes down. web v1.11 `progress.ts` (localStorage `pos.{lang}_{lesson}`), `Library.tsx`.

- android 0fa91c9: Long meanings in the word / phrase window: chips must grow in height with wrapped text (web chips had a fixed
  height, lines overlapped other chips). Check the Android FilterChips with a long LingQ meaning too. web v1.10.

- android ec96295: Narrow screen (phone, width <= 600 dp): no page-turn buttons and no side padding for them; the word panel is
  never docked there (pin button hidden), the pop-up sheet is used. With the panel docked, sentence mode shows the
  panel beside the sentence too (otherwise a double tap there opened nothing). web v1.9.

- android ec96295: Sentence mode: sentence and its translation centred on the page (horizontally and vertically). Under the
  sentence a text button "Скрыть перевод ⌃" / "Показать перевод ⌄" folds the translation away and back with an
  animation (height + fade, chevron turns); the choice holds for all sentences and is kept (`smTranslation`);
  a hidden translation is not requested. web `SentenceTranslationPage`, styles `.fold`, `.tr-toggle`.

- android ec96295: "Спросить" in the word window and in the phrase window opens a chat about that word / those words (not the
  sentence): new prompt "Чат о слове" ({word} {sentence}, editable in prompts, reset restores it), title
  "Чат о слове «…»", card shows word + sentence, suggestions "Что значит здесь?", "Другие значения",
  "Начальная форма и грамматика", "Примеры употребления", "Синонимы". web `prompts.ts` CHAT_WORD, `session.ts` chatWord.

- android ec96295: Docked word panel: the word window can be pinned at the right of the reader (pin button in the word / phrase
  window; ✕ in the panel header unpins). It stays there: empty with a hint until a word is chosen; a tap on a
  word (not only a double tap) shows the word there, the phrase selection too; choosing a meaning keeps the word
  in the panel. Setting kept (`dockPanel`). Text pages are re-measured for the narrower width.
  web `src/reader/Reader.tsx` (`DockedPanel`, `WordContent`, `PhraseContent`).
- android ec96295: Page-turn buttons: round translucent ‹ › buttons at the left/right middle of the text pages and of sentence
  mode (hidden on the first/last page); the text gets side padding so they do not cover it. web `TurnButtons`.

- Android: Gemini 503/429 retries 2/4/8 s + fallback flash model; chat "Повторить" (`Gemini.kt`, `Chat.kt`).

- web aa8d97f: Text view is paged, not one long scroll: pages fit the screen, swipe left/right turns them; a paragraph
  that does not fit is split at a sentence boundary; pages are rebuilt when pins/translations/font change
  and the page being read is kept; page counter "3 / 12" under the progress bar. Sentence mode collapses
  slowly (~0.5 s). Android commit 53b0f28, `Reader.kt` (`PagedText`, `TextPager`).
  Note: on Android, rendering the whole lesson off-screen to measure it ran out of memory on long lessons;
  heights are now computed from text sizes (`TextMetrics`, commit 87bc83d). On the web, render only the
  current page (+ neighbours) too.
  Web: only the current page and its neighbours are shown; measuring caches paragraph heights by what changes
  them and renders new ones in batches of 25, so a pin re-measures one paragraph, not the lesson (see next commit).
- web aa8d97f: Gemini chat answers are Markdown: render bold/italic/headings/lists/quotes/code (Android `Markdown.kt`,
  commit df2779e). On the web a small Markdown renderer (e.g. `marked` + sanitizing) is fine.
- web cd68734: Gemini limit/overload (HTTP 429/503, RESOURCE_EXHAUSTED, quota) after retries+fallback: a window
  "Gemini: лимит исчерпан" with "Попробовать другую модель" -> model list -> the choice becomes the current
  model and the failed request is repeated (word pin, word window, sentence translation, retranslate, chat).
  Android `ModelSwitch.kt`, `AppState.geminiFailed`.

- web cd68734: Several words at once: long press on a word, keep the finger down and drag over other words of the same
  sentence (selection highlighted); release -> window with the phrase translated together in context
  (Gemini word prompt + "this is a phrase" note; LingQ: hints + machine translation), chips to choose,
  own variant (saved per lesson like word meanings). Long press without dragging = sentence translation as
  before. Android `Reader.kt` (`wordGestures`, `openPhrase`, `PhraseSheet`).
