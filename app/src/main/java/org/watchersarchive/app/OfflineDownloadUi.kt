package org.watchersarchive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Only the visible destination polls its small durable download records. */
@Composable
private fun downloadStatuses(repository: ReaderRepository): List<OfflineDownloadStatus> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var statuses by remember(repository) { mutableStateOf(emptyList<OfflineDownloadStatus>()) }
    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { statuses = repository.offlineDownloads() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the last known state after a transient read error. */ }
                delay(1_000)
            }
        }
    }
    return statuses
}

@Composable
private fun downloadStatus(repository: ReaderRepository, comicId: String): OfflineDownloadStatus? {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var status by remember(repository, comicId) { mutableStateOf<OfflineDownloadStatus?>(null) }
    LaunchedEffect(repository, comicId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { status = repository.offlineStatus(comicId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the last known state. */ }
                delay(1_000)
            }
        }
    }
    return status
}

internal fun offlineProgressLabel(status: OfflineDownloadStatus): String {
    val percent = if (status.totalBytes > 0) (status.downloadedBytes * 100 / status.totalBytes).coerceIn(0, 100) else 0
    return when (status.state) {
        OfflineDownloadState.QUEUED -> "等待下载"
        OfflineDownloadState.DOWNLOADING -> "正在下载 · $percent%"
        OfflineDownloadState.PAUSED -> "已暂停 · $percent%"
        OfflineDownloadState.COMPLETED -> "已下载，可离线阅读"
        OfflineDownloadState.FAILED -> status.message ?: "下载中断，可继续重试"
    }
}

@Composable
internal fun DownloadComicAction(record: CatalogSourceRecord, repository: ReaderRepository) {
    if (record.source == "local") return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val status = downloadStatus(repository, record.externalId)
    var confirm by remember(record.externalId) { mutableStateOf(false) }
    var wifiOnly by remember { mutableStateOf(true) }
    var busy by remember(record.externalId) { mutableStateOf(false) }
    var message by remember(record.externalId) { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val active = status?.state in setOf(OfflineDownloadState.QUEUED, OfflineDownloadState.DOWNLOADING)
    val complete = status?.state == OfflineDownloadState.COMPLETED
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy && !complete, modifier = Modifier.fillMaxWidth().testTag("download-comic"), onClick = {
            if (active) scope.launch {
                busy = true
                try { repository.pauseDownload(record.externalId); message = "下载已暂停。" }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "暂时无法暂停，请稍后重试。" }
                finally { busy = false }
            } else confirm = true
        }) {
            Text(when {
                busy -> "正在准备…"
                complete -> "已下载，可离线阅读"
                active -> "暂停下载"
                status != null -> "继续下载"
                else -> "下载整本"
            })
        }
        if (status?.state == OfflineDownloadState.DOWNLOADING) LinearProgressIndicator(
            progress = { (status.downloadedBytes.toDouble() / status.totalBytes).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (status != null && !complete) Text(offlineProgressLabel(status), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("下载整本漫画") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(record.title)
                record.sizeLabel?.let { Text("来源标注大小：$it", style = MaterialTheme.typography.bodySmall) }
                Text("下载完成后可直接离线阅读。漫画会保存在 App 中，并自动加入书架，占用手机存储。")
                Row { Checkbox(checked = wifiOnly, onCheckedChange = { wifiOnly = it }); Text("仅使用 Wi-Fi 下载", Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("confirm-download"), onClick = {
            confirm = false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            scope.launch {
                busy = true; message = "正在准备下载…"
                try {
                    repository.downloadComic(record, wifiOnly)
                    message = if (wifiOnly) "已加入下载队列，连接 Wi-Fi 后会自动下载。" else "已加入下载队列。"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "暂时无法下载，请确认来源可用和手机空间充足后重试。" }
                finally { busy = false }
            }
        }) { Text("确认下载") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
    )
}

@Composable
internal fun OfflineDownloadsCard(repository: ReaderRepository) {
    val statuses = downloadStatuses(repository)
    var showManager by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("离线漫画", style = MaterialTheme.typography.titleMedium)
            val completed = statuses.count { it.state == OfflineDownloadState.COMPLETED }
            Text(if (statuses.isEmpty()) "在漫画详情下载整本，之后无需联网即可阅读。" else "已下载 $completed 部 · 共 ${statuses.size} 个任务", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showManager = true }) { Text("管理下载") }
        }
    }
    if (showManager) OfflineDownloadManager(statuses, repository) { showManager = false }
}

@Composable
private fun OfflineDownloadManager(statuses: List<OfflineDownloadStatus>, repository: ReaderRepository, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<OfflineDownloadStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.85f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("管理下载", style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
                Text("清理临时内容不会删除这里的漫画。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (statuses.isEmpty()) Text("还没有离线下载。", color = Color.Gray)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(statuses, key = { it.comicId }) { status ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(status.title, style = MaterialTheme.typography.titleSmall)
                            Text(offlineProgressLabel(status), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                            Row {
                                if (status.state != OfflineDownloadState.COMPLETED) TextButton(enabled = busy == null, onClick = { scope.launch {
                                    busy = status.comicId; error = null
                                    try {
                                        if (status.state in setOf(OfflineDownloadState.QUEUED, OfflineDownloadState.DOWNLOADING)) repository.pauseDownload(status.comicId)
                                        else repository.resumeDownload(status.comicId, wifiOnly = true)
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { error = "操作未完成，请稍后重试。" }
                                    finally { busy = null }
                                } }) { Text(if (status.state in setOf(OfflineDownloadState.QUEUED, OfflineDownloadState.DOWNLOADING)) "暂停" else "继续（Wi-Fi）") }
                                TextButton(enabled = busy == null, onClick = { removing = status }) { Text("删除下载") }
                            }
                        }
                    }
                }
            }
        }
    }
    removing?.let { selected -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("删除下载？") },
        text = { Text("只删除「${selected.title}」的离线文件，保留书架、阅读进度、书签和笔记。") },
        confirmButton = { TextButton(onClick = {
            removing = null
            scope.launch {
                busy = selected.comicId; error = null
                try { repository.removeOfflineComic(selected.comicId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "这部漫画可能正在阅读，请退出阅读后再删除。" }
                finally { busy = null }
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }) }
}
