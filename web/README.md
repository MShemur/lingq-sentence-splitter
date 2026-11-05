# LingQ Fix for iPad (web version)

PWA: the reader of the Android app (`android/`) as a web page installed on the iPad home screen.
Preact + TypeScript + Vite. Hosted on Cloudflare Pages; `functions/api/[[path]].ts` forwards `/api/*` to
LingQ (its API has no CORS). Gemini is called straight from the browser (it allows CORS).

## Files

- `src/lingq.ts`, `src/gemini.ts`, `src/prompts.ts` — ports of `LingqApi.kt`, `Gemini.kt`
- `src/reader/session.ts` — reader logic (port of `ReaderSession`), `src/reader/Reader.tsx` — its UI
- `src/screens/*` — library, bookmarks, chat, settings; `src/press.ts` — tap / double tap / long press
- `public/sw.js` — service worker (offline start, page always from network first = auto-update)
- `scripts/make-icons.mjs` — draws the PNG icons (`npm run icons`)

## Local run

```
cd web
npm install
npm run dev
```

The dev server forwards `/api` to LingQ itself. With `LINGQ_API_KEY` set in the environment, entering the key
`dev` in the app uses that key (so the real key never has to be typed into the browser).

## Cloudflare Pages (set up 2026-11-05)

Live at **https://lingq-sentence-splitter.pages.dev** (project `lingq-sentence-splitter`). Every push to `main`
deploys again. Settings: preset None, build `npm run build`, output `dist`, root `web`, `NODE_VERSION=22`.

How it was created (dashboard of 2026-11): Compute → Workers & Pages → Create app → link "Continue to Pages"
at the bottom → Import an existing Git repository → Connect GitHub → choose the repo → build settings above.
Optional later: Zero Trust → Access → protect the site with a one-time e-mail code.

## On the iPad

Safari → open the site → Share → "На экран «Домой»". Settings (⚙): LingQ key, language, translator.
Keys and settings stay in this browser only.
