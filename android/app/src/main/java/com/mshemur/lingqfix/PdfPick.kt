package com.mshemur.lingqfix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An open PDF while its pages are being chosen: page count and small previews. */
class PdfDoc(ctx: Context, val uri: Uri) {
    private val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw ApiException("файл недоступен")
    private val r = PdfRenderer(pfd)
    private val lock = Mutex()          // PdfRenderer opens one page at a time
    private var closed = false
    val pages = r.pageCount

    suspend fun thumb(i: Int, width: Int): Bitmap? = lock.withLock {
        if (closed) null else withContext(Dispatchers.IO) { renderPage(r, i, width, Bitmap.Config.RGB_565) }
    }

    suspend fun close() = lock.withLock {
        if (!closed) { closed = true; runCatching { r.close() }; runCatching { pfd.close() } }
    }
}

private fun renderPage(r: PdfRenderer, i: Int, width: Int, cfg: Bitmap.Config): Bitmap {
    val page = r.openPage(i)
    try {
        val scale = width.toFloat() / page.width
        val bmp = Bitmap.createBitmap(width, (page.height * scale).toInt().coerceAtLeast(1), cfg)
        bmp.eraseColor(AColor.WHITE)
        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bmp
    } finally {
        page.close()
    }
}

/** Text from PDF files: per chosen page the text layer, or OCR when the page has none (a scan). */
object PdfText {
    /** A page with fewer letters than this has no usable text layer. */
    private const val MIN_LETTERS = 80

    /**
     * Text of [pages] (0-based, in order). Pages with a text layer are taken as they are; the others are
     * rendered and read by OCR on the phone. [onPage] reports progress (n, total, ocr).
     */
    suspend fun pages(ctx: Context, uri: Uri, pages: List<Int>, onPage: (Int, Int, Boolean) -> Unit): String {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val layers = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri).use { input ->
                PDDocument.load(input ?: throw ApiException("файл недоступен")).use { doc ->
                    pages.map { i ->
                        runCatching {
                            PDFTextStripper().apply { sortByPosition = true; startPage = i + 1; endPage = i + 1 }.getText(doc)
                        }.getOrDefault("")
                    }
                }
            }
        }
        val out = StringBuilder()
        pages.forEachIndexed { n, i ->
            val layer = layers[n]
            val scan = layer.count { it.isLetter() } < MIN_LETTERS
            onPage(n + 1, pages.size, scan)
            val t = if (!scan) layer.trim() else ocrPage(ctx, uri, i)
            if (t.isNotBlank()) out.append(t.trim()).append("\n\n")
        }
        return out.toString()
    }

    private suspend fun ocrPage(ctx: Context, uri: Uri, i: Int): String {
        val bmp = withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw ApiException("файл недоступен")
            pfd.use { PdfRenderer(it).use { r -> renderPage(r, i, 1600, Bitmap.Config.ARGB_8888) } }
        }
        return try { Ocr.read(InputImage.fromBitmap(bmp, 0)) } finally { bmp.recycle() }
    }
}

/** "3-5, 8" (1-based) -> page indices (0-based); null if it cannot be read. */
fun parsePages(text: String, pages: Int): Set<Int>? {
    val out = sortedSetOf<Int>()
    for (part in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
        val ab = part.split('-', '–', '—').map { it.trim() }
        val a = ab[0].toIntOrNull() ?: return null
        val b = if (ab.size > 1) ab[1].toIntOrNull() ?: return null else a
        if (ab.size > 2 || a < 1 || b < a || b > pages) return null
        for (p in a..b) out += p - 1
    }
    return out
}

/** Page indices (0-based) -> "3–5, 8". */
fun pagesLabel(pages: Collection<Int>): String {
    val s = pages.sorted()
    val parts = mutableListOf<String>()
    var i = 0
    while (i < s.size) {
        var j = i
        while (j + 1 < s.size && s[j + 1] == s[j] + 1) j++
        parts += if (j > i) "${s[i] + 1}–${s[j] + 1}" else "${s[i] + 1}"
        i = j + 1
    }
    return parts.joinToString(", ")
}

/** Which pages of the PDF to import: previews to tap, or a range typed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPagePicker(s: ImportSession, d: PdfDoc) {
    var range by remember(d) { mutableStateOf("") }
    var bad by remember(d) { mutableStateOf(false) }
    fun syncText() { range = pagesLabel(s.pdfPicked); bad = false }
    ModalBottomSheet(onDismissRequest = { s.cancelPdf() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Какие страницы взять?", style = MaterialTheme.typography.titleLarge)
            Text(
                "${s.pdfName} · ${d.pages} стр. Нажмите на страницы или впишите номера. Страницы без текста (сканы) распознаются на телефоне.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                range,
                { v ->
                    range = v
                    val p = parsePages(v, d.pages)
                    bad = v.isNotBlank() && p == null
                    if (p != null) { s.pdfPicked.clear(); s.pdfPicked.addAll(p) }
                },
                Modifier.fillMaxWidth(), singleLine = true, isError = bad, shape = RoundedCornerShape(12.dp),
                label = { Text("Страницы, например 3-5, 8") },
                supportingText = { if (bad) Text("Номера от 1 до ${d.pages}, через запятую или дефис") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { s.pdfPicked.clear(); s.pdfPicked.addAll(0 until d.pages); syncText() }) { Text("Все") }
                TextButton(onClick = { s.pdfPicked.clear(); syncText() }, enabled = s.pdfPicked.isNotEmpty()) { Text("Сбросить") }
            }
            LazyVerticalGrid(
                GridCells.Adaptive(96.dp), Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(d.pages, key = { it }) { i ->
                    val on = i in s.pdfPicked
                    PdfThumb(d, i, on) {
                        if (on) s.pdfPicked.remove(i) else s.pdfPicked.add(i)
                        syncText()
                    }
                }
            }
            Button(
                onClick = { s.importPdfPages() }, enabled = s.pdfPicked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                Text(if (s.pdfPicked.isEmpty()) "Выберите страницы" else "Взять ${s.pdfPicked.size} стр.")
            }
        }
    }
}

@Composable
private fun PdfThumb(d: PdfDoc, i: Int, picked: Boolean, onClick: () -> Unit) {
    val bmp by produceState<Bitmap?>(null, d, i) { value = runCatching { d.thumb(i, 240) }.getOrNull() }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.71f)
                .border(if (picked) 3.dp else 1.dp, if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
                .clip(shape).background(Color.White),
        ) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            if (picked) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium) }
        }
        Text("${i + 1}", Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}
