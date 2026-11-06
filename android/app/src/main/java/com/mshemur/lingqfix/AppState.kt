package com.mshemur.lingqfix

import android.content.Context
import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object SettingsLingq : Screen
    data object SettingsTranslation : Screen
    data object SettingsPrompts : Screen
    data object SettingsUpdates : Screen
    data object SettingsReading : Screen
    data object Stats : Screen
    data object SettingsSync : Screen
    data object Courses : Screen
    data object Backups : Screen
    class Import(val session: ImportSession) : Screen
    class Check(val session: CheckSession) : Screen

    data object ReaderCourses : Screen
    data object Bookmarks : Screen
    class Chat(val session: ChatSession) : Screen
    class Reader(val session: ReaderSession) : Screen

    /** Lessons of a course: [reader] = open the lesson for reading, otherwise check/fix it. */
    class Lessons(val lang: String, val course: Course, val reader: Boolean = false) : Screen {
        var title by mutableStateOf(course.title.ifBlank { "Курс ${course.id}" })
        var lessons by mutableStateOf<List<Lesson>?>(null)
        var error by mutableStateOf<String?>(null)

        fun load(app: AppState) {
            error = null
            lessons = null
            app.scope.launch {
                try {
                    val (t, l) = withContext(Dispatchers.IO) { app.api.lessons(lang, course.id) }
                    if (t.isNotBlank()) title = t
                    lessons = l
                    app.setCourseWords(course.id, l.sumOf { it.words })
                } catch (e: Throwable) {
                    error = e.message ?: e.toString()
                }
            }
        }
    }
}

class AppState(val ctx: Context) {
    val prefs = Prefs(ctx)
    val backups = Backups(ctx)
    val bookmarks = BookmarkStore(ctx)
    val progress = ReadingProgress(ctx)
    val stats = ReadingStats(ctx)
    val sync = SyncService(this)
    /** The app is on the screen (between onResume and onPause). */
    var resumed = false
    val scope = MainScope()
    val snackbar = SnackbarHostState()

    /** Main screen = library (reader); import/fix and settings open from its top bar. */
    val stack = mutableStateListOf<Screen>(Screen.ReaderCourses)
    val screen: Screen get() = stack.last()

    var apiKey by mutableStateOf(prefs.apiKey)
    var lang by mutableStateOf(prefs.lang)
    var ghToken by mutableStateOf(prefs.ghToken)
    var trLang by mutableStateOf(prefs.trLang)
    val api: LingqApi get() = LingqApi(apiKey)
    private val otherAi get() = prefs.translator == "openai"
    /** Main model: chat and retranslation (Gemini or the other service). */
    fun gemini(): Llm = if (otherAi) OpenAiCompat(prefs.aiBase, prefs.aiKey, prefs.aiModel) else Gemini(prefs.geminiKey, prefs.geminiModel)
    /** Light model for the many small requests: words, phrases, sentence translations, cleaning. */
    fun geminiFast(): Llm =
        if (otherAi) OpenAiCompat(prefs.aiBase, prefs.aiKey, prefs.aiFastModel.ifBlank { prefs.aiModel })
        else Gemini(prefs.geminiKey, prefs.geminiFastModel.ifBlank { prefs.geminiModel })
    /** Name of the AI in use, for messages. */
    val aiName get() = if (otherAi) "ИИ" else "Gemini"
    /** Reader translates with Gemini (chosen in settings and a key is set). */
    /** An AI translator is on: Gemini, or another OpenAI-compatible service. */
    val useGemini get() = (prefs.translator == "gemini" && prefs.geminiKey.isNotBlank()) ||
        (prefs.translator == "openai" && prefs.aiKey.isNotBlank() && prefs.aiBase.isNotBlank())

    var courses by mutableStateOf<List<Course>?>(null)
    var coursesError by mutableStateOf<String?>(null)
    private var coursesLang = ""

    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableStateOf<Float?>(null)
    /** Pop-up "new version" shown once per version per app start. */
    var updateDialog by mutableStateOf(false)
    /** Stack trace of the last crash, shown once at start. */
    var crashReport by mutableStateOf(CrashLog.take(ctx))
    /** Gemini hit a limit: window offering another model. */
    var modelSwitch by mutableStateOf<ModelSwitch?>(null)

