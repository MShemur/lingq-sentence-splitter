# SatzLoop: menus and settings reorganization plan

Analysis 18.11.2026 (Android v1.134, web v1.69). Goal: one clear place per thing, frequent actions first, ⋮ = actions
of the current screen only, settings grouped by topic, the same names and order on Android and iPad.
No feature is removed: items are moved, merged or renamed. «Отзыв / задача» stays in the reader ⋮ and the
sentence-mode ⋮ (user's request).

---

## 1. Current state

### 1.1 Library (main screen) top bar

| | Android (`Ui.kt`, `CourseEdit.kt` LibraryMenu) | Web (`screens/Library.tsx`) |
|---|---|---|
| Icons | Закладки, ⋮ | Закладки, **⚙ Настройки**, ⋮ |
| ⋮ | Архив курсов (N) · Слова по урокам курса… · Отзыв / задача · — · **Настройки** | Архив курсов (N) · Слова по урокам курса… · Отзыв / задача |
| FAB | «Импорт» → hub screen «Импорт» (Загрузить в LingQ / Исправить разбивку на предложения / Исправить по ссылке) | «Загрузить» → «Загрузка в LingQ» directly |
| Bottom tabs | Библиотека · Плеер · Подкасты | Библиотека · **Подкасты · Плеер** |
| Body | Today card (tap = Статистика), search + ⟳, jobs, Продолжить, Мои курсы | same |

### 1.2 Course screen ⋮ (`CourseEdit.kt` CourseMenu / `screens/CourseEdit.tsx`)
Both: Изменить курс · Слова по урокам · В архив / Вернуть из архива · — · Удалить курс… (red on Android, not red on web).

### 1.3 Settings root (`Settings.kt` SettingsRoot / `screens/Settings.tsx` SettingsRoot)

| # | Android | Web |
|---|---|---|
| 1 | Аккаунт LingQ | Аккаунт LingQ |
| 2 | Перевод | Перевод |
| 3 | Распознавание речи | Распознавание речи |
| 4 | Чтение | Чтение |
| 5 | Промпты Gemini | Синхронизация |
| 6 | Синхронизация | Резервные копии |
| 7 | — divider — | Журнал действий |
| 8 | Очистить кэш аудио (an action, not a section) | Отзыв / задача |
| 9 | Резервные копии | Промпты ИИ (page title «Промпты Gemini») |
| 10 | Обновления (version, check, install, **GitHub-токен**) | — / info row «SatzLoop для iPad vN» |
| 11 | Отзыв / задача | |
| 12 | Журнал действий | |

Settings → Чтение:
- Android: Фон страницы · Цвет выделения слов (+ на цветном фоне / без цвета, preview) · Расстояние до перевода над словом (slider %) · Строки · Абзацы · Книжный режим (switch) · Выравнивание · Экран не гаснет · Максимальная яркость · **Наушники** («вперёд» читает перевод).
- Web: Фон · Цвет выделения · Шрифт текста · Выравнивание · Книжный режим (chips Выключен/Включён) · Строки · Абзацы · Перевод над словом (chips, px) · Экран не гаснет.

Settings → Синхронизация: Android tells you to paste the GitHub token in «Обновления»; web has the token field here.

Prompts page: field order differs (Android: …Чат о предложении, Очистка, Чат о слове, Чат о тексте…; web: …Чат о предложении, Чат о слове, Чат о тексте, Очистка…); «при импорте» vs «при загрузке».

### 1.4 Reader ⋮ (`Reader.kt` ReaderActions / `reader/Reader.tsx` ReaderActions)

| Android | Web |
|---|---|
| **Урок**: Слушать в плеере · Определить уровень (ИИ) · Обработать текст с ИИ | **Урок**: Слушать в плеере · Определить уровень (ИИ) · Обработать текст с ИИ… · **Удалить урок…** |
| **Текст**: Размер текста A− N A+ (one row) · [only with pins:] Слова урока (N) · **Перевести весь урок** · Временно скрыть переводы · Снять переводы (N) · Показать страницы PDF · Книжный режим ☑ · Оформление и экран | **Вид**: Шрифт крупнее A+ · Шрифт мельче A− · Книжный режим ✓ · Показать страницы PDF · Оформление и экран · Перевести весь урок · Слова с переводом (N) · Временно скрыть переводы · Снять переводы (N) |
| — Как пользоваться · Отзыв / задача · — · Удалить урок… (red) | **Помощь**: Как пользоваться · Отзыв / задача |

### 1.5 Sentence mode ⋮
- Android: Редактировать предложение · Слушать отсюда · Отзыв / задача · — · Размер текста row.
- Web: «Предложение»: Редактировать предложение · Слушать отсюда · — «Текст»: Крупнее A+ · Мельче A− · — Отзыв / задача.

### 1.6 Word menus
- Android long press (text and sentence mode; double tap in book mode): [book: Перевод слова] · Перевод предложения · В закладки · [book: Слово жирным] · **Исправить предложение** · Слушать отсюда · Чат о предложении.
- Web: no long-press menu in text mode (user's iPad wish, ROADMAP web v1.38: long press = sentence translation). Book-mode double tap: Перевод слова · Перевод предложения · Закладка на предложение · Чат о предложении · Сделать жирным (no Исправить / Слушать отсюда).
- Player long press: Перевод слова · Перевод предложения · Начало повтора · Конец повтора (same on both).

### 1.7 Player
Header: bookmark icon (add audio bookmark) + ⋮ with one item «Аудиозакладки (N)»; dead item «Другой урок» (its `onPick` is always null on both).

### 1.8 Other screens
Bookmarks (expand icon; item menu: Слушать с этого места / Перейти к источнику · Удалить закладку), Podcasts, Stats, Word stats, Archive, Import: no menu problems.

---

## 2. Problems

**Duplicates**
1. Web library has ⚙ and ⋮ next to each other; Android has Settings inside ⋮. The ⋮ is half app-level stuff.
2. «Отзыв / задача» in 4 places: library ⋮, Settings, reader ⋮, sentence ⋮. Library ⋮ copy has no reason.
3. «Слова по урокам курса…» in library ⋮ (with a course picker) duplicates «Слова по урокам» in the course ⋮.
4. Android: the fix-split flow has its own hub screen behind the FAB «Импорт» (with its own course list that duplicates the library).

**Misplaced**
5. Library ⋮ holds Настройки and Отзыв: app-level, not actions of the library.
6. Android Settings root: «Очистить кэш аудио» is an action in a list of sections; «Промпты Gemini» is a root section although it belongs to translation/AI; «Журнал» / «Отзыв» / «Обновления» are mixed with topic sections (no grouping at all).
7. Settings → Чтение holds «Наушники» (a player setting).
8. Android GitHub token lives in «Обновления», but Синхронизация needs it too and points there; web has it in Синхронизация.
9. Reader ⋮ (web): «Удалить урок…» sits between normal actions, not last, not red. Android: «Перевести весь урок» is hidden unless the lesson already has word translations (wrong `if` block).
10. Player ⋮ with a single item and a dead item.

**Inconsistencies Android vs web**
11. Bottom tab order (Плеер / Подкасты swapped).
12. FAB «Импорт» vs «Загрузить»; screen titles «Импорт» / «Загрузка в LingQ».
13. Reader ⋮ group names (Текст / Вид), item order, «Слова урока» vs «Слова с переводом», font size as one row vs two items.
14. «Редактировать предложение» (sentence ⋮) vs «Исправить предложение» (word menu, help).
15. «Слово жирным» vs «Сделать жирным»; «В закладки» vs «Закладка на предложение».
16. «Промпты Gemini» vs «Промпты ИИ»; section summaries differ (Синхронизация, Резервные копии, Журнал, Чтение).
17. Settings → Чтение: different order, different controls (switch vs chips for Книжный режим; slider % vs chips px for the translation gap).
18. Web help (`reader/Help.tsx`) still says a long press opens a word menu; it does not.
19. Web «В архив» toast says «⋮ в библиотеке» (will move).

---

## 3. Target layout (identical on both; exceptions marked **[A]** = Android only, **[W]** = web only)

### 3.1 Library
- Top bar: title «Библиотека · de» · icons **Закладки**, **Настройки** (⚙). **No ⋮.**
- Body: Today card (tap = Статистика) · search + ⟳ · jobs · Продолжить · Мои курсы · at the end of the grid, if any:
  row **«Архив курсов (N) ›»**.
- FAB: **«Загрузить»** → «Загрузка в LingQ».
- Bottom tabs: **Библиотека · Плеер · Подкасты**.

Why: the archive is where hidden courses are, so it goes under the courses; word stats is a course action (course ⋮);
feedback is in Settings and in the reader. Settings gets the standard gear: one tap, one place.

### 3.2 Course screen ⋮
1. Изменить курс
2. Слова по урокам
3. **Исправить разбивку…** [A] (checks all lessons of this course; was hub → Исправить разбивку → course)
4. В архив / Вернуть из архива
5. — Удалить курс… (red, both)

### 3.3 Загрузка в LingQ (import screen)
Source tiles as now. [A] at the bottom: section «Уже на LingQ» with the field «Ссылка на урок или курс LingQ» +
«Проверить» (moved from the hub screen; sharing a LingQ link to the app keeps working). The hub screen «Импорт» is removed.
[W]: no split checker for existing lessons on web (not ported), so no such section.

### 3.4 Settings root (M3 list with small group headers)
**Сервисы**
1. Аккаунт LingQ — «Ключ задан · язык de»
2. **Перевод и ИИ** — «Gemini · model → ru» (page gets a last row **«Промпты ИИ ›»**)
3. Распознавание речи — «Groq · ключ задан»

**Чтение и звук**
4. Чтение — «<фон> · выделение: <цвет>»
5. **Плеер** [A] — «Наушники · кэш аудио N МБ» (page: «Кнопка «вперёд» читает перевод» + «Очистить кэш аудио»)

**Данные**
6. Синхронизация — «Включена» / «Выключена» / «Ошибка» (page also holds **Токен GitHub** on both)
7. Резервные копии — «Вернуть урок к версии до исправления»

**Приложение**
8. Обновления — [A] «Версия vN» → page: version, Проверить обновления, Установить (token: a hint + link to Синхронизация if missing); [W] info row, not clickable: «SatzLoop для iPad vN · обновляется сама»
9. Журнал действий — «Для сообщения об ошибке · хранится 2 дня»
10. Отзыв / задача — «Написать, что поправить; Claude отвечает здесь»

[W] keeps its footer hint about keys stored in the browser.

### 3.5 Settings → Чтение (same order)
**Страница**: Фон страницы · Цвет выделения слов (+ «Слово на цветном фоне» / «Без цвета…», preview)
**Текст**: Шрифт текста [W] (Android: port later via PORTING, not part of this change) · Выравнивание текста ·
Расстояние между строками · Расстояние между абзацами · «Вернуть обычные расстояния» · **Расстояние до перевода над словом**
**Режим**: Книжный режим (switch on both)
**Экран**: Экран не гаснет при чтении · Максимальная яркость [A] (browser cannot)
(«Наушники» moved to Плеер [A].)

### 3.6 Settings → Перевод и ИИ → Промпты ИИ (field order)
Перевод слова · Перевод предложения · Повторный перевод · Чат о предложении · Чат о слове («Спросить» в окне слова) ·
Чат о всём тексте · Очистка текста при загрузке · Уровень текста · [Вернуть стандартные]. Page title «Промпты ИИ».

### 3.7 Reader top bar and ⋮
Top bar: ✕ · page slider · Закладки урока · ⋮.

⋮ (headers in small primary text):
**Текст**
1. Размер текста  A−  18  A+ (one row, menu stays open)
2. Книжный режим ☑
3. Показать страницы PDF / Показать текстом (PDF lessons only)
4. **Настройки чтения…** (opens Settings → Чтение; deliberate shortcut)

**Переводы**
5. Перевести весь урок / «Перевожу урок: n из m» (always shown)
6. **Слова с переводом (N)** (N > 0)
7. Временно скрыть переводы / Показать переводы над словами (N > 0 or hidden)
8. Снять переводы над словами (N) (N > 0)

**Урок**
9. Слушать в плеере (lesson has audio)
10. Определить уровень (ИИ)
11. Обработать текст с ИИ…
12. Исправить разбивку… [A] (this lesson)

— 13. Как пользоваться · 14. Отзыв / задача (kept on request)
— 15. **Удалить урок…** (red, last, both)

Deliberate shortcuts: «Книжный режим» (also in Settings → Чтение; toggled often while reading), «Настройки чтения…»,
«Отзыв / задача» (user's request), «Закладки урока» icon (the dialog links to all bookmarks).

### 3.8 Sentence mode ⋮ (no group headers: 4 items)
1. **Исправить предложение**
2. Слушать отсюда (sentence has a time)
— 3. Размер текста  A−  18  A+ (one row)
— 4. Отзыв / задача

### 3.9 Word menu (long press; double tap in book mode)
1. Перевод слова (book mode only)
2. Перевод предложения / Скрыть перевод предложения
3. **В закладки** / Убрать закладку
4. **Сделать жирным** / Убрать жирный (book mode only)
5. Исправить предложение
6. Слушать отсюда (sentence has a time)
7. Чат о предложении

Platform exception: [W] text mode has no long-press menu (user's iPad wish: long press = sentence translation);
the book-mode double-tap menu uses the full list above. [A] keeps the long-press menu. (If the user wants the iPad
behaviour on the phone too, that is a separate decision.)

### 3.10 Player
Header: «Закладка на это место» icon · ⋮ → «Аудиозакладки (N)». Word menu unchanged. Remove the dead «Другой урок».

---

## 4. Checklist

### Android (`android/app/src/main/java/com/mshemur/lingqfix/`)
- [x] `Ui.kt` top bar, `Screen.ReaderCourses`: replace `LibraryMenu(state)` with an `IconButton` `Icons.Filled.Settings`, «Настройки» → `Screen.Settings`.
- [x] `Ui.kt` FAB: text «Загрузить» (icon upload/add), `onClick` → `state.go(Screen.Import(ImportSession(state)))`.
- [x] `Ui.kt`: remove `HomeScreen`, `Screen.Home` routing/title; move its link field + «Проверить» (`state.openLink`) to the bottom of `ImportScreen` under header «Уже на LingQ». `CoursesScreen` (non-library) and the non-reader branch of `LessonsScreen` become unused: remove. `AppState.kt`: drop `Screen.Home`, `Screen.Courses`.
- [x] `CourseEdit.kt`: delete `LibraryMenu`. `CourseMenu`: add «Исправить разбивку…» (icon Build) after «Слова по урокам» → `state.checkLessons(s.lang, s.lessons!!, s.title)`, enabled when lessons are loaded; give «В архив» an icon like web.
- [x] `Library.kt` `LibraryScreen`: after the course items add a full-width row «Архив курсов (N) ›» → `Screen.Archive`, only if `state.archivedCourses()` is not empty.
- [x] `Settings.kt` `SettingsRoot`: group headers Сервисы / Чтение и звук / Данные / Приложение, order as 3.4; rename «Перевод» → «Перевод и ИИ»; remove root rows «Промпты Gemini» and «Очистить кэш аудио»; add «Плеер» → new `Screen.SettingsPlayer`; summaries as 3.4.
- [x] `Settings.kt` `SettingsTranslation`: last row `ListItem` «Промпты ИИ ›» → `Screen.SettingsPrompts`. `SettingsPrompts`: field order and labels as 3.6.
- [x] New `SettingsPlayer` (e.g. in `Settings.kt`): the «Наушники» block moved from `ReaderLook.kt` + «Очистить кэш аудио» (size, clear) moved from the root. Add to `AppState.kt` `Screen`, routing and `titleOf` («Плеер»).
- [x] `Settings.kt` `SettingsUpdates`: move the GitHub token `SecretField` + hint to `Sync.kt` `SettingsSync` (section «Токен GitHub», like web); in Updates leave a hint «Токен GitHub задаётся в «Синхронизации»» + button there when blank. In `SettingsSync` drop the «Вставить токен» link to Updates.
- [x] `ReaderLook.kt` `SettingsReading`: order and headers as 3.5; «Наушники» out.
- [x] `Ui.kt` `titleOf`: «Перевод и ИИ», «Промпты ИИ», «Плеер».
- [x] `Reader.kt` `ReaderActions`: order as 3.7; «Перевести весь урок» out of the `pins.isNotEmpty()` block; «Слова урока» → «Слова с переводом» (also `MarkedWordsDialog` title); «Оформление и экран» → «Настройки чтения…»; add «Исправить разбивку…» → `state.checkLessons(s.lang, listOf(s.lesson), s.title)`.
- [x] `Reader.kt` `SentenceMenu`: «Редактировать предложение» → «Исправить предложение»; order as 3.8.
- [x] `Reader.kt` `ReaderMenuItems`: «Слово жирным» → «Сделать жирным»; order as 3.9.
- [x] `Reader.kt` `ReaderHelp`: update «Оформление и экран» → «Настройки чтения», and other moved items.
- [x] `Player.kt` `PlayerActions`: remove the unused `onPick` / «Другой урок».
- [x] `CourseEdit.kt` `ArchiveScreen` empty text: fine as is.
- Done in android v1.136. Differences: the old course list for checking is gone, a course link in «Проверить» / sharing checks the whole course at once (one lesson: reader ⋮ → «Исправить разбивку…»); the unused course picker in `WordStats.kt` is left in place; «Шрифт текста» deferred.

### Web (`web/src/`)
- [x] `screens/Library.tsx` `CoursesScreen`: remove the ⋮ (`menu` state, `Menu`, three items); keep Закладки + Настройки icons; add the «Архив курсов (N) ›» row after the grid when N > 0.
- [x] `screens/Player.tsx` `MainTabs`: order Библиотека · Плеер · Подкасты. `PlayerActions`: remove `onPick` / «Другой урок».
- [x] `screens/WordStats.tsx` `WordStatsPicker` + route `wordStatsPick` (`app.ts`, `main.tsx`): unreachable now, remove.
- [x] `screens/CourseEdit.tsx` `CourseMenu`: delete item red (needs `danger` prop on `MenuItem` in `ui.tsx` + style); toast «Курс убран в архив (⋮ в библиотеке)» → «Курс убран в архив (внизу библиотеки)».
- [x] `screens/Settings.tsx` `SettingsRoot`: group headers and order as 3.4 (Плеер absent); «Перевод» → «Перевод и ИИ»; remove root «Промпты ИИ»; info row headline «Обновления», supporting «SatzLoop для iPad vN · сборка … · обновляется сама»; summaries as 3.4.
- [x] `screens/Settings.tsx` `SettingsTranslation`: last `ListItem` «Промпты ИИ ›». `SettingsPrompts`: title «Промпты ИИ», labels as 3.6 (order already right).
- [x] `screens/Settings.tsx` `SettingsReading`: order and headers as 3.5; Книжный режим as a `switch-row`; «Перевод над словом» → «Расстояние до перевода над словом».
- [x] `reader/Reader.tsx` `ReaderActions`: order and groups as 3.7 (Вид → Текст, new group Переводы, Помощь header dropped); font as one row «Размер текста A− N A+» that keeps the menu open; «Удалить урок…» last, red; «Оформление и экран» → «Настройки чтения…».
- [x] `reader/Reader.tsx` `SentenceMenu`: no headers; «Исправить предложение»; font row; order as 3.8.
- [x] `reader/Reader.tsx` `BookMenuItems`: labels and order as 3.9 («В закладки»), add «Исправить предложение» and «Слушать отсюда» (when `s.hasTime(id)`).
- [x] `reader/Help.tsx`: long press = sentence translation (no menu); «⋮ → «Настройки чтения»»; «⋮ → размер текста».
- [x] Styles for the settings group header, the menu font row and the red menu item go in `styles.css`: coordinate with the session editing PDF files there.

### Both
- [x] Add a `PORTING.md` / `ROADMAP.md` line when done; raise versions (Android v1.136, web v1.71).
