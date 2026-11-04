package com.mshemur.lingqfix

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** Checks GitHub releases of the (private) repo and installs a newer APK. */
object Updater {
    private const val REPO = "MShemur/lingq-sentence-splitter"

    /** [code] = versionCode of the build (tag v1.<code>). [assetUrl] = API url of the .apk asset. */
    class Release(val versionName: String, val code: Int, val assetUrl: String, val notes: String)

    private fun gh(token: String, url: String, accept: String = "application/vnd.github+json") =
        Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "lingq-fix-app")
            .build()

    fun latest(token: String): Release? {
        LingqApi.http.newCall(gh(token, "https://api.github.com/repos/$REPO/releases/latest")).execute().use { r ->
            if (r.code == 404) return null
            if (r.code == 401) throw ApiException("GitHub не принял токен (HTTP 401)")
            if (!r.isSuccessful) throw ApiException("GitHub: HTTP ${r.code}")
            val o = JSONObject(r.body?.string().orEmpty())
            val name = o.getString("tag_name").removePrefix("v")
            val code = name.substringAfterLast('.').toIntOrNull() ?: return null
            val assets = o.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) return Release(name, code, a.getString("url"), o.optString("body"))
            }
            return null
        }
    }

    fun download(ctx: Context, token: String, rel: Release, onProgress: (Float) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val f = File(dir, "lingq-fix-${rel.versionName}.apk")
        // GitHub answers with a redirect to its file storage; OkHttp drops the token on the other host.
        LingqApi.http.newCall(gh(token, rel.assetUrl, "application/octet-stream")).execute().use { r ->
            if (!r.isSuccessful) throw ApiException("GitHub: HTTP ${r.code}")
            val body = r.body ?: throw ApiException("GitHub: пустой ответ")
            val total = body.contentLength()
            body.byteStream().use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        return f
    }

    /** Opens the system installer. Returns false if the user first has to allow installs from this app. */
    fun install(ctx: Context, f: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