    /** Called where a Gemini request failed: on a limit error the user may switch the model and [retry]. */
    fun geminiFailed(e: Throwable, fast: Boolean = false, retry: () -> Unit) {
        if (!useGemini || !isGeminiLimit(e) || modelSwitch != null) return
        modelSwitch = when {
            otherAi && fast -> ModelSwitch(prefs.aiFastModel.ifBlank { prefs.aiModel }, e.message.orEmpty(), { m -> applyPrefs { aiFastModel = m } }, retry)
            otherAi -> ModelSwitch(prefs.aiModel, e.message.orEmpty(), { m -> applyPrefs { aiModel = m } }, retry)
            fast -> ModelSwitch(prefs.geminiFastModel.ifBlank { prefs.geminiModel }, e.message.orEmpty(), { m -> applyPrefs { geminiFastModel = m } }, retry)
            else -> ModelSwitch(prefs.geminiModel, e.message.orEmpty(), { m -> applyPrefs { geminiModel = m } }, retry)
        }
    }
    private var dialogShownFor = 0

    fun go(s: Screen) { stack.add(s) }

    /** Opens the lesson of a bookmark at that sentence (reuses the reader if this lesson is already open). */
    fun openBookmark(b: Bookmark) {
        val open = stack.indexOfLast { it is Screen.Reader && it.session.lesson.id == b.lessonId }
        if (open >= 0) {
            while (stack.size > open + 1) stack.removeAt(stack.lastIndex)
            (stack.last() as Screen.Reader).session.jumpTo(b.sent)
        } else go(Screen.Reader(ReaderSession(this, b.lang, Lesson(b.lessonId, b.lessonTitle), startSent = b.sent)))
    }
    fun toRoot() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    var readerTheme by mutableStateOf(prefs.readerTheme)
    var highlight by mutableStateOf(prefs.highlight)
    var keepAwake by mutableStateOf(prefs.keepAwake)
    /** Time of the last touch (uptime ms, updated at most every few seconds): screen timeout while reading. */
    var lastTouch by mutableStateOf(android.os.SystemClock.uptimeMillis())

