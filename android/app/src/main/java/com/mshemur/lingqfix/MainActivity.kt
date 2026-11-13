package com.mshemur.lingqfix

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    companion object {
        /** One per process: a re-created activity keeps the state (and the running background jobs). */
        internal var shared: AppState? = null
    }
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        AppLog.init(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = shared ?: AppState(applicationContext).also { shared = it }
        // covers: same HTTP client, with the user agent LingQ's CDN accepts
        coil.Coil.setImageLoader(Covers.loader(applicationContext))
        setContent { App(state) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    /** Every time the app comes to the screen: look for a new version (pop-up if there is one). */
    override fun onResume() {
        super.onResume()
        state.resumed = true
        AppLog.add("app", "На экране")
        state.sync.auto()
        state.checkUpdate(quiet = true)
        // while the app stays open: look again every 5 minutes
        updateJob?.cancel()
        updateJob = state.scope.launch {
            while (true) { kotlinx.coroutines.delay(5 * 60_000L); state.checkUpdate(quiet = true) }
        }
    }

    private var updateJob: kotlinx.coroutines.Job? = null

    override fun onPause() {
        state.resumed = false
        updateJob?.cancel(); updateJob = null
        AppLog.add("app", "Свёрнуто")
        ContinueWidget.refresh(applicationContext)     // reading progress on the home screen widget
        state.sync.auto()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Share" from LingQ (link), from a file manager (EPUB/TXT) or any text. */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        when (i.action) {
            Intent.ACTION_SEND -> {
                val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                val text = i.getStringExtra(Intent.EXTRA_TEXT)
                when {
                    stream != null -> state.openImportFile(stream)
                    text != null && text.contains("lingq.com") && parseTarget(text) != null -> state.openLink(text)
                    text != null -> state.openImportText(text, i.getStringExtra(Intent.EXTRA_SUBJECT))
                }
            }
            Intent.ACTION_VIEW -> i.data?.let { state.openImportFile(it) }
            PlaybackService.ACTION_PLAYER -> state.switchTab(Screen.Player)
            ContinueWidget.ACTION_OPEN -> {
                val id = i.getIntExtra("id", -1)
                if (id >= 0) state.openLesson(i.getStringExtra("lang") ?: state.lang, id, i.getStringExtra("title").orEmpty())
            }
        }
    }
}
