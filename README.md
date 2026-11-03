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