    fun touched() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastTouch > 3000) lastTouch = now
    }

    /** Changes settings right away and keeps the screen state in sync. */
    fun applyPrefs(block: Prefs.() -> Unit) {
        val oldLang = lang
        prefs.block()
        apiKey = prefs.apiKey; lang = prefs.lang; ghToken = prefs.ghToken; trLang = prefs.trLang
        readerTheme = prefs.readerTheme; highlight = prefs.highlight; keepAwake = prefs.keepAwake
        if (oldLang != lang) courses = null
    }

    fun saveSettings(key: String, language: String, token: String, translation: String) {
        prefs.apiKey = key; prefs.lang = language; prefs.ghToken = token; prefs.trLang = translation
        val langChanged = prefs.lang != lang
        apiKey = prefs.apiKey; lang = prefs.lang; ghToken = prefs.ghToken; trLang = prefs.trLang
        if (langChanged) courses = null
        say("Сохранено")
    }

    /** Words in each course (sum over its lessons); kept on the phone, refreshed once per app start. */
    val courseWords = androidx.compose.runtime.mutableStateMapOf<Int, Int>().apply {
        runCatching {
            val o = org.json.JSONObject(ctx.getSharedPreferences("progress", Context.MODE_PRIVATE).getString("courseWords", "{}")!!)
            o.keys().forEach { k -> put(k.toInt(), o.getInt(k)) }
        }
    }
    private val wordsFetched = HashSet<Int>()
    private val wordsLimit = kotlinx.coroutines.sync.Semaphore(3)

    fun setCourseWords(id: Int, words: Int) {
        wordsFetched += id
        courseWords[id] = words
        val o = org.json.JSONObject()
        courseWords.forEach { (k, v) -> o.put(k.toString(), v) }
        ctx.getSharedPreferences("progress", Context.MODE_PRIVATE).edit().putString("courseWords", o.toString()).apply()
    }

    fun ensureCourseWords(lang: String, id: Int) {
        if (!wordsFetched.add(id)) return
        scope.launch {
            runCatching {
                wordsLimit.acquire()
                try { withContext(Dispatchers.IO) { api.lessons(lang, id).second.sumOf { it.words } } } finally { wordsLimit.release() }
            }.onSuccess { setCourseWords(id, it) }.onFailure { wordsFetched.remove(id) }
        }
    }

    /** Lessons opened lately, for the "continue" row of the library. */
    var recent by mutableStateOf<List<Lesson>?>(null)

    fun loadRecent() {
        val l = lang
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.recentLessons(l) } }.onSuccess { if (lang == l) recent = it }
        }
    }

    fun loadCourses(force: Boolean = false) {
        if (!force && courses != null && coursesLang == lang) return
        val l = lang
        courses = null
        coursesError = null
        scope.launch {
            try {
                courses = withContext(Dispatchers.IO) { api.myCourses(l) }
                coursesLang = l
            } catch (e: Throwable) {
                coursesError = e.message ?: e.toString()
            }
        }
    }

    // --- entry points -------------------------------------------------------------------------

    /** From the widget: open that lesson (unless it is already on the screen). */
    fun openLesson(lang: String, id: Int, title: String) {
        val top = stack.lastOrNull()
        if (top is Screen.Reader && top.session.lesson.id == id) return
        go(Screen.Reader(ReaderSession(this, lang, Lesson(id, title))))
    }

    /** A lesson was opened in the reader: remember it for the widget (cover fetched if missing). */
    fun lessonOpened(lang: String, l: Lesson) {
        ContinueWidget.save(ctx, lang, l)
        if (l.imageUrl.isBlank()) scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.lesson(lang, l.id) } }.onSuccess { full ->
                if (full.imageUrl.isNotBlank()) ContinueWidget.save(ctx, lang, full.copy(title = l.title.ifBlank { full.title }))
            }
        }
    }

    fun openLink(text: String) {
        val t = parseTarget(text) ?: return say("Не понял ссылку")
        val language = t.lang ?: lang
        when {
            t.lessonId != null -> checkLessons(language, listOf(Lesson(t.lessonId, "")), "Урок ${t.lessonId}")
            t.courseId != null -> go(Screen.Lessons(language, Course(t.courseId, "")))
        }
    }

    fun checkLessons(language: String, lessons: List<Lesson>, label: String) {
        val s = CheckSession(this, language, lessons, label)
        go(Screen.Check(s))
        s.start()
    }

    fun openImportFile(uri: Uri) {
        val s = ImportSession(this)
        go(Screen.Import(s))
        s.loadFile(uri)
    }

    fun openImportText(text: String, title: String?) {
        val s = ImportSession(this)
        s.pasteTitle = title.orEmpty()
        s.paste(text)
        go(Screen.Import(s))
    }

    fun restore(e: Backups.Entry) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.saveParagraphs(e.lang, e.id, backups.paragraphs(e)) }
                say("Урок возвращён ✅")
            } catch (ex: Throwable) {
                say(ex.message ?: "Ошибка")
            }
        }
    }

    // --- updates -------------------------------------------------------------------------------

    fun checkUpdate(quiet: Boolean) {
        if (ghToken.isBlank()) { if (!quiet) say("Укажите GitHub-токен в настройках"); return }
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { Updater.latest(ghToken) }
                if (r != null && r.code > BuildConfig.VERSION_CODE) {
                    update = r
                    if (dialogShownFor != r.code && updateProgress == null) { dialogShownFor = r.code; updateDialog = true }
                    if (!quiet) say("Доступна версия ${r.versionName}")
                } else if (!quiet) say("У вас последняя версия (${BuildConfig.VERSION_NAME})")
            } catch (e: Throwable) {
                if (!quiet) say(e.message ?: "Ошибка проверки обновлений")
            }
        }
    }

    fun installUpdate() {
        val r = update ?: return
        scope.launch {
            try {
                updateProgress = 0f
                val f = withContext(Dispatchers.IO) { Updater.download(ctx, ghToken, r) { p -> updateProgress = p } }
                updateProgress = null
                if (!Updater.install(ctx, f)) say("Разрешите установку для LingQ Fix и нажмите «Обновить» ещё раз")
            } catch (e: Throwable) {
                updateProgress = null
                say(e.message ?: "Ошибка загрузки обновления")
            }
        }
    }
}

/** Dry run over some lessons, then (after confirmation) writing the fixes with backups. */
class CheckSession(private val app: AppState, val lang: String, private val lessons: List<Lesson>, label: String) {
    var label by mutableStateOf(label)
    var running by mutableStateOf(true)
    var applying by mutableStateOf(false)
    var done by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var checked by mutableIntStateOf(0)
    var status by mutableStateOf("")
    var error by mutableStateOf<String?>(null)
    val results = mutableStateListOf<LessonFix>()
    val savedIds = mutableStateListOf<Int>()

