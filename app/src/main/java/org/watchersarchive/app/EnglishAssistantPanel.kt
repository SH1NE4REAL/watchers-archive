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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.graphics.RectF
import kotlin.math.roundToInt

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
@OptIn(ExperimentalLayoutApi::class)
internal fun EnglishAssistantCardHost(
    state: EnglishAssistantState,
    selectedScreenBounds: RectF?,
    speechReady: Boolean,
    downloadingModel: Boolean,
    modelNotice: String?,
    onSpeak: (String) -> Unit,
    onDownloadModel: (Boolean) -> Unit,
    onPhrase: (EnglishToken) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = state.recognizedPage() ?: return
    val token = when (state) {
        is EnglishAssistantState.LookingUp -> state.token
        is EnglishAssistantState.Selected -> state.token
        else -> return
    }
    var expanded by remember(page.pageKey, token.id) { mutableStateOf(false) }
    var manualEdge by remember(page.pageKey, token.id) { mutableStateOf<EnglishCardEdge?>(null) }
    val density = LocalDensity.current
    val insetTop = maxOf(WindowInsets.statusBarsIgnoringVisibility.getTop(density), WindowInsets.displayCutout.getTop(density)).toFloat()
    val insetBottom = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density).toFloat()
    BoxWithConstraints(modifier.fillMaxSize().testTag("english-assistant-card-host")) {
        val viewportHeight = constraints.maxHeight.toFloat()
        val availableHeight = (viewportHeight - insetTop - insetBottom).coerceAtLeast(0f)
        val edgePadding = with(density) { 8.dp.toPx() }
        val desired = if (expanded) minOf(with(density) { 360.dp.toPx() }, availableHeight * .45f)
            else minOf(with(density) { 160.dp.toPx() }, viewportHeight * .25f)
        val placement = placeEnglishAssistantCard(viewportHeight, desired, selectedScreenBounds,
            safeTop = insetTop + edgePadding, safeBottom = insetBottom + edgePadding,
            gap = with(density) { 12.dp.toPx() }, minimumUsableHeight = with(density) { 96.dp.toPx() }, manualEdge = manualEdge)
        if (placement.height <= 0f) return@BoxWithConstraints
        Box(Modifier.fillMaxWidth().offset { IntOffset(0, placement.top.roundToInt()) }.padding(horizontal = 12.dp)) {
            EnglishAssistantCard(state, speechReady, downloadingModel, modelNotice, onSpeak, onDownloadModel, onPhrase, onDismiss,
                modifier = Modifier.height(with(density) { placement.height.toDp() }), compact = !expanded,
                maximumHeight = with(density) { placement.height.toDp() },
                onToggleExpanded = { expanded = !expanded },
                onMove = { manualEdge = if (placement.edge == EnglishCardEdge.TOP) EnglishCardEdge.BOTTOM else EnglishCardEdge.TOP },
                moveLabel = if (placement.edge == EnglishCardEdge.TOP) "下移" else "上移",
                onAutomatic = if (manualEdge != null) ({ manualEdge = null }) else null)
        }
    }
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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    maximumHeight: Dp = 330.dp,
    onToggleExpanded: (() -> Unit)? = null,
    onMove: (() -> Unit)? = null,
    moveLabel: String = "上移",
    onAutomatic: (() -> Unit)? = null,
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
    Surface(modifier.fillMaxWidth().heightIn(max = maximumHeight).testTag("english-assistant-card"),
        shape = RoundedCornerShape(18.dp), color = Color.White, shadowElevation = 8.dp) {
        Column(Modifier.padding(if (compact) 12.dp else 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            Text(token.text, style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = Color(0xFFBE2834), fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis)
            if (compact) {
                val names = entry?.marvelNames.orEmpty().map { it.term.chineseName }.distinct()
                Text(when {
                    entry == null -> "正在查词…"
                    names.isNotEmpty() -> "漫威专名候选：${names.joinToString(" / ")}"
                    else -> entry.meaning.lineSequence().take(2).joinToString(" ")
                }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSpeak(token.text) }, enabled = speechReady,
                    contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f).testTag("english-pronounce")) { Text("发音", maxLines = 1) }
                onToggleExpanded?.let { action -> TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f).testTag("english-expand-card")) { Text(if (compact) "展开" else "收起", maxLines = 1) } }
                onMove?.let { action -> TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f).testTag("english-move-card")) { Text(moveLabel, maxLines = 1) } }
                onAutomatic?.let { action -> TextButton(onClick = action, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f).testTag("english-auto-card")) { Text("自动", maxLines = 1) } }
                TextButton(onClick = onDismiss, contentPadding = PaddingValues(horizontal = 4.dp), modifier = Modifier.weight(1f).testTag("close-english-card")) { Text("关闭", maxLines = 1) }
            }
            if (compact) return@Column
            if (!token.id.startsWith("phrase:")) englishLinePhrase(page, token)?.let { phrase ->
                if (phrase.text != token.text) TextButton(onClick = { onPhrase(phrase) }, modifier = Modifier.testTag("english-select-phrase")) { Text("本行短语") }
            }
            if (!speechReady) Text("本机英文语音尚未就绪或未安装，可在系统文字转语音设置中检查。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            if (entry == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在查词…", color = Color.Gray)
            } else SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (entry.marvelNames.isNotEmpty()) {
                        Text("漫威专名候选", fontWeight = FontWeight.Bold, color = Color(0xFFBE2834))
                        entry.marvelNames.forEach { match ->
                            val term = match.term
                            Text("${term.chineseName} · ${term.englishName}", fontWeight = FontWeight.Bold)
                            Text("${term.kind.label} · ${term.chineseEvidence.label}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            match.contextHint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text("这是漫威名称候选，请结合原句，普通词义见下方。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            TextButton(onClick = { runCatching { uriHandler.openUri(term.englishSourceUrl) } }, contentPadding = PaddingValues(0.dp)) {
                                Text("英文实体资料", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("普通词义", fontWeight = FontWeight.Bold)
                    }
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
