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
import kotlinx.coroutines.withContext
import org.json.JSONArray

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Courses : Screen
    data object Backups : Screen
    class Import(val session: ImportSession) : Screen
    class Check(val session: CheckSession) : Screen

    class Lessons(val lang: String, val course: Course) : Screen {
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
    val scope = MainScope()
    val snackbar = SnackbarHostState()

    val stack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = stack.last()

    var apiKey by mutableStateOf(prefs.apiKey)
    var lang by mutableStateOf(prefs.lang)
    var ghToken by mutableStateOf(prefs.ghToken)
    val api: LingqApi get() = LingqApi(apiKey)

    var courses by mutableStateOf<List<Course>?>(null)
    var coursesError by mutableStateOf<String?>(null)
    private var coursesLang = ""

    var update by mutableStateOf<Updater.Release?>(null)
    var updateProgress by mutableStateOf<Float?>(null)

    fun go(s: Screen) { stack.add(s) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    fun saveSettings(key: String, language: String, token: String) {
        prefs.apiKey = key; prefs.lang = language; prefs.ghToken = token
        val langChanged = prefs.lang != lang
        apiKey = prefs.apiKey; lang = prefs.lang; ghToken = prefs.ghToken
        if (langChanged) courses = null
        say("Сохранено")
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
        s.pasteMode = true
        s.pasteTitle = title.orEmpty()
        s.pasteText = text
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

class ImportItem(title: String, val text: String, selected: Boolean) {
    var title by mutableStateOf(title)
    var selected by mutableStateOf(selected)
    val words = countWords(text)
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

    fun loadFile(uri: Uri) {
        loading = true
        pasteMode = false
        error = null
        app.scope.launch {
            try {
                val (name, texts) = withContext(Dispatchers.IO) { Sources.read(app.ctx, uri) }
                sourceName = name
                isBook = texts.size > 1
                items.clear()
                // Short EPUB parts (cover, title page, imprint) are not preselected.
                items.addAll(texts.map { ImportItem(it.title.ifBlank { name }, it.text, !isBook || countWords(it.text) >= 100) })
            } catch (e: Throwable) {
                error = "Не удалось прочитать файл: ${e.message}"
            }
            loading = false
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
                for ((i, job) in jobs.withIndex()) {
                    val (title, text) = job
                    log.add("⬆ $title")
                    val fixedCount = withContext(Dispatchers.IO) {
                        val id = app.api.importLesson(lang, c.id, title, text)
                        if (!autoFix) return@withContext -1
                        var paras = JSONArray()
                        for (attempt in 0 until 10) {           // LingQ may still be processing the text
                            paras = app.api.editor(lang, id)
                            if (paras.length() > 0) break
                            Thread.sleep(3000)
                        }
                        val fixed = JSONArray(paras.toString())
                        val changes = Fixer.fix(fixed)
                        if (changes.isNotEmpty()) {
                            app.backups.save(lang, Lesson(id, title), paras)
                            app.api.saveParagraphs(lang, id, fixed)
                        }
                        changes.size
                    }
                    log[log.lastIndex] = "✅ $title" + if (fixedCount > 0) " · исправлено склеек: $fixedCount" else ""
                    progress = (i + 1f) / jobs.size
                    delay(500)
                }
                finished = true
                app.say("Загружено уроков: ${jobs.size}")
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            }
            running = false
        }
    }
}