    val total get() = lessons.size
    val changeCount get() = results.sumOf { it.changes.size }

    fun start() {
        app.scope.launch {
            try {
                for ((i, l0) in lessons.withIndex()) {
                    status = "Проверяю ${i + 1}/${lessons.size}: ${l0.title}"
                    val fix = withContext(Dispatchers.IO) {
                        val l = if (l0.title.isBlank()) app.api.lesson(lang, l0.id) else l0
                        val orig = app.api.editor(lang, l.id)
                        val fixed = JSONArray(orig.toString())
                        LessonFix(l, orig, fixed, Fixer.fix(fixed))
                    }
                    if (lessons.size == 1) label = fix.lesson.title
                    if (fix.changes.isNotEmpty()) results.add(fix)
                    checked = i + 1
                    progress = (i + 1f) / lessons.size
                    delay(150)
                }
                status = ""
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            running = false
        }
    }

    fun apply() {
        app.scope.launch {
            applying = true
            error = null
            try {
                for ((i, r) in results.toList().withIndex()) {
                    if (r.lesson.id in savedIds) continue
                    status = "Записываю ${i + 1}/${results.size}: ${r.lesson.title}"
                    withContext(Dispatchers.IO) {
                        app.backups.save(lang, r.lesson, r.original)
                        app.api.saveParagraphs(lang, r.lesson.id, r.fixed)
                    }
                    savedIds.add(r.lesson.id)
                    delay(1000)
                }
                done = true
                status = ""
                app.say("Записано в LingQ ✅")
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            applying = false
        }
    }
}

class ImportItem(title: String, text: String, selected: Boolean) {
    var title by mutableStateOf(title)
    var text by mutableStateOf(text)
    var selected by mutableStateOf(selected)
    val words get() = countWords(text)
}

/** Upload of a file / pasted text to a course, optionally fixing the sentence split right away. */
class ImportSession(private val app: AppState) {
    var sourceName by mutableStateOf("")
    val items = mutableStateListOf<ImportItem>()
    var isBook by mutableStateOf(false)
    var loading by mutableStateOf(false)
    var pasteMode by mutableStateOf(false)
    var pasteTitle by mutableStateOf("")
    var pasteText by mutableStateOf("")
    var course by mutableStateOf<Course?>(null)
    var newCourse by mutableStateOf(false)
    var newCourseTitle by mutableStateOf("")
    var autoFix by mutableStateOf(true)
    var splitWords by mutableStateOf("3000")
    var running by mutableStateOf(false)
    var finished by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    val log = mutableStateListOf<String>()
    var error by mutableStateOf<String?>(null)
    /** Lessons whose sentence split was fixed right after the upload (for the report). */
    val fixes = mutableStateListOf<LessonFix>()

    // --- reading the source: file, PDF (text layer or OCR), photos ---

    /** What is being done while [loading] ("Распознаю страницу 3 из 7…"). */
    var status by mutableStateOf("")

    private fun setSingle(title: String, text: String) {
        pasteMode = true; isBook = false; items.clear()
        pasteTitle = title; pasteText = text
    }

    private fun startLoad(msg: String) {
        loading = true; status = msg; error = null
        cleanNote = null; before = null; canUndo = false
    }

    fun paste(text: String) {
        startLoad(""); loading = false
        setSingle(pasteTitle, text)
        if (app.prefs.autoClean && text.isNotBlank()) cleanQuick()
    }

    /** Photos of pages (gallery or camera): recognised on the phone; [append] adds them to the text. */
    fun loadPhotos(uris: List<Uri>, append: Boolean) {
        if (uris.isEmpty()) return
        val keep = append && pasteMode && pasteText.isNotBlank()
        startLoad("Распознаю фото…")
        app.scope.launch {
            try {
                val parts = uris.mapIndexed { i, u ->
                    status = "Распознаю фото ${i + 1} из ${uris.size}…"
                    withContext(Dispatchers.Default) { Ocr.photo(app.ctx, u) }
                }
                val (text, _) = TextCleaner.clean(parts.joinToString("\n\n"))
                if (keep) pasteText = pasteText.trimEnd() + "\n\n" + text
                else setSingle("Фото " + java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date()), text)
                if (text.isBlank()) error = "На фото не нашлось текста"
            } catch (e: Throwable) {
                error = "Не удалось распознать: ${e.message}"
            }
            loading = false; status = ""
        }
    }

    // --- cleaning the text ---

    var cleaning by mutableStateOf(false)
    var cleanProgress by mutableFloatStateOf(0f)
    var cleanNote by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
    private var before: Pair<String, List<String>>? = null

    private fun snapshot() { before = pasteText to items.map { it.text }; canUndo = true }

    fun undoClean() {
        val b = before ?: return
        pasteText = b.first
        items.forEachIndexed { i, it -> b.second.getOrNull(i)?.let { t -> it.text = t } }
        before = null; canUndo = false; cleanNote = null
    }

    /** The texts to clean, each with a setter. */
    private fun targets(): List<Pair<String, (String) -> Unit>> =
        if (pasteMode) listOf(pasteText to { v: String -> pasteText = v })
        else items.filter { it.selected }.map { item -> item.text to { v: String -> item.text = v } }

    /** Quick clean on the phone (rules, no AI). */
    fun cleanQuick() {
        if (before == null) snapshot()
        var r = CleanReport(0, 0, 0, 0)
        targets().forEach { (t, set) -> val (c, rep) = TextCleaner.clean(t); r += rep; set(c) }
        cleanNote = r.text()
    }

    /** Clean with AI (light model), piece by piece; the quick clean runs first and stays if AI fails. */
    fun cleanAi() {
        if (!app.useGemini) return app.say("Для чистки с ИИ включите Gemini или другой ИИ: Настройки → Перевод")
        if (before == null) snapshot()
        cleaning = true; cleanProgress = 0f; cleanNote = null
        app.scope.launch {
            val jobs = targets().map { (t, set) -> TextCleaner.clean(t).first to set }
            jobs.forEach { (t, set) -> set(t) }
            try {
                val total = jobs.sumOf { TextCleaner.chunks(it.first).size }.coerceAtLeast(1)
                var done = 0
                for ((t, set) in jobs) {
                    val out = TextCleaner.chunks(t).map { piece ->
                        val r = withContext(Dispatchers.IO) { app.geminiFast().clean(app.prefs.promptClean, app.lang, piece) }
                        done++; cleanProgress = done.toFloat() / total
                        r.ifBlank { piece }
                    }
                    set(out.joinToString("\n\n"))
                }
                cleanNote = "ИИ почистил текст. Пролистайте и проверьте; если что-то пропало — «Вернуть как было»."
            } catch (e: Throwable) {
                cleanNote = "ИИ не справился (${e.message?.take(120)}). Оставлена быстрая очистка."
                app.geminiFailed(e, fast = true) { undoClean(); cleanAi() }
            }
            cleaning = false
        }
    }

    fun loadFile(uri: Uri) {
        startLoad("Читаю файл…")
        pasteMode = false
        app.scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { Sources.name(app.ctx, uri) }
                sourceName = name
                if (withContext(Dispatchers.IO) { Sources.isPdf(app.ctx, uri, name) }) {
                    status = "Читаю PDF…"
                    val (layer, pages) = withContext(Dispatchers.IO) { PdfText.text(app.ctx, uri) }
                    val text = if (!PdfText.isScan(layer, pages)) layer
                    else withContext(Dispatchers.Default) { PdfText.ocr(app.ctx, uri) { i, n -> status = "Распознаю страницу $i из $n…" } }
                    setSingle(name.substringBeforeLast('.'), text)
                } else {
                    val (n, texts) = withContext(Dispatchers.IO) { Sources.read(app.ctx, uri) }
                    if (texts.size == 1) setSingle(texts[0].title.ifBlank { n.substringBeforeLast('.') }, texts[0].text)
                    else {
                        isBook = true
                        items.clear()
                        // Short EPUB parts (cover, title page, imprint) are not preselected.
                        items.addAll(texts.map { ImportItem(it.title.ifBlank { n }, it.text, countWords(it.text) >= 100) })
                    }
                }
                if (app.prefs.autoClean) cleanQuick()
            } catch (e: Throwable) {
                error = "Не удалось прочитать файл: ${e.message}"
            }
            loading = false; status = ""
        }
    }

    private fun selected(): List<Pair<String, String>> =
        if (pasteMode) listOf(pasteTitle.ifBlank { "Текст" } to pasteText).filter { it.second.isNotBlank() }
        else items.filter { it.selected }.mapIndexed { i, item -> (if (isBook) "${i + 1}: ${item.title}" else item.title) to item.text }

    fun selectedCount() = if (pasteMode) (if (pasteText.isBlank()) 0 else 1) else items.count { it.selected }

    fun run() {
        val c0 = course
        val createTitle = newCourseTitle.trim()
        if (newCourse && createTitle.isEmpty()) return app.say("Введите название нового курса")
        if (!newCourse && c0 == null) return app.say("Выберите курс")
        val lang = app.lang
        val max = splitWords.toIntOrNull() ?: 0
        val jobs = selected().flatMap { (title, text) ->
            val parts = Sources.splitLong(text, max)
            if (parts.size == 1) listOf(title to parts[0]) else parts.mapIndexed { i, p -> "$title (${i + 1})" to p }
        }
        if (jobs.isEmpty()) return app.say("Нечего загружать")
        running = true
        finished = false
        error = null
        log.clear()
        fixes.clear()
        app.scope.launch {
            try {
                val c = if (newCourse) {
                    log.add("➕ Курс «$createTitle»")
                    val created = withContext(Dispatchers.IO) { app.api.createCourse(lang, createTitle) }
                    log[log.lastIndex] = "✅ Курс «${created.title}» создан"
                    app.courses = listOf(created) + app.courses.orEmpty()
                    course = created
                    newCourse = false
                    created
                } else c0!!
                // Uploads go one after another (keeps the lesson order in the course); LingQ then
                // processes each lesson on its own, so every lesson is waited for and fixed in parallel.
                val base = log.size
                jobs.forEach { (title, _) -> log.add("· $title · в очереди") }
                fun line(i: Int, msg: String) { log[base + i] = "$msg ${jobs[i].first}" }
                var done = 0
                var failed = 0
                val found = arrayOfNulls<LessonFix>(jobs.size)
                val limit = Semaphore(4)
                coroutineScope {
                    for ((i, job) in jobs.withIndex()) {
                        val (title, text) = job
                        line(i, "⬆")
                        val id = try {
                            withContext(Dispatchers.IO) { app.api.importLesson(lang, c.id, title, text) }
                        } catch (e: Throwable) {
                            line(i, "❌ ${e.message} ·"); failed++; done++; progress = done.toFloat() / jobs.size
                            continue
                        }
                        if (!autoFix) {
                            line(i, "✅"); done++; progress = done.toFloat() / jobs.size
                            continue
                        }
                        line(i, "⏳ LingQ обрабатывает ·")
                        launch {
                            val msg = try {
                                limit.withPermit {
                                    val r = withContext(Dispatchers.IO) { fixImported(lang, id, title) }
                                    found[i] = r
                                    if (r != null) "✅ исправлено ${r.changes.size} ·" else "✅ в порядке ·"
                                }
                            } catch (e: Throwable) {
                                failed++; "❌ ${e.message} ·"
                            }
                            line(i, msg)
                            done++
                            progress = done.toFloat() / jobs.size
                        }
                        delay(300)
                    }
                }
                fixes.addAll(found.filterNotNull())
                finished = true
                app.say(if (failed == 0) "Загружено уроков: ${jobs.size}" else "Загружено: ${jobs.size - failed}, ошибок: $failed")
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            running = false
        }
    }

    /** Waits until LingQ finished processing a fresh lesson, fixes its split and checks the fix stayed. */
    private fun fixImported(lang: String, id: Int, title: String): LessonFix? {
        app.api.waitProcessed(lang, id)
        var result: LessonFix? = null
        for (attempt in 0 until 3) {
            val paras = app.api.editor(lang, id)
            val fixed = JSONArray(paras.toString())
            val changes = Fixer.fix(fixed)
            if (changes.isEmpty()) break          // nothing (left) to fix
            if (result == null) app.backups.save(lang, Lesson(id, title), paras)
            app.api.saveParagraphs(lang, id, fixed)
            if (result == null) result = LessonFix(Lesson(id, title), paras, fixed, changes)
            Thread.sleep(5000)                    // then re-check that LingQ kept it
        }
        return result
    }
}
