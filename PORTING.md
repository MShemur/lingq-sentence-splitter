# Porting queue between the Android app and the web version

When a session adds or changes a reader feature on one platform, it adds a line here under the other
platform ("to do on web" / "to do on Android"): what changed, the commit, the file. The session working on
the other platform checks this file at the start, ports the change, and moves the line to "Done" with its commit.
Platform-specific things (APK updates, iPad gestures) are not listed.

## To do on web

- Two Gemini models in Settings → Перевод: "Модель для чата и повторного перевода" (geminiModel) and "Модель для
  слов и предложений" (geminiFastModel, blank = same as chat; hint: take flash-lite, bigger free limit). Words,
  phrases, sentence translations and bookmark translations use the light one; the limit window switches the model
  of the request that failed. Android `AppState.geminiFast`, `Settings.kt`, `ModelSwitch.apply`.

- Statistics (library top bar, chart icon): reading time counts in 15 s steps while a lesson is open and the app
  is in front, unless nothing was touched for 3 min; words = words of sentences seen for the first time in a lesson
  (highest sentence index counted per lesson). Screen: "Сегодня" card (time, words, streak = days in a row with
  >= 1 min), cards Неделя (from Monday) / Месяц / Год, bar chart "Минуты чтения за 2 недели" (tap a day for its
  numbers). Android `Stats.kt` (`ReadingStats`, `ReadingTimer`, `StatsScreen`). Will be synced later.

- Settings → "Чтение" (also from the reader menu "Оформление и экран"): page colours of the reader
  (Как в системе, Белая FFFFFF/1C1B1F, Кремовая FBF7EE/2D2A26, Сепия F4ECD8/5B4636, Мятная CCE8CF/233626,
  Серая E6E6E6/222222, Сумерки 3A3A3C/E3E3E3, Тёмная 1E1E1E/DADADA, Чёрная 000000/C8C8C8; surfaces of bars and
  sheets mixed from them), colour of chosen words + translations above them (Зелёный, Жёлтый как в LingQ, Голубой,
  Розовый, Сиреневый, Оранжевый; light/dark variants) with a preview line, and "Экран не гаснет": system /
  5 / 10 / 15 / 30 min after the last touch / while the lesson is open (web: Screen Wake Lock API).
  Android `ReaderLook.kt`.

(the web version starts from `web/PLAN.md`, which already covers Android v1.23)

(nothing)

## To do on Android

(nothing)

## Done

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
