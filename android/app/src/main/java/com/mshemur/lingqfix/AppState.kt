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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    data object SettingsSpeech : Screen
    class Podcasts(val session: PodcastSession) : Screen
    class PodcastFeed(val session: PodcastSession) : Screen
    data object Stats : Screen
    data object SettingsSync : Screen
    data object Log : Screen
    /** Tab "Плеер": lesson audio with its text. */
    data object Player : Screen
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
        AppLog.error("ai", "Ошибка ИИ (${if (fast) "лёгкая" else "основная"} модель)", e)
        if (!useGemini || !isGeminiLimit(e) || modelSwitch != null) return
        modelSwitch = when {
            otherAi && fast -> ModelSwitch(prefs.aiFastModel.ifBlank { prefs.aiModel }, e.message.orEmpty(), { m -> applyPrefs { aiFastModel = m } }, retry)
            otherAi -> ModelSwitch(prefs.aiModel, e.message.orEmpty(), { m -> applyPrefs { aiModel = m } }, retry)
            fast -> ModelSwitch(prefs.geminiFastModel.ifBlank { prefs.geminiModel }, e.message.orEmpty(), { m -> applyPrefs { geminiFastModel = m } }, retry)
            else -> ModelSwitch(prefs.geminiModel, e.message.orEmpty(), { m -> applyPrefs { geminiModel = m } }, retry)
        }
    }
    private var dialogShownFor = 0

    fun go(s: Screen) { stack.add(s); AppLog.add("nav", "→ ${screenName(s)}") }

    private fun screenName(s: Screen): String = when (s) {
        is Screen.Reader -> "Урок ${s.session.lang}/${s.session.lesson.id} «${s.session.lesson.title.take(60)}»"
        else -> s.javaClass.simpleName
    }

    /** Opens the lesson of a bookmark at that sentence (reuses the reader if this lesson is already open). */
    fun openBookmark(b: Bookmark) {
        val open = stack.indexOfLast { it is Screen.Reader && it.session.lesson.id == b.lessonId }
        if (open >= 0) {
            while (stack.size > open + 1) stack.removeAt(stack.lastIndex)
            (stack.last() as Screen.Reader).session.jumpTo(b.sent)
        } else go(Screen.Reader(ReaderSession(this, b.lang, Lesson(b.lessonId, b.lessonTitle), startSent = b.sent)))
    }
    fun toRoot() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun back(): Boolean = if (stack.size > 1) {
        stack.removeAt(stack.lastIndex); AppLog.add("nav", "← ${screenName(stack.last())}"); true
    } else false
    fun say(msg: String) { AppLog.add("msg", msg); scope.launch { snackbar.showSnackbar(msg) } }

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

    /** Words in each course (sum over its lessons); kept on the phone, refreshed at most once a day. */
    val courseWords = androidx.compose.runtime.mutableStateMapOf<Int, Int>().apply {
        runCatching {
            val o = org.json.JSONObject(ctx.getSharedPreferences("progress", Context.MODE_PRIVATE).getString("courseWords", "{}")!!)
            o.keys().forEach { k -> put(k.toInt(), o.getInt(k)) }
        }
    }
    private val wordsFetched = HashSet<Int>()
    private val wordsLimit = kotlinx.coroutines.sync.Semaphore(3)
    /** When each course's word count was last fetched (ms). */
    private val wordsAt = HashMap<Int, Long>().apply {
        runCatching {
            val o = org.json.JSONObject(ctx.getSharedPreferences("progress", Context.MODE_PRIVATE).getString("courseWordsAt", "{}")!!)
            o.keys().forEach { k -> put(k.toInt(), o.getLong(k)) }
        }
    }

    fun setCourseWords(id: Int, words: Int) {
        wordsFetched += id
        courseWords[id] = words
        wordsAt[id] = System.currentTimeMillis()
        val o = org.json.JSONObject()
        courseWords.forEach { (k, v) -> o.put(k.toString(), v) }
        val at = org.json.JSONObject()
        wordsAt.forEach { (k, v) -> at.put(k.toString(), v) }
        ctx.getSharedPreferences("progress", Context.MODE_PRIVATE).edit()
            .putString("courseWords", o.toString()).putString("courseWordsAt", at.toString()).apply()
    }

    fun ensureCourseWords(lang: String, id: Int) {
        val fresh = courseWords.containsKey(id) && System.currentTimeMillis() - (wordsAt[id] ?: 0L) < 24 * 3600_000L
        if (fresh || !wordsFetched.add(id)) return
        scope.launch {
            runCatching {
                wordsLimit.acquire()
                try { withContext(Dispatchers.IO) { api.lessons(lang, id).second.sumOf { it.words } } } finally { wordsLimit.release() }
            }.onSuccess { setCourseWords(id, it) }.onFailure { wordsFetched.remove(id) }
        }
    }

    /** The lesson in the player tab (its own session: pins, translations), null = nothing chosen. */
    var player by mutableStateOf<ReaderSession?>(null)

    /** Bottom tabs of the main screen: Библиотека / Плеер. */
    fun switchTab(s: Screen) {
        if (stack.size == 1 && stack[0] == s) return
        stack.clear(); stack.add(s)
        AppLog.add("nav", "Вкладка ${s.javaClass.simpleName}")
    }

    /** "Слушать все": lessons are being prepared (their audio addresses fetched). */
    var queueLoading by mutableStateOf(false)
    private val queueLessons = HashMap<String, Pair<String, Lesson>>()

    /** Plays [lessons] one after another (the player shows the text of the one sounding). */
    fun playAll(lang: String, lessons: List<Lesson>) {
        if (lessons.isEmpty() || queueLoading) return
        queueLoading = true
        scope.launch {
            try {
                val limit = Semaphore(4)
                val ready = withContext(Dispatchers.IO) {
                    coroutineScope {
                        lessons.map { l ->
                            async {
                                if (l.audio.isNotBlank()) l
                                else limit.withPermit { runCatching { l.copy(audio = api.audioUrl(lang, l.id)) }.getOrDefault(l) }
                            }
                        }.awaitAll()
                    }
                }.filter { it.audio.isNotBlank() }
                if (ready.isEmpty()) { say("У этих уроков нет аудио"); return@launch }
                queueLessons.clear()
                ready.forEach { queueLessons["${lang}_${it.id}"] = lang to it }
                val first = ready.first()
                val cur = player
                if (cur == null || cur.lesson.id != first.id || cur.lang != lang) {
                    player = ReaderSession(this@AppState, lang, first).apply { sentenceAtBottom = true }
                }
                AudioEngine.onTrack = { k -> onTrack(k) }
                AudioEngine.loadQueue(
                    ctx, ready.map { AudioEngine.Track("${lang}_${it.id}", it.audio, it.title, it.imageUrl) },
                    AudioEngine.savedPos(ctx, "${lang}_${first.id}"),
                )
                AudioEngine.play()
                switchTab(Screen.Player)
            } catch (e: Throwable) {
                AppLog.error("audio", "Слушать все", e)
                say(e.message ?: "Не удалось собрать очередь")
            } finally {
                queueLoading = false
            }
        }
    }

    /** The queue went on to the next lesson: the player shows its text. */
    private fun onTrack(k: String) {
        val (lang, l) = queueLessons[k] ?: return
        // the text is loaded at once: the next lesson may start with the screen off
        if (player?.audioKey != k) player = ReaderSession(this, lang, l).apply { sentenceAtBottom = true; load() }
    }

    /** Mini player: the player tab on the lesson that is sounding (also after the app screen was recreated). */
    fun openSounding() {
        val k = AudioEngine.key
        if (k != null && player?.audioKey != k) {
            val q = queueLessons[k]
            val id = k.substringAfter('_').toIntOrNull()
            val s = when {
                q != null -> ReaderSession(this, q.first, q.second)
                id != null -> ReaderSession(this, k.substringBefore('_'), Lesson(id, AudioEngine.title, AudioEngine.image))
                else -> null
            }
            if (s != null) player = s.apply { sentenceAtBottom = true }
        }
        switchTab(Screen.Player)
    }

    /** Audio bookmark: the player tab, from that place. */
    fun openAudioBookmark(b: Bookmark) = listenAt(b.lang, Lesson(b.lessonId, b.lessonTitle), b.ms)

    /** The player tab, lesson [l] from [ms]. */
    fun listenAt(lang: String, l: Lesson, ms: Long) {
        val cur = player
        if (cur != null && cur.lesson.id == l.id && cur.lang == lang && cur.audioUrl != null) {
            cur.startAt = ms
            if (AudioEngine.key == cur.audioKey) { AudioEngine.seekTo(ms); cur.startAt = -1; AudioEngine.play() } else cur.togglePlay()
        } else {
            player = ReaderSession(this, lang, l).apply { sentenceAtBottom = true; autoPlay = true; startAt = ms }
        }
        switchTab(Screen.Player)
    }

    /** Listen to a lesson in the player tab (starts playing when its audio is known). */
    fun playLesson(lang: String, l: Lesson) {
        val cur = player
        if (cur == null || cur.lesson.id != l.id || cur.lang != lang) {
            player = ReaderSession(this, lang, l).apply { sentenceAtBottom = true; autoPlay = true }
        } else if (AudioEngine.key != cur.audioKey || !AudioEngine.playing) cur.togglePlay()
        switchTab(Screen.Player)
    }

    /** Lessons opened lately, for the "continue" row of the library. */
    var recent by mutableStateOf<List<Lesson>?>(null)

    private val recentPrefs = ctx.getSharedPreferences("recent", android.content.Context.MODE_PRIVATE)
    private var recentApi: List<Lesson> = emptyList()

    /** Lessons opened in this app (LingQ does not always notice an opening through the API). */
    private fun localRecent(l: String): List<Lesson> = runCatching {
        val a = org.json.JSONArray(recentPrefs.getString(l, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Lesson(o.getInt("id"), o.optString("title"), o.optString("imageUrl"), o.optString("level"),
                o.optInt("words"), 0, o.optString("course"), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    private fun rememberOpened(l: String, lesson: Lesson) {
        val old = localRecent(l).firstOrNull { it.id == lesson.id }
        val now = Lesson(
            lesson.id, lesson.title.ifBlank { old?.title.orEmpty() }, lesson.imageUrl.ifBlank { old?.imageUrl.orEmpty() },
            lesson.level.ifBlank { old?.level.orEmpty() }, if (lesson.words > 0) lesson.words else old?.words ?: 0, 0,
            lesson.courseTitle.ifBlank { old?.courseTitle.orEmpty() }, System.currentTimeMillis(),
        )
        val list = (listOf(now) + localRecent(l).filter { it.id != lesson.id }).take(20)
        val a = org.json.JSONArray()
        list.forEach { x ->
            a.put(org.json.JSONObject().put("id", x.id).put("title", x.title).put("imageUrl", x.imageUrl).put("level", x.level)
                .put("words", x.words).put("course", x.courseTitle).put("t", x.opened))
        }
        recentPrefs.edit().putString(l, a.toString()).apply()
        if (lang == l) recent = mergeRecent(l)
    }

    /** LingQ's list + lessons opened here; the one opened last comes first. */
    private fun mergeRecent(l: String): List<Lesson> {
        val local = localRecent(l).associateBy { it.id }
        val ids = LinkedHashSet<Int>().apply { recentApi.forEach { add(it.id) }; local.keys.forEach { add(it) } }
        return ids.map { id ->
            val a = recentApi.firstOrNull { it.id == id }
            val b = local[id]
            val base = a ?: b!!
            val t = maxOf(a?.opened ?: 0L, b?.opened ?: 0L, progress.time(l, id))
            base.copy(
                title = base.title.ifBlank { b?.title.orEmpty() }, imageUrl = base.imageUrl.ifBlank { b?.imageUrl.orEmpty() },
                courseTitle = base.courseTitle.ifBlank { b?.courseTitle.orEmpty() }, opened = t,
            )
        }.sortedByDescending { it.opened }.take(10)
    }

    fun loadRecent() {
        val l = lang
        recentApi = emptyList()
        if (localRecent(l).isNotEmpty()) recent = mergeRecent(l)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.recentLessons(l) } }.onSuccess {
                if (lang == l) { recentApi = it; recent = mergeRecent(l) }
            }
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
        rememberOpened(lang, l)
        ContinueWidget.save(ctx, lang, l)
        if (l.imageUrl.isBlank()) scope.launch {
            runCatching { withContext(Dispatchers.IO) { api.lesson(lang, l.id) } }.onSuccess { full ->
                if (full.imageUrl.isNotBlank()) ContinueWidget.save(ctx, lang, full.copy(title = l.title.ifBlank { full.title }))
                rememberOpened(lang, full.copy(title = l.title.ifBlank { full.title }))
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
                if (!Updater.install(ctx, f)) say("Разрешите установку для SatzLoop и нажмите «Обновить» ещё раз")
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
    /** The AI sets the level of each uploaded lesson (remembered). */
    var autoLevel by mutableStateOf(app.prefs.autoLevel)
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

    /** PDF waiting for the choice of pages (null = none), its file name and the chosen pages (0-based). */
    var pdf by mutableStateOf<PdfDoc?>(null)
    var pdfName by mutableStateOf("")
    val pdfPicked = mutableStateListOf<Int>()

    fun cancelPdf() {
        val d = pdf ?: return
        pdf = null
        app.scope.launch { d.close() }
    }

    /** The chosen pages: text layer where there is one, OCR for scanned pages. */
    fun importPdfPages() {
        val d = pdf ?: return
        val pages = pdfPicked.sorted()
        if (pages.isEmpty()) return
        pdf = null
        startLoad("Читаю PDF…")
        app.scope.launch {
            d.close()
            try {
                readPdf(d.uri, pdfName, pages, d.pages)
                if (app.prefs.autoClean) cleanQuick()
            } catch (e: Throwable) {
                AppLog.error("import", "PDF", e)
                error = "Не удалось прочитать PDF: ${e.message}"
            }
            loading = false; status = ""
        }
    }

    private suspend fun readPdf(uri: android.net.Uri, name: String, pages: List<Int>, total: Int) {
        var scans = 0
        val text = PdfText.pages(app.ctx, uri, pages) { n, all, ocr ->
            if (ocr) scans++
            status = if (ocr) "Распознаю страницу $n из $all (скан)…" else "Читаю страницу $n из $all…"
        }
        val base = name.substringBeforeLast('.')
        setSingle(if (pages.size == total) base else "$base, стр. ${pagesLabel(pages)}", text)
        AppLog.add("import", "PDF: ${pages.size} из $total стр., распознано сканов: $scans")
        if (text.isBlank()) error = "На выбранных страницах не нашлось текста"
    }

    private fun setSingle(title: String, text: String) {
        pasteMode = true; isBook = false; items.clear()
        pasteTitle = title; pasteText = text
    }

    // --- audio: a part is cut, recognised and uploaded with the lesson ---

    /** Audio file waiting for the choice of its part. */
    var audioPick by mutableStateOf<AudioPick?>(null)
    /** Audio that goes into the lesson (the cut part or the whole file), its file name, and the recognised words. */
    var audioUri by mutableStateOf<Uri?>(null)
    var audioName by mutableStateOf("")
    private var audioWords: List<TWord> = emptyList()

    fun dropAudio() { audioUri = null; audioName = ""; audioWords = emptyList() }

    /** Cover picture (bytes, MIME type) of the book / podcast; put on the course when [coverToCourse]. */
    var cover by mutableStateOf<Pair<ByteArray, String>?>(null)
    var coverToCourse by mutableStateOf(true)

    fun setCover(c: Pair<ByteArray, String>?, title: String) {
        cover = c
        if (c != null && newCourseTitle.isBlank() && title.isNotBlank()) newCourseTitle = title
    }

    /** [name] = file name when the uri has none (podcast episode); [transcript] = the podcast's own, if any. */
    fun pickAudio(uri: Uri, name: String? = null, transcript: List<TSeg>? = null) {
        val prov = sttProvider(app.prefs.sttProvider)
        if (transcript == null && sttKeyFor(app.prefs, prov).isBlank()) {
            app.say("Задайте ключ распознавания (${prov.name})")
            app.go(Screen.SettingsSpeech)
            return
        }
        startLoad("Открываю аудио…")
        app.scope.launch {
            val n = name ?: withContext(Dispatchers.IO) { Sources.name(app.ctx, uri) }
            val dur = withContext(Dispatchers.IO) { AudioCut.duration(app.ctx, uri) }
            loading = false; status = ""
            audioPick = AudioPick(uri, n, dur, transcript)
        }
    }

    /** Recognises [fromMs, toMs) of the picked audio (toMs null = to the end). */
    fun transcribePart(fromMs: Long, toMs: Long?, useTranscript: Boolean = false) {
        val pk = audioPick ?: return
        val p = app.prefs
        val prov = sttProvider(p.sttProvider)
        val key = sttKeyFor(p, prov)
        val ready = if (useTranscript) pk.transcript else null
        if (ready == null && key.isBlank()) {
            app.say("Задайте ключ распознавания (${prov.name})")
            app.go(Screen.SettingsSpeech)
            return
        }
        audioPick = null
        val keepCover = cover
        startLoad("Вырезаю кусок…")
        pasteMode = false; items.clear()
        app.scope.launch {
            try {
                val base = pk.name.substringBeforeLast('.')
                val end = toMs ?: pk.durationMs.takeIf { it > 0 }
                val whole = fromMs <= 0 && (end == null || (pk.durationMs > 0 && end >= pk.durationMs))
                val (uri, name) = if (whole) pk.uri to pk.name else {
                    val dir = java.io.File(app.ctx.cacheDir, "import").apply { deleteRecursively() }
                    val f = withContext(Dispatchers.Default) { AudioCut.cut(app.ctx, pk.uri, fromMs, end ?: (1L shl 40), dir, "part") }
                    Uri.fromFile(f) to f.name
                }
                val title = if (whole) base else "$base, ${clock(fromMs)}–${end?.let { clock(it) } ?: "конец"}"
                cover = keepCover
                val t = if (ready != null) Stt.fromSegments(ready, fromMs, end)
                else Stt.transcribe(app.ctx, uri, name, app.lang, prov, key, p.sttModel(prov.id)) { status = it }
                setSingle(title, t.text)
                audioUri = uri; audioName = if (whole) pk.name else "$title.${name.substringAfterLast('.')}"
                audioWords = t.words
                if (t.text.isBlank()) error = "В аудио не нашлось речи"
                else if (p.autoClean) cleanQuick()
            } catch (e: Throwable) {
                AppLog.error("stt", "Распознавание", e)
                error = "Не удалось распознать: ${e.message}"
            }
            loading = false; status = ""
        }
    }

    /** Sets the time of every sentence of a fresh lesson from the recognised words; returns a note for the log. */
    private fun setTimes(lang: String, id: Int, words: List<TWord>): String {
        val state = app.api.audioState(lang, id)
        if (state == "none") return "LingQ не принял аудио"
        val paras = app.api.editor(lang, id)
        val sents = mutableListOf<org.json.JSONObject>()
        for (i in 0 until paras.length()) {
            val ss = paras.getJSONObject(i).optJSONArray("sentences") ?: continue
            for (j in 0 until ss.length()) sents += ss.getJSONObject(j)
        }
        if (sents.isEmpty() || words.isEmpty()) return "аудио есть"
        val times = Stt.align(sents.map { it.optString("text") }, words)
        sents.forEachIndexed { i, s ->
            val (a, b) = times[i]
            s.put("timestamp", JSONArray().put(Math.round(a * 100) / 100.0).put(Math.round(b * 100) / 100.0))
        }
        app.api.saveParagraphs(lang, id, paras)
        AppLog.add("import", "Урок $id: время для ${sents.size} предложений (аудио: $state)")
        return "аудио и время предложений"
    }

    private fun startLoad(msg: String) {
        dropAudio(); cover = null
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
                    status = "Открываю PDF…"
                    cancelPdf()
                    val d = withContext(Dispatchers.IO) { PdfDoc(app.ctx, uri) }
                    if (d.pages <= 1) {
                        d.close()
                        readPdf(uri, name, listOf(0), 1)
                    } else {
                        // more than one page: the user picks which ones (sheet with previews)
                        pdfName = name.substringBeforeLast('.')
                        pdfPicked.clear()
                        pdf = d
                        loading = false; status = ""
                        return@launch
                    }
                } else {
                    val (n, texts, cv) = withContext(Dispatchers.IO) { Sources.read(app.ctx, uri) }
                    if (cv != null) setCover(cv, n)
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
        val audio = if (pasteMode) audioUri else null
        val audioFile = audioName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        val words = audioWords
        val max = if (audio != null) 0 else splitWords.toIntOrNull() ?: 0   // a lesson with audio stays whole
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
                    val created = withContext(Dispatchers.IO) { app.api.createCourse(lang, createTitle, cover?.takeIf { coverToCourse }) }
                    log[log.lastIndex] = "✅ Курс «${created.title}» создан"
                    app.courses = listOf(created) + app.courses.orEmpty()
                    course = created
                    newCourse = false
                    created
                } else c0!!.also { ex ->
                    val cv = cover
                    if (cv != null && coverToCourse) try {
                        withContext(Dispatchers.IO) { app.api.setCourseImage(lang, ex.id, cv) }
                        log.add("🖼 Обложка курса «${ex.title}» обновлена")
                    } catch (e: Throwable) {
                        AppLog.error("import", "Обложка курса", e)
                        log.add("⚠ Обложку не удалось поставить: ${e.message}")
                    }
                }
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
                            withContext(Dispatchers.IO) {
                                if (audio != null) app.api.importLessonAudio(lang, c.id, title, text, UriBody(app.ctx, audio), audioFile)
                                else app.api.importLesson(lang, c.id, title, text)
                            }
                        } catch (e: Throwable) {
                            line(i, "❌ ${e.message} ·"); failed++; done++; progress = done.toFloat() / jobs.size
                            continue
                        }
                        val level = autoLevel && app.useGemini
                        if (!autoFix && !level && audio == null) {
                            line(i, "✅"); done++; progress = done.toFloat() / jobs.size
                            continue
                        }
                        line(i, "⏳ LingQ обрабатывает ·")
                        launch {
                            val msg = try {
                                limit.withPermit {
                                    val parts = mutableListOf<String>()
                                    if (autoFix) {
                                        val r = withContext(Dispatchers.IO) { fixImported(lang, id, title) }
                                        found[i] = r
                                        parts += if (r != null) "исправлено ${r.changes.size}" else "разбивка в порядке"
                                    }
                                    if (audio != null) parts += try {
                                        withContext(Dispatchers.IO) {
                                            if (!autoFix) app.api.waitProcessed(lang, id)
                                            setTimes(lang, id, words)
                                        }
                                    } catch (e: Throwable) {
                                        AppLog.error("import", "Время предложений урока $id", e)
                                        "время предложений не записано"
                                    }
                                    if (level) parts += try {
                                        withContext(Dispatchers.IO) { aiLevel(lang, id, text) }
                                    } catch (e: Throwable) {
                                        AppLog.error("import", "Уровень урока $id", e)
                                        app.geminiFailed(e, fast = true) {}
                                        "уровень не определён"
                                    }
                                    "✅ ${parts.joinToString(", ")} ·"
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

    /** The AI estimates the level of a fresh lesson and saves it on LingQ; returns e.g. "уровень B1". */
    private fun aiLevel(lang: String, id: Int, text: String): String {
        val (lvl, _) = app.geminiFast().level(app.prefs.promptLevel, lang, text)
        app.api.waitProcessed(lang, id)
        app.api.setLevel(lang, id, lvl)
        AppLog.add("import", "Урок $id: уровень ${levelName(lvl)}")
        return "уровень ${CEFR[lvl - 1]}"
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
