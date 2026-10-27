package org.watchersarchive.app

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

private val translatedPages = PageTranslationCache()

internal fun clearPageTranslationMemory() = translatedPages.clear()

/** Explicit page action. Opening the panel never uploads artwork or starts a model download. */
@Composable
internal fun PageTranslationPanel(
    bitmap: Bitmap,
    pageKey: String,
    onDismiss: () -> Unit,
    createEngine: () -> PageTranslationEngine<Bitmap> = { MlKitPageTranslationEngine() },
) {
    val controller = remember(pageKey) { PageTranslationController(createEngine(), translatedPages) }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    var wifiOnly by remember { mutableStateOf(true) }
    LaunchedEffect(controller) { controller.openPage(pageKey) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    val busy = state is PageTranslationState.Downloading || state is PageTranslationState.Recognizing || state is PageTranslationState.Translating || state is PageTranslationState.CheckingModel

    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("本页译文", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Text("设备端机器翻译，不改动原图。人名、艺术字体和气泡顺序可能有误，请对照原文。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (val current = state) {
                    PageTranslationState.CheckingModel -> Text("正在检查本机翻译模型…")
                    PageTranslationState.ModelRequired -> {
                        Text("首次使用需下载约 30MB 级的语言模型，占用独立存储。下载成功后可离线翻译；漫画图片与识别文字不会上传到翻译服务器。")
                        Row { Checkbox(wifiOnly, { wifiOnly = it }); Text("仅在 Wi-Fi 下下载", Modifier.padding(top = 12.dp)) }
                        Button(onClick = { scope.launch { controller.translatePage(pageKey, bitmap, allowModelDownload = true, wifiOnly = wifiOnly) } }) { Text("下载模型并翻译本页") }
                    }
                    PageTranslationState.ReadyToTranslate -> Button(onClick = { scope.launch { controller.translatePage(pageKey, bitmap, allowModelDownload = false) } }) { Text("翻译本页") }
                    PageTranslationState.Downloading -> Text("正在下载语言模型…关闭面板会停止本页译文处理，SDK 模型下载可能继续。")
                    PageTranslationState.Recognizing -> Text("正在识别本页英文…")
                    is PageTranslationState.Translating -> Text("正在翻译 ${current.completed + 1} / ${current.total} 段…")
                    PageTranslationState.NoText -> Text("没有识别到可翻译的英文。漫画小字、拟声词或特殊字体可能无法识别。")
                    is PageTranslationState.Failed -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { scope.launch { controller.openPage(pageKey) } }) { Text("重新检查") }
                    }
                    is PageTranslationState.Complete -> { }
                }
                val translated = (state as? PageTranslationState.Complete)?.page
                if (translated != null) {
                    if (translated.truncated) Text("本页文字较多，已限制识别段数和长度。", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(translated.blocks) { index, block ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                SelectionContainer {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("${index + 1}. ${block.chinese}", style = MaterialTheme.typography.bodyLarge)
                                        Text(block.original, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                } else Spacer(Modifier.weight(1f))
                Text("译文由 Google 翻译提供 · 机器译文非官方汉化", color = Color.Gray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable {
                        try { uriHandler.openUri("https://translate.google.com/") }
                        catch (_: Exception) { /* Attribution stays visible if no browser is installed. */ }
                    })
                Text("当前显示页的小字经过缩放，识别可能不完整。", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                if (busy) OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("取消本页翻译") }
            }
        }
    }
}
