# Web version (PWA) for iPad — plan and handoff

The user reads mostly on an **iPad** (no paid Apple developer account, so no native iOS app).
Goal: the same reader as the Android app (`android/`), as a web app installed on the iPad home screen
("Share → Add to Home Screen"), full screen, auto-updating.

The Android app is the reference: read `android/app/src/main/java/com/mshemur/lingqfix/*.kt`
(Reader.kt, Bookmarks.kt, Gemini.kt, Chat.kt, LingqApi.kt, Settings.kt, Storage.kt) before porting a feature.
UI language: Russian. Look: Material 3 (same conventions as the Android app).

## Hosting

- **Cloudflare Pages** (free; GitHub Pages is paid for a private repo). Project root directory `web/`,
  build `npm run build`, output `dist`, Pages Functions in `web/functions/`.
- Every push to `main` touching `web/` deploys automatically. Address like `lingq-fix.pages.dev`.
- Optional later: Cloudflare Access (login by e-mail) so nobody else can open the site.
- The user must create the Cloudflare account and connect the repo themselves (guide them step by step).

## LingQ proxy (required)

LingQ's API sends **no CORS headers** (verified: OPTIONS with an Origin header → 401, no
`Access-Control-Allow-*`), so the browser cannot call it directly.
Pages Function `web/functions/api/[[path]].ts`: forwards `/api/...` to `https://www.lingq.com/api/...`
with method, query, body and the `Authorization: Token <key>` header from the request;
sets `User-Agent: lingq-lesson-fixer/1.0` (Cloudflare error 1010 blocks default UAs).
Stores nothing, logs no keys. Same origin as the site, so no CORS needed.

Gemini (`generativelanguage.googleapis.com`) is called straight from the browser (check CORS first;
if blocked, proxy it the same way).

## Keys and data

- LingQ key, Gemini key, model, prompts, language, translation language: `localStorage` on the iPad.
  Never sent anywhere except LingQ (through the proxy) / Google.
- Per-lesson pins and chosen meanings (Android: `filesDir/reader/{lang}_{id}.json`), bookmarks:
  IndexedDB or localStorage. Not synced with the phone (possible later).

## LingQ API (verified)

- Auth header `Authorization: Token <key>`.
- `GET /api/v3/{lang}/collections/my/` etc. — courses; `GET /api/v3/{lang}/collections/{course}/lessons/`.
- `GET /api/v3/{lang}/lessons/{id}/editor/` → `paragraphs[] -> sentences[]` (`text`, `translations[]{language,text}`, `index`).
- `GET /api/v2/{lang}/hints/search/?term=<word>&locale=ru` → popular meanings (`text`, `popularity`, `flagged`).
- `POST /api/v2/{lang}/translate/ {source, target, text}` → `translations[0].translatedText`.
- `POST /api/v3/{lang}/lessons/{id}/translation/ {sentence: index (1-based), language, method:"chatgpt", sentence_bulk}` — retranslate.
- Exact request/response handling: copy from `LingqApi.kt`.

## Gemini

- `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`, header `x-goog-api-key`.
- `systemInstruction`, `contents` (user/model), `generationConfig` (temperature, `responseMimeType: application/json` for words).
- Default model `gemini-3.8-flash` (2.5 retired for new users). Thinking: `thinkingLevel: "minimal"` for gemini-3,
  `thinkingBudget: 0` for gemini-2; on HTTP 400 mentioning "thinking" retry without it.
- `GET v1beta/models` → models with `generateContent` for the model picker.
- Prompts (word JSON best/options/note, sentence, retranslate, chat about sentence/text): copy from `Gemini.kt`.

## Features to port (reader first; import/fix later or never)

1. Settings: LingQ key + language; translation language; translator LingQ/Gemini, Gemini key, model picker; prompts with "reset".
2. Library: courses → lessons → reader.
3. Reader text view: words tappable.
   - tap = translation pinned above the word (green), the line moves down; tap again removes.
     With Gemini: LingQ's quick guess at once (grey), Gemini's answer replaces it.
   - double tap = word sheet: meanings as chips (no duplicates, comma-split parts of the top 2), own variant,
     chosen meaning saved only for this lesson; sentence translation, bookmark, ask chat.
   - long press = sentence translation inserted right after the sentence.
   - top bar: close ✕ left, progress bar centre, bookmarks + menu right (font size, clear pins, chat about text).
   - bottom bar: "По предложениям", "Переводы" (all sentence translations), "Чат".
4. Sentence mode: sheet sliding up over the text, opens at the sentence of the selected word, swipe left/right,
   arrow-down collapses it; progress bar + counter.
5. Sentence row actions: bookmark, retranslate (Gemini with "current translation is poor" prompt, else LingQ chatgpt), chat.
6. Bookmarks: per lesson and all; tap shows translation, long press → "Перейти к источнику" / "Удалить"; expand/collapse.
7. Chat with Gemini about the text or one sentence.

Touch on iPad: implement tap / double tap / long press yourself (pointer events + timers); disable
iOS text selection and double-tap zoom on the reader (`user-select:none`, `touch-action: manipulation`).

## Status (2026-11-05)

Done, everything above except import/fix: settings, library, reader (tap / double tap / long press, pins,
word sheet, sentence translations), sentence mode (swipe, ← → keys), bookmarks, chat, PWA (manifest, icons,
service worker). Tested against the live LingQ API in a desktop browser at iPad size; not yet on a real iPad.
Setup and file overview: `web/README.md`.
