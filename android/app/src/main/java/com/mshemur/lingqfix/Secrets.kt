package com.mshemur.lingqfix

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import kotlinx.coroutines.launch

/** "×" at the end of a search field: empties it. */
@Composable
fun ClearButton(text: String, onClear: () -> Unit) {
    if (text.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "Очистить") }
}

/** Copies a key / password; marked sensitive so Android does not show it in the clipboard preview. */
fun copySecret(ctx: Context, label: String, text: String) {
    val clip = ClipData.newPlainText(label, text)
    if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply {
        putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else "android.content.extra.IS_SENSITIVE", true)
    }
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Text field for a key / token / password: hidden; copy button; ⋮ with show / hide and, when [credId] is set,
 * "save to / take from the password manager" (Google Password Manager through Android's Credential Manager).
 */
@Composable
internal fun SecretField(value: String, label: String, credId: String? = null, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        supportingText = note?.let { { Text(it) } },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotEmpty()) IconButton(onClick = { copySecret(ctx, label, value); note = "Скопировано" }) {
                    Icon(AppIcons.ContentCopy, contentDescription = "Копировать")
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Ещё") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(if (show) "Скрыть" else "Показать") }, onClick = { show = !show; menu = false })
                        if (credId != null && value.isNotBlank()) DropdownMenuItem(
                            text = { Text("Сохранить в менеджер паролей") },
                            onClick = {
                                menu = false
                                val act = ctx.activity() ?: return@DropdownMenuItem
                                scope.launch {
                                    note = runCatching {
                                        CredentialManager.create(act).createCredential(act, CreatePasswordRequest(credId, value))
                                        AppLog.add("app", "«$credId» сохранён в менеджер паролей")
                                        "Сохранено в менеджер паролей"
                                    }.getOrElse { e ->
                                        AppLog.error("app", "Менеджер паролей: сохранить", e)
                                        "Не сохранилось: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                            },
                        )
                        if (credId != null) DropdownMenuItem(
                            text = { Text("Взять из менеджера паролей") },
                            onClick = {
                                menu = false
                                val act = ctx.activity() ?: return@DropdownMenuItem
                                scope.launch {
                                    note = runCatching {
                                        val r = CredentialManager.create(act).getCredential(act, GetCredentialRequest(listOf(GetPasswordOption())))
                                        val c = r.credential as? PasswordCredential ?: return@runCatching "В менеджере паролей ничего не выбрано"
                                        onChange(c.password)
                                        "Взято из менеджера паролей (${c.id})"
                                    }.getOrElse { e ->
                                        AppLog.error("app", "Менеджер паролей: взять", e)
                                        "Не получилось: ${e.message ?: e.javaClass.simpleName}"
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
    )
}
