package org.watchersarchive.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Decorative only. The single image gesture recognizer also handles word taps. */
@Composable
internal fun EnglishAssistantOverlay(
    page: EnglishPageTokens,
    transform: ReaderImageTransform,
    bounds: ReaderImageBounds,
    selected: EnglishToken? = null,
) {
    Canvas(Modifier.fillMaxSize().testTag("english-assistant-overlay")) {
        page.tokens.take(512).forEach { token ->
            bounds.imageRectOnScreen(transform, token.bounds)?.let { rect ->
                drawLine(Color.White.copy(alpha = .24f), Offset(rect.left, rect.bottom),
                    Offset(rect.right, rect.bottom), 1.dp.toPx())
            }
        }
        selected?.let { token -> bounds.imageRectOnScreen(transform, token.bounds)?.let { rect ->
            drawRoundRect(Color(0xFFFFD166).copy(alpha = .28f), Offset(rect.left, rect.top),
                Size(rect.width(), rect.height()), CornerRadius(3.dp.toPx()))
        } }
    }
}

internal fun EnglishAssistantState.recognizedPage(): EnglishPageTokens? = when (this) {
    is EnglishAssistantState.Ready -> page
    is EnglishAssistantState.LookingUp -> page
    is EnglishAssistantState.Selected -> page
    else -> null
}

/** One line only: this is a user selection, not a claim to detect idioms. */
internal fun englishLinePhrase(page: EnglishPageTokens, token: EnglishToken): EnglishToken? {
    val line = page.tokens.filter { it.lineId == token.lineId }
    val position = line.indexOfFirst { it.id == token.id }
    if (position < 0) return null
    val start = if (line.size <= 12) 0 else (position - 3).coerceAtLeast(0)
    return phraseFromRange(page, line[start].id, line[minOf(start + 11, line.lastIndex)].id)
}

@Composable
internal fun EnglishAssistantCard(
    state: EnglishAssistantState,
    speechReady: Boolean,
    downloadingModel: Boolean,
    modelNotice: String?,
    onSpeak: (String) -> Unit,
    onDownloadModel: (Boolean) -> Unit,
    onPhrase: (EnglishToken) -> Unit,
    onDismiss: () -> Unit,
) {
    val page = state.recognizedPage() ?: return
    val token = when (state) {
        is EnglishAssistantState.Selected -> state.token
        is EnglishAssistantState.LookingUp -> state.token
        else -> return
    }
    val entry = (state as? EnglishAssistantState.Selected)?.entry
    val uriHandler = LocalUriHandler.current
    var wifiOnly by remember { mutableStateOf(true) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        .heightIn(max = 330.dp).testTag("english-assistant-card"),
        shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 8.dp) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(token.text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFBE2834), fontWeight = FontWeight.Bold)
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("close-english-card")) { Text("关闭") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = { onSpeak(token.text) }, enabled = speechReady,
                    modifier = Modifier.testTag("english-pronounce")) { Text("▶ 发音") }
                if (!token.id.startsWith("phrase:")) englishLinePhrase(page, token)?.let { phrase ->
                    if (phrase.text != token.text) TextButton(onClick = { onPhrase(phrase) }, modifier = Modifier.testTag("english-select-phrase")) { Text("本行短语") }
                }
            }
            if (!speechReady) Text("本机英文语音尚未就绪或未安装，可在系统文字转语音设置中检查。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            if (entry == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在查词…", color = Color.Gray)
            } else SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(entry.phonetic?.let { "音标：$it" } ?: "音标暂缺", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                    Text(entry.partOfSpeech?.let { "词性：$it" } ?: "词性暂缺", style = MaterialTheme.typography.bodySmall)
                    Text(entry.meaning, style = MaterialTheme.typography.bodyLarge)
                    entry.englishDefinition?.takeIf { it.isNotBlank() }?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                    entry.context?.takeIf { it.isNotBlank() && it != token.text }?.let {
                        Text("所在对白：$it", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    entry.contextMeaning?.let { Text("对白参考（机器翻译）：$it", style = MaterialTheme.typography.bodyMedium) }
                    entry.notice?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (entry?.requiresModel == true || downloadingModel) {
                Text("对白参考和未收录短语可由本机翻译模型辅助，首次需下载约 30MB 级语言模型。关闭助手后 SDK 下载可能继续。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                Row {
                    Checkbox(wifiOnly, { wifiOnly = it }, enabled = !downloadingModel)
                    Text("仅用 Wi-Fi 下载模型", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !downloadingModel, onClick = { onDownloadModel(wifiOnly) },
                    modifier = Modifier.fillMaxWidth().testTag("download-english-model")) {
                    Text(if (downloadingModel) "正在下载…" else "下载中文辅助模型")
                }
            }
            modelNotice?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            entry?.sourceLabel?.let { source ->
                TextButton(onClick = { entry.sourceUrl?.let { url -> runCatching { uriHandler.openUri(url) } } },
                    contentPadding = PaddingValues(0.dp)) { Text(source, style = MaterialTheme.typography.labelSmall) }
            }
            if (entry?.contextMeaning != null || entry?.meaning?.contains("机器参考") == true) {
                TextButton(onClick = { runCatching { uriHandler.openUri("https://translate.google.com/") } }, contentPadding = PaddingValues(0.dp)) {
                    Text("机器译文由 Google 翻译提供", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun EnglishAssistantStatus(state: EnglishAssistantState) {
    val text = when (state) {
        EnglishAssistantState.Recognizing -> "正在识别本页英文…"
        is EnglishAssistantState.Failed -> state.message
        is EnglishAssistantState.Ready -> when {
            state.page.tokens.isEmpty() -> "未识别到英文。小字、艺术字体可能无法识别。"
            state.page.truncated -> "点击单词查义，长按查本行短语。文字较多，已限制识别范围。"
            else -> "点击单词查义，长按查本行短语。"
        }
        else -> return
    }
    Surface(color = Color.Black.copy(alpha = .65f), shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(12.dp)) {
        Text(text, color = Color.White, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    }
}
