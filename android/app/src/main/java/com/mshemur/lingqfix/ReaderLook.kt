@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.mshemur.lingqfix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Page colours of the reader, like in e-book apps. */
class ReaderTheme(val key: String, val name: String, val bg: Color, val fg: Color, val dark: Boolean)

val READER_THEMES = listOf(
    ReaderTheme("white", "Белая", Color(0xFFFFFFFF), Color(0xFF1C1B1F), false),
    ReaderTheme("cream", "Кремовая", Color(0xFFFBF7EE), Color(0xFF2D2A26), false),
    ReaderTheme("sepia", "Сепия", Color(0xFFF4ECD8), Color(0xFF5B4636), false),
    ReaderTheme("mint", "Мятная", Color(0xFFCCE8CF), Color(0xFF233626), false),
    ReaderTheme("gray", "Серая", Color(0xFFE6E6E6), Color(0xFF222222), false),
    ReaderTheme("dusk", "Сумерки", Color(0xFF3A3A3C), Color(0xFFE3E3E3), true),
    ReaderTheme("dark", "Тёмная", Color(0xFF1E1E1E), Color(0xFFDADADA), true),
    ReaderTheme("black", "Чёрная", Color(0xFF000000), Color(0xFFC8C8C8), true),
)

/** Colour of chosen words and of the translations above them. */
class Highlight(val key: String, val name: String, val bgLight: Color, val textLight: Color, val bgDark: Color, val textDark: Color)

val HIGHLIGHTS = listOf(
    Highlight("green", "Зелёный", Color(0x3343A047), Color(0xFF2E7D32), Color(0x5543A047), Color(0xFF81C784)),
    Highlight("yellow", "Жёлтый (LingQ)", Color(0x99FFD54F), Color(0xFF8D6200), Color(0x66FFC107), Color(0xFFFFD54F)),
    Highlight("blue", "Голубой", Color(0x4D42A5F5), Color(0xFF1565C0), Color(0x6642A5F5), Color(0xFF90CAF9)),
    Highlight("pink", "Розовый", Color(0x4DF06292), Color(0xFFC2185B), Color(0x66F06292), Color(0xFFF48FB1)),
    Highlight("purple", "Сиреневый", Color(0x4DAB47BC), Color(0xFF7B1FA2), Color(0x66AB47BC), Color(0xFFCE93D8)),
    Highlight("orange", "Оранжевый", Color(0x4DFF8A65), Color(0xFFD84315), Color(0x66FF8A65), Color(0xFFFFAB91)),
)

/** Highlight colours in use: background of a chosen word, colour of the translation above it. */
class HlColors(val bg: Color, val text: Color)

val LocalHighlight = staticCompositionLocalOf { HlColors(Color(0x3343A047), Color(0xFF2E7D32)) }

fun readerThemeOf(key: String) = READER_THEMES.firstOrNull { it.key == key }

fun highlightColors(key: String, darkPage: Boolean): HlColors {
    val h = HIGHLIGHTS.firstOrNull { it.key == key } ?: HIGHLIGHTS[0]
    return if (darkPage) HlColors(h.bgDark, h.textDark) else HlColors(h.bgLight, h.textLight)
}

/** App colours with the reader's page colours (surfaces and text); [light]/[dark] = the app's own schemes. */
fun readerScheme(t: ReaderTheme, light: ColorScheme, dark: ColorScheme): ColorScheme {
    val base = if (t.dark) dark else light
    fun mix(f: Float) = lerp(t.bg, t.fg, f)
    return base.copy(
        background = t.bg, surface = t.bg, onBackground = t.fg, onSurface = t.fg,
        surfaceContainerLowest = t.bg, surfaceContainerLow = mix(0.04f), surfaceContainer = mix(0.06f),
        surfaceContainerHigh = mix(0.09f), surfaceContainerHighest = mix(0.12f),
        surfaceVariant = mix(0.10f), onSurfaceVariant = mix(0.72f), outline = mix(0.45f), outlineVariant = mix(0.18f),
        surfaceBright = mix(0.02f), surfaceDim = mix(0.08f),
    )
}

