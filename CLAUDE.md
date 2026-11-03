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
2. Read `runs/<stamp>/report.txt` and summarize: lessons, number of changes per lesson,
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

## How the API works (verified 2026-11)

- Auth header `Authorization: Token <key>`.
- `GET /api/v3/{lang}/collections/{course}/lessons/`: lessons of a course.
- `GET /api/v3/{lang}/lessons/{id}/editor/`: `paragraphs[] -> sentences[]` with `text`,
  `cleanText`, `translations`, `phrases`, `notes`, `timestamp`.
- `PATCH /api/v3/{lang}/lessons/{id}/` with `{"paragraphs": [...]}` saves the sentence structure
  as sent, without re-splitting. `PATCH {"text": ...}` is silently ignored.
