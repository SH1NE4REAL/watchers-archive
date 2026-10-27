package org.watchersarchive.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import java.net.URI

private val ArchiveRed = Color(0xFFBE2834)
private val PageBackground = Color(0xFFF4F4F5)
private enum class Screen { Library, Reader, SourceDetail, Official, Cache }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository: ReaderRepository = AndroidRepository(applicationContext)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = ArchiveRed,
                    background = PageBackground,
                    surface = Color.White,
                    secondaryContainer = Color(0xFFF9E8E9),
                ),
            ) { ArchiveApp(repository) }
        }
    }
}

@Composable
private fun ArchiveApp(repository: ReaderRepository) {
    var screen by remember { mutableStateOf(Screen.Official) }
    var selected by remember { mutableStateOf<DemoComic?>(null) }
    var selectedSource by remember { mutableStateOf<CatalogSourceRecord?>(null) }
    var detailParent by remember { mutableStateOf(Screen.Official) }
    val view = LocalView.current
    val activity = view.context as? Activity
    val goBack = {
        screen = when (screen) {
            Screen.Reader -> if (selectedSource != null) Screen.SourceDetail else Screen.Cache
            Screen.SourceDetail -> detailParent
            else -> Screen.Official
        }
    }
    BackHandler(enabled = screen != Screen.Official) { goBack() }
    DisposableEffect(screen, activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        if (window != null && controller != null) {
            WindowCompat.setDecorFitsSystemWindows(window, screen != Screen.Reader)
            if (screen == Screen.Reader) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            if (window != null && controller != null && screen == Screen.Reader) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }
    if (screen == Screen.Reader) {
        // Keep the reader outside Scaffold so its content can draw edge-to-edge. The
        // reader owns its own transient controls and never inherits app chrome insets.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            selected?.let { ReaderScreen(it, repository) { screen = if (selectedSource != null) Screen.SourceDetail else Screen.Cache } }
        }
        return
    }
    Scaffold(
        containerColor = PageBackground,
        topBar = {
            Surface(color = PageBackground) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (screen == Screen.SourceDetail) TextButton(onClick = goBack) { Text("返回") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("观察者漫格", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("THE WATCHER'S ARCHIVE", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = screen == Screen.Official || (screen == Screen.SourceDetail && detailParent == Screen.Official),
                    onClick = { screen = Screen.Official },
                    icon = { Text("★", fontSize = 22.sp) }, label = { Text("官方推荐") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Library || (screen == Screen.SourceDetail && detailParent == Screen.Library),
                    onClick = { screen = Screen.Library },
                    icon = { Text("▤", fontSize = 22.sp) }, label = { Text("我的书架") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Cache,
                    onClick = { screen = Screen.Cache },
                    icon = { Text("⚙", fontSize = 22.sp) }, label = { Text("缓存与设置") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Library -> ShelfScreen(repository) { selectedSource = it; detailParent = Screen.Library; screen = Screen.SourceDetail }
                Screen.SourceDetail -> selectedSource?.let { record ->
                    ArticleDetailScreen(record, repository) { comic -> selected = comic; screen = Screen.Reader }
                }
                Screen.Official -> DiscoveryScreen(repository) { selectedSource = it; detailParent = Screen.Official; screen = Screen.SourceDetail }
                Screen.Cache -> CacheScreen(repository) { comic -> selectedSource = null; selected = comic; screen = Screen.Reader }
                Screen.Reader -> Unit
            }
        }
    }
}

@Composable
private fun ReaderScreen(comic: DemoComic, repository: ReaderRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var session by remember(comic.id) { mutableStateOf<ReaderSession?>(null) }
    var page by remember(comic.id) { mutableStateOf<ReaderPage?>(null) }
    var requestedIndex by remember(comic.id) { mutableIntStateOf(0) }
    var busy by remember(comic.id) { mutableStateOf(true) }
    var error by remember(comic.id) { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var bookmarks by remember(comic.id) { mutableStateOf(emptySet<Int>()) }
    var cache by remember { mutableStateOf<CacheSnapshot?>(null) }
    var traces by remember { mutableStateOf(emptyList<String>()) }
    var task by remember { mutableStateOf<Job?>(null) }
    var controlsVisible by remember(comic.id) { mutableStateOf(false) }
    var diagnosticsVisible by remember(comic.id) { mutableStateOf(false) }
    suspend fun load(index: Int) {
        requestedIndex = index; busy = true; error = null; notice = null
        try {
            page = checkNotNull(session) { "尚未打开归档，请重新打开。" }.loadPage(index)
            cache = repository.cacheSnapshot()
        } catch (e: CancellationException) {
            notice = "读取已取消；未完成页面不会提交缓存。"; throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally { busy = false; traces = repository.traceLines().takeLast(8) }
    }

    LaunchedEffect(comic.id) {
        task = launch {
            try {
                bookmarks = repository.bookmarkPages(comic.id)
                session = repository.open(comic)
                val restored = repository.lastPage(comic.id).coerceIn(0, (session!!.totalPages - 1).coerceAtLeast(0))
                load(restored)
            } catch (e: CancellationException) { notice = "读取已取消。"; throw e }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            finally { busy = false }
        }
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                // Cancel the reader token before joining the child coroutine. A coroutine
                // cancellation alone cannot interrupt HttpURLConnection; Session.close()
                // actively disconnects the current Range request.
                session?.close()
                task?.cancelAndJoin()
                // A load that completed concurrently with the first close may have
                // registered its cache lease after the initial snapshot.
                session?.close()
            }
        }
    }
    LaunchedEffect(comic.id) {
        while (true) { traces = repository.traceLines().takeLast(8); delay(750) }
    }

    // Keep the displayed page pinned while Compose owns the corresponding UI. When
    // navigation replaces it (or the screen leaves composition), release only that
    // page lease; the session still owns any in-flight/candidate leases.
    DisposableEffect(page) {
        val displayedPage = page
        onDispose { displayedPage?.release?.invoke() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        page?.let { current ->
            ReaderGestureSurface(current.bitmap, current.index, enabled = !busy,
                onPrevious = { if (!busy && current.index > 0) { busy = true; task = scope.launch { load(current.index - 1) } } },
                onNext = { if (!busy && current.index + 1 < current.total) { busy = true; task = scope.launch { load(current.index + 1) } } },
                onToggleControls = { controlsVisible = !controlsVisible })
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(14.dp))
            Text(if (busy) "正在读取归档目录与页面…" else "未取得可显示页面", color = Color.White)
            error?.let { Text(it, color = Color(0xFFFFB4AB), modifier = Modifier.padding(20.dp)) }
        }

        if (controlsVisible) {
            // Controls are an overlay over the page. The image remains edge-to-edge;
            // tapping the page hides this layer for distraction-free reading.
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("‹ 返回", color = Color.White, fontSize = 16.sp) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(comic.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("${if (comic.isTestContent) "测试内容 · " else ""}${page?.let { "第 ${it.index + 1} / ${it.total} 页" } ?: "读取中"}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(enabled = page != null && !busy, onClick = {
                        val current = page ?: return@TextButton
                        scope.launch {
                            try {
                                repository.setBookmark(comic.id, current.index, current.index !in bookmarks)
                                bookmarks = repository.bookmarkPages(comic.id)
                            } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                        }
                    }) { Text(if (page?.index?.let { it in bookmarks } == true) "已书签" else "书签", color = Color.White) }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .76f)).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArchiveRed)
                    error?.let { ErrorText(it) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.LightGray, modifier = Modifier.padding(vertical = 4.dp)) }
                    page?.let {
                        Text("${if (it.fromCache) "页面缓存命中" else "页面已解码"} · 本次传输 ${it.transferredBytes} 字节", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && (page?.index ?: 0) > 0, modifier = Modifier.weight(1f), onClick = { task = scope.launch { load((page?.index ?: 0) - 1) } }) { Text("上一页") }
                        Button(enabled = !busy && page != null && page!!.index + 1 < page!!.total, modifier = Modifier.weight(1f), onClick = { task = scope.launch { load(page!!.index + 1) } }) { Text("下一页") }
                        if (busy) OutlinedButton(onClick = { task?.cancel() }) { Text("取消") }
                        else if (error != null && session != null) OutlinedButton(onClick = { task = scope.launch { load(requestedIndex) } }) { Text("重试") }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("缓存 ${cache?.let { formatBytes(it.committedBytes) } ?: "—"} / 500 MB", Modifier.weight(1f), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { scope.launch {
                            try { notice = repository.clearCache(); cache = repository.cacheSnapshot() }
                            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                        } }) { Text("清理缓存", style = MaterialTheme.typography.labelSmall) }
                        TextButton(onClick = { diagnosticsVisible = !diagnosticsVisible }) { Text(if (diagnosticsVisible) "隐藏日志" else "调试", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (diagnosticsVisible && traces.isNotEmpty()) Column(Modifier.fillMaxWidth().height(82.dp).verticalScroll(rememberScrollState()).background(Color(0xFF242428)).padding(6.dp)) {
                        traces.forEach { Text(it, color = Color.LightGray, fontSize = 9.sp, fontFamily = FontFamily.Monospace, lineHeight = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CacheScreen(repository: ReaderRepository, onOpenTest: (DemoComic) -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CacheSnapshot?>(null) }
    var endpoint by remember { mutableStateOf(repository.baseUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showDeveloper by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { snapshot = repository.cacheSnapshot() }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("观察者漫格 ${BuildConfig.VERSION_NAME}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        WhiteCard {
            Text("临时阅读缓存", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${snapshot?.let { formatBytes(it.committedBytes) } ?: "—"} / 500 MB", style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(progress = { snapshot?.let { (it.committedBytes.toDouble() / it.limitBytes).toFloat().coerceIn(0f, 1f) } ?: 0f }, modifier = Modifier.fillMaxWidth())
            Text("硬上限 500,000,000 字节\n已提交：${snapshot?.committedBytes ?: "—"} 字节\n未提交（预留+临时）：${snapshot?.reservedBytes ?: "—"} 字节\n被读取任务占用：${snapshot?.pinnedEntries ?: "—"} 项", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { scope.launch {
                busy = true; error = null
                try { result = repository.clearCache(); snapshot = repository.cacheSnapshot() }
                catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                finally { busy = false }
            } }) { Text("清理可清理缓存") }
            Text("目录、阅读进度、书签与笔记独立持久保存。当前使用的页面不会被清理。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        TextButton(onClick = { showDeveloper = !showDeveloper }) { Text(if (showDeveloper) "收起开发测试" else "开发测试") }
        if (showDeveloper) WhiteCard {
            Text("开发测试（仅测试漫画需要电脑）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("服务器基础地址") }, singleLine = true)
            Text("模拟器： http://10.0.2.2:8765\nadb reverse 后： http://127.0.0.1:8765", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Button(onClick = {
                try {
                    val uri = URI(endpoint.trim())
                    require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.fragment == null) { "请输入不含凭据、查询参数或片段的 HTTP(S) 地址。" }
                    repository.baseUrl = endpoint.trim().trimEnd('/')
                    result = "测试服务器地址已保存。"; error = null
                } catch (e: Exception) { error = e.message ?: "服务器地址无效。" }
            }) { Text("保存地址") }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { ErrorText(it) }
        if (showDeveloper) WhiteCard {
            Text("测试漫画", fontWeight = FontWeight.Bold)
            Text("目录、书架和真实来源不使用上述电脑地址。只有以下自建测试漫画依赖测试服务器。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            repository.comics.forEach { comic -> TextButton(onClick = { onOpenTest(comic) }) { Text(comic.title) } }
        }
    }
}

@Composable
private fun WhiteCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = ArchiveRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().background(Color(0xFFFBECEE), RoundedCornerShape(8.dp)).padding(10.dp))
}

private fun formatBytes(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
