# Porting queue between the Android app and the web version

When a session adds or changes a reader feature on one platform, it adds a line here under the other
platform ("to do on web" / "to do on Android"): what changed, the commit, the file. The session working on
the other platform checks this file at the start, ports the change, and moves the line to "Done" with its commit.
Platform-specific things (APK updates, iPad gestures) are not listed.

## To do on web

(the web version starts from `web/PLAN.md`, which already covers Android v1.23)

- Text view is paged, not one long scroll: pages fit the screen, swipe left/right turns them; a paragraph
  that does not fit is split at a sentence boundary; pages are rebuilt when pins/translations/font change
  and the page being read is kept; page counter "3 / 12" under the progress bar. Sentence mode collapses
  slowly (~0.5 s). Android commit 53b0f28, `Reader.kt` (`PagedText`, `TextPager`).

## To do on Android

## Done
