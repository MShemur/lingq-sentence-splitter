# LingQ sentence-split fixer

LingQ splits lesson text into sentences at every dot, so abbreviations (`Abb. 24`, `ca. 1500`,
`hl. Georg`, `19. Jahrhundert`, `Inv.-Nr. Gr. A. 12779 (Slg. Diehl)`) break sentences apart.
`lingq_fix_splits.py` merges those fragments back together (with their translations) directly in
the lesson's sentence structure on lingq.com. The lesson text itself is not changed.

The user talks to you in Russian, usually from a phone. Reply in Russian, short and to the point.

## Typical request

The user sends a LingQ course or lesson link, e.g.
`https://www.lingq.com/ru/learn/de/web/library/course/2951278` or
`https://www.lingq.com/ru/learn/de/web/lesson/47481004`.

1. Dry run (writes nothing to LingQ):
   `python lingq_fix_splits.py "<link>"`
2. Read `runs/<stamp>/report.md` and summarize: lessons, number of changes per lesson,
   typical abbreviations, and anything suspicious. Suspicious means a merge where the second part
   clearly starts a new sentence, or a sentence that still ends with an abbreviation.
   If an abbreviation is missing, add it to `ABBREVIATIONS` in the script and run again.
3. **Ask the user for confirmation before writing.** Never run `--apply` without an explicit "да/ок".
4. Apply: `python lingq_fix_splits.py "<link>" --apply`
5. Verify by running the dry run again. It should report `Total changes: 0`.
6. Commit the `runs/<stamp>/` folders of the apply run (they contain the backups needed for
   restore) together with any script changes, and push.

Restore a lesson: `python lingq_fix_splits.py --restore runs/<stamp>/backup/<lesson id>.json`
(take it from the run *before* the unwanted change).

## Environment

- API key comes from the `LINGQ_API_KEY` environment variable. Never print it or commit it.
  If it is missing, tell the user to add it in the cloud environment settings
  (claude.ai/code → environment → Environment variables). The key is at
  https://www.lingq.com/accounts/apikey/
- Network must allow `www.lingq.com`. A connection error or a proxy/403 message that is not
  from LingQ means the environment's network access needs `www.lingq.com` added (or "Full").
- HTTP 403 with Cloudflare "error 1010" means the User-Agent got blocked; the script already sends
  a custom one (`lingq-lesson-fixer/1.0`). If it still happens, report it rather than spoofing a browser.
- Only Python standard library is used; no install step needed.

## Android app (`android/`)

Kotlin + Jetpack Compose app "SatzLoop" (was "LingQ Fix"; package `com.mshemur.lingqfix` stays, updates need it): uploads .txt/.md/.epub or pasted text to a LingQ course,
fixes sentence splits of new or existing lessons, keeps backups on the phone, updates itself from
GitHub releases. `Fixer.kt` is a port of the merge rules in `lingq_fix_splits.py`:
**when you change ABBREVIATIONS / SENTENCE_STARTERS / AMBIGUOUS / should_merge, change both.**

- Built only in CI (`.github/workflows/android.yml`): every push touching `android/` builds a signed
  APK and publishes release `v1.<run number>` (versionCode = run number). No local SDK needed.
- Signing key is NOT in git: secrets `ANDROID_KEYSTORE_B64` (base64 of the PKCS12 file) and
  `ANDROID_KEYSTORE_PASSWORD`; alias `lingqfix`. The original file lives only on the user's PC in
  `android/app/signing/`. Never generate a new key: Android refuses updates signed with another key.
- The app downloads releases with a fine-grained GitHub token (Contents: read) entered in its settings.

## How the API works (verified 2026-11)

- Auth header `Authorization: Token <key>`.
- `GET /api/v3/{lang}/collections/{course}/lessons/`: lessons of a course.
- `GET /api/v3/{lang}/lessons/{id}/editor/`: `paragraphs[] -> sentences[]` with `text`,
  `cleanText`, `translations`, `phrases`, `notes`, `timestamp`.
- `PATCH /api/v3/{lang}/lessons/{id}/` with `{"paragraphs": [...]}` saves the sentence structure
  as sent, without re-splitting. `PATCH {"text": ...}` is silently ignored.

## Web version (`web/`, for the iPad)

PWA on Cloudflare Pages with a LingQ proxy (Pages Function). Plan, verified API facts and the feature
list: `web/PLAN.md`. The Android app is the reference implementation; keep both in step when a reader
feature changes.

## Keeping Android and web in step

Two Claude sessions work in this repo: one on `android/`, one on `web/`. `PORTING.md` is the queue
between them. **At the start of work, read it and port what is listed for your platform. After changing a
reader feature, add a line for the other platform.**

The improvement plan (what is done, what comes next, what is NOT wanted) is `ROADMAP.md`; keep it current.

### Git rules for the two sessions (agreed 2026-11-10)

The sessions share one working tree; the other session's uncommitted files are always there.

1. No `git stash`, no `--autostash`: a failed pop (Vite dev server locks files on Windows) left the other session's
   files deleted twice. Android works in `android/`, web in `web/`, so there is nothing of the other to put away.
2. Commit only your own paths (`git add android/...` or `git add web/...` plus the shared files below), then push at
   once. If the push is rejected: `git pull --no-rebase --no-edit` (a merge works with the other's dirty files as long
   as the incoming commits do not touch them; a rebase refuses a dirty tree), then push again.
3. If that pull refuses because an incoming commit touches the other session's uncommitted file: do not stash or
   overwrite anything; tell the other session (SendMessage) and wait until it has committed.
4. Shared files (`PORTING.md`, `ROADMAP.md`, `CLAUDE.md`): edit, add, commit and push in one go; never leave them
   uncommitted.
5. The web dev server runs only while testing, and is stopped before any pull / merge (its file locks break checkout).
6. After adding a `PORTING.md` line for the other platform, send the other session a short message: "в очереди для
   тебя: <version> — <the PORTING.md line>". The receiver ports it and moves the line to Done as usual.
7. `ROADMAP.md` is the shared plan: mark what got done there.

## Feedback from the apps (agreed 2026-11-11)

The user writes notes in the app ("Отзыв / задача"); they land as `feedback/*.json` on the `sync` branch. The Android
session checks them hourly (`git fetch origin sync`), does the work, then writes back into the same file `status`
("work" / "done" / "no"), `doneIn` (e.g. "android v1.85") and `reply` (short Russian), committed to `sync` from a
separate worktree (never switch the shared working tree). Web-only notes go to the web session via PORTING.md.
