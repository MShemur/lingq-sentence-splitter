# SatzLoop for iPad (web version)

PWA: the reader of the Android app (`android/`) as a web page installed on the iPad home screen.
Preact + TypeScript + Vite. Hosted on Cloudflare Pages; `functions/api/[[path]].ts` forwards `/api/*` to
LingQ (its API has no CORS). Gemini is called straight from the browser (it allows CORS).

## Files

- `src/lingq.ts`, `src/gemini.ts`, `src/openai.ts`, `src/prompts.ts` — LingQ / AI calls (ports of `LingqApi.kt`, `Gemini.kt`)
- `src/reader/session.ts` — reader logic (port of `ReaderSession`), `src/reader/Reader.tsx` — text view, pages, book
  mode, sentence mode; `src/reader/PdfReader.tsx` — PDF-book page view; `src/reader/Help.tsx`
- `src/pdfbook.ts` — PDF book data (IndexedDB 'satzloop-pdf'), recognising pages (pdf.js text layer, scans via
  Tesseract.js + Gemini reading order in `src/ocrorder.ts`), lesson ↔ page word alignment
- `src/importer/*` — import (paste, TXT/MD/EPUB/PDF, audio, podcasts, transcription), cleaning, fixer (= Fixer.kt), backups
- `src/screens/*` — library, import, player, podcasts, bookmarks, chat, stats, settings, feedback (in-app notes)
- `src/sync.ts` — phone ↔ iPad sync via the `sync` branch (GitHub token); `src/press.ts` — tap / double tap / long press
- `functions/api/[[path]].ts` — LingQ proxy; `functions/stt`, `functions/fetch.ts` — transcription / fetch helpers
- `public/sw.js` — service worker (offline start, page always from network first = auto-update)
- `scripts/make-icons.mjs` — draws the PNG icons (`npm run icons`)

Feature history: `PORTING.md` → Done (exact behaviour per version) and `ROADMAP.md`. Version: `package.json` (web v1.N).

## Local run

```
cd web
npm install
npm run dev
```

The dev server forwards `/api` to LingQ itself. With `LINGQ_API_KEY` set in the environment, entering the key
`dev` in the app uses that key (so the real key never has to be typed into the browser).

Testing tips: start with `$env:LINGQ_API_KEY = (Get-Content ..\lingq_key.txt -Raw).Trim(); npx vite --port 5173 --strictPort --host 127.0.0.1`
(never print the key); prefs are `localStorage` keys `pref.<name>` (reload to apply); block PATCH/PUT/DELETE fetches
when testing on real lessons; test lessons go to the course «SatzLoop PDF test». Stop the dev server before any git pull
(it locks files on Windows). No Gemini key in dev, so Gemini paths are tested with fake responses only.

## Cloudflare Pages (set up 2026-11-05)

Live at **https://lingq-sentence-splitter.pages.dev** (project `lingq-sentence-splitter`). Every push to `main`
deploys again. Settings: preset None, build `npm run build`, output `dist`, root `web`, `NODE_VERSION=22`.

How it was created (dashboard of 2026-11): Compute → Workers & Pages → Create app → link "Continue to Pages"
at the bottom → Import an existing Git repository → Connect GitHub → choose the repo → build settings above.
Optional later: Zero Trust → Access → protect the site with a one-time e-mail code.

## On the iPad

Safari → open the site → Share → "На экран «Домой»". Settings (⚙): LingQ key, language, translator.
Keys and settings stay in this browser only.
