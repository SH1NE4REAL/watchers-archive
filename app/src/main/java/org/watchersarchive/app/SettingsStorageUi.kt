package org.watchersarchive.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Storage details are requested only when the user opens this settings panel. */
@Composable
internal fun SettingsStorageDialog(repository: ReaderRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CacheSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    var clearing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) {
        try { snapshot = repository.cacheSnapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "暂时无法读取存储状态，请稍后重试。" }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("存储管理") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("临时阅读内容", style = MaterialTheme.typography.titleSmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                else Text("${snapshot?.let { storageMegabytes(it.committedBytes) } ?: "—"} / 500 MB", style = MaterialTheme.typography.headlineSmall)
                Text("空间满时自动清理较久未读、未被使用的内容，正在阅读的页面会受到保护。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Text("整本离线下载单独保留。清理临时内容不会删除离线漫画、书架、阅读进度、书签或笔记。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && !clearing, modifier = Modifier.testTag("clear-temporary-content"), onClick = {
                scope.launch {
                    clearing = true; result = null; error = null
                    try {
                        repository.clearCache()
                        snapshot = repository.cacheSnapshot()
                        result = "已清理可释放的临时内容。"
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "暂时无法清理，请稍后重试。" }
                    finally { clearing = false }
                }
            }) { Text(if (clearing) "清理中…" else "清理临时内容") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun storageMegabytes(bytes: Long) = String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
