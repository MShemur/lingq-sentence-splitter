# LingQ sentence-split fixer

Merges sentences that LingQ split at abbreviations (`Abb.`, `ca.`, `hl.`, `19. Jahrhundert`, ...).
Text is not changed; only the sentence structure (with translations) is fixed.

```
python lingq_fix_splits.py <course or lesson URL>            # dry run
python lingq_fix_splits.py <course or lesson URL> --apply    # write to LingQ
python lingq_fix_splits.py --restore runs/<stamp>/backup/<id>.json
```

API key: env var `LINGQ_API_KEY` (https://www.lingq.com/accounts/apikey/).
See `CLAUDE.md` for the workflow used from Claude Code on the web.

## Run from the phone (GitHub Actions)

GitHub app → this repo → Actions → **Fix LingQ sentences** → Run workflow:
- `target`: LingQ course or lesson link
- `mode`: `dry-run` (report only), `apply` (write to LingQ, backups get committed to `runs/`),
  `restore` (target = `runs/<stamp>/backup/<lesson id>.json`)

The report is shown on the run's summary page. Requires repository secret `LINGQ_API_KEY`.
