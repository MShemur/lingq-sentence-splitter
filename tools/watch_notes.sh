#!/bin/sh
# Waits for new in-app notes (feedback/*.json with status "new" on the sync branch); costs no tokens while waiting.
# Usage: watch_notes.sh [seconds between checks, default 900] [all = check all day]
# Default: every 15 min on weekdays 03:00-07:59 and 18:00-19:59, weekends all day. Prints the new files and exits when there are any.
I=${1:-900}; ALL=${2:-}
R="$(cd "$(dirname "$0")/.." && pwd)"
while true; do
  h=$(date +%H); d=$(date +%u)
  if [ "$ALL" = all ] || [ "$d" -ge 6 ] || { [ "$h" -ge 3 ] && [ "$h" -lt 8 ]; } || { [ "$h" -ge 18 ] && [ "$h" -lt 20 ]; }; then
    if git -C "$R" fetch -q origin sync 2>/dev/null; then
      new=""
      for f in $(git -C "$R" ls-tree --name-only origin/sync feedback/); do
        git -C "$R" show "origin/sync:$f" | grep -q '"status": *"new"' && new="$new $f"
      done
      if [ -n "$new" ]; then echo "NEW:$new"; exit 0; fi
    fi
  fi
  sleep "$I"
done
