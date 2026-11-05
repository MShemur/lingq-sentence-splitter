package com.mshemur.lingqfix

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    private lateinit var state: AppState

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        state = AppState(applicationContext)
        setContent { App(state) }
        if (savedInstanceState == null) handleIntent(intent)
    }

    /** Every time the app comes to the screen: look for a new version (pop-up if there is one). */
    override fun onResume() {
        super.onResume()
        state.checkUpdate(quiet = true)
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
        }
    }
}