val KEEP_AWAKE = listOf(
    "system" to "Как в системе",
    "5" to "5 минут после касания",
    "10" to "10 минут после касания",
    "15" to "15 минут после касания",
    "30" to "30 минут после касания",
    "always" to "Пока открыт урок",
)

/** While the reader is open: the screen stays on for the chosen time after the last touch. */
@Composable
fun KeepScreenOn(state: AppState) {
    val view = LocalView.current
    val mode = state.keepAwake
    val touch = state.lastTouch
    LaunchedEffect(mode, touch) {
        val min = mode.toIntOrNull()
        when {
            mode == "always" -> view.keepScreenOn = true
            min != null -> { view.keepScreenOn = true; delay(min * 60_000L); view.keepScreenOn = false }
            else -> view.keepScreenOn = false
        }
    }
    DisposableEffect(Unit) { onDispose { view.keepScreenOn = false } }
}

/** Settings → Чтение: page colours, highlight colour, screen timeout. */
@Composable
fun SettingsReading(state: AppState) {
    val theme = state.readerTheme
    val hl = state.highlight
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Фон страницы", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeSwatch("Как в системе", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, theme == "system") {
                state.applyPrefs { readerTheme = "system" }
            }
            READER_THEMES.forEach { t ->
                ThemeSwatch(t.name, t.bg, t.fg, theme == t.key) { state.applyPrefs { readerTheme = t.key } }
            }
        }

        Text("Цвет выделения слов", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val t = readerThemeOf(theme)
        val pageBg = t?.bg ?: MaterialTheme.colorScheme.surface
        val pageFg = t?.fg ?: MaterialTheme.colorScheme.onSurface
        val darkPage = t?.dark ?: (pageBg.luminance() < 0.4f)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HIGHLIGHTS.forEach { h ->
                val c = highlightColors(h.key, darkPage)
                Column(
                    Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable { state.applyPrefs { highlight = h.key } }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(pageBg).background(c.bg)
                            .border(if (hl == h.key) 3.dp else 1.dp, if (hl == h.key) c.text else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (hl == h.key) Icon(Icons.Filled.Check, contentDescription = null, tint = c.text) }
                    Text(h.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                }
            }
        }
        // preview: a line of text with one chosen word and its translation above it
        val c = highlightColors(hl, darkPage)
        Surface(shape = RoundedCornerShape(12.dp), color = pageBg, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Bottom) {
                Text("Er las das ", color = pageFg, fontSize = 19.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "книгу", color = c.text,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (19 * 0.6).sp, lineHeight = (19 * 0.68).sp),
                        modifier = Modifier.trPull(19f, state.trGap),
                    )
                    Text(
                        "Buch", color = pageFg,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = (19 * 1.35).sp),
                        modifier = Modifier.background(c.bg, RoundedCornerShape(4.dp)),
                    )
                }
                Text(" bis zum Ende.", color = pageFg, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = (19 * 1.35).sp))
            }
        }

        Text("Расстояние до перевода над словом", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Slider(
                value = state.trGap.toFloat(),
                onValueChange = { v -> val n = (Math.round(v / 10f) * 10).coerceIn(0, 100); if (n != state.trGap) state.applyPrefs { trGap = n } },
                valueRange = 0f..100f, steps = 9, modifier = Modifier.weight(1f),
            )
            Text(if (state.trGap >= 100) "обычное" else "${state.trGap} %", Modifier.width(72.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "Меньше — перевод ближе к слову и текст ниже по высоте. Видно на примере выше.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text("Экран не гаснет при чтении", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        KEEP_AWAKE.forEach { (k, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = state.keepAwake == k, role = Role.RadioButton) {
                    state.applyPrefs { keepAwake = k }
                }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.keepAwake == k, onClick = null)
                Text(label, Modifier.padding(start = 12.dp))
            }
        }
        Text(
            "Пока урок открыт, экран не погаснет столько времени после последнего касания, даже если в телефоне стоит меньше.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeSwatch(name: String, bg: Color, fg: Color, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp), color = bg,
            border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().size(width = 72.dp, height = 52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { Text("Aa", color = fg, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
        }
        Text(name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
