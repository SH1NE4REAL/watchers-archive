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
import androidx.compose.foundation.gestures.detectTapGestures
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
private enum class Screen { Library, Detail, Reader, Cache }

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
    var screen by remember { mutableStateOf(Screen.Library) }
    var selected by remember { mutableStateOf<DemoComic?>(null) }
    val view = LocalView.current
    val activity = view.context as? Activity
    val goBack = {
        screen = if (screen == Screen.Reader) Screen.Detail else Screen.Library
    }
    BackHandler(enabled = screen != Screen.Library) { goBack() }
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
            selected?.let { ReaderScreen(it, repository) { screen = Screen.Detail } }
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
                    if (screen != Screen.Library) TextButton(onClick = goBack) { Text("返回") }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text("观察者漫格", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("THE WATCHER'S ARCHIVE · 阅读内核验证", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    selected = screen == Screen.Library || screen == Screen.Detail,
                    onClick = { screen = Screen.Library },
                    icon = { Text("▤", fontSize = 22.sp) }, label = { Text("测试书架") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Cache,
                    onClick = { screen = Screen.Cache },
                    icon = { Text("◷", fontSize = 22.sp) }, label = { Text("缓存与设置") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Library -> LibraryScreen(repository.comics) { selected = it; screen = Screen.Detail }
                Screen.Detail -> selected?.let { DetailScreen(it, repository) { screen = Screen.Reader } }
                Screen.Reader -> selected?.let { ReaderScreen(it, repository) { screen = Screen.Detail } }
                Screen.Cache -> CacheScreen(repository)
            }
        }
    }
}

@Composable
private fun LibraryScreen(comics: List<DemoComic>, onSelect: (DemoComic) -> Unit) {
    var query by remember { mutableStateOf("") }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("搜索测试漫画") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
        item { Text("我的漫画", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp)) }
        item { Text("封面、简介和阅读入口均已就绪。当前条目是本地自建测试内容。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        items(comics.filter { it.title.contains(query, true) || it.description.contains(query, true) }, key = { it.id }) { comic ->
            Card(onClick = { onSelect(comic) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TestCover(comic, Modifier.width(78.dp).height(108.dp))
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(comic.title, fontWeight = FontWeight.Bold)
                        Text("${comic.series} · ${comic.year}", color = ArchiveRed, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(comic.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        Text("点击查看简介并开始阅读", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                    Text("›", fontSize = 24.sp, color = Color.Gray)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun TestCover(comic: DemoComic, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        color = Color(0xFFF8E6E8),
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("THE WATCHER'S", color = ArchiveRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Column {
                Text("ARCHIVE", color = Color(0xFF26262A), fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text("TEST EDITION", color = ArchiveRed, fontSize = 8.sp)
            }
            Text("${comic.id.uppercase()}\n${comic.year}", color = Color(0xFF5F5A5E), fontSize = 8.sp)
        }
    }
}

@Composable
private fun DetailScreen(comic: DemoComic, repository: ReaderRepository, onRead: () -> Unit) {
    var last by remember(comic.id) { mutableIntStateOf(0) }
    var bookmarks by remember(comic.id) { mutableStateOf(emptySet<Int>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(comic.id) {
        try { last = repository.lastPage(comic.id); bookmarks = repository.bookmarkPages(comic.id) }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        WhiteCard {
            Row(verticalAlignment = Alignment.Top) {
                TestCover(comic, Modifier.width(112.dp).height(156.dp))
                Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("测试内容", color = ArchiveRed, style = MaterialTheme.typography.labelMedium)
                    Text(comic.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${comic.series} · ${comic.year}", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(comic.description, style = MaterialTheme.typography.bodyLarge)
            Text("简介", fontWeight = FontWeight.Bold)
            Text("这是观察者漫格的本地阅读内核验证条目。它用于验证从目录、详情到阅读器的完整流程；内容与漫威官方目录无关。", color = Color.DarkGray)
            Text("来源：本地测试服务器\n${repository.baseUrl}/${comic.path}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        WhiteCard {
            Text("最近阅读：第 ${last + 1} 页")
            Text("书签：${if (bookmarks.isEmpty()) "暂无" else bookmarks.sorted().joinToString { "第 ${it + 1} 页" }}")
            Text("页码是持久数据；清理临时缓存后仍应保留。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        error?.let { ErrorText(it) }
        Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) { Text("开始阅读") }
        Text("只有取得有效 Range 响应、解析归档并成功解码后，阅读器才会显示页面。错误端点用于验证拒绝与取消行为。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
    var controlsVisible by remember(comic.id) { mutableStateOf(true) }
    var diagnosticsVisible by remember(comic.id) { mutableStateOf(false) }
    var zoom by remember(comic.id) { mutableStateOf(1f) }
    var imageOffset by remember(comic.id) { mutableStateOf(Offset.Zero) }

    LaunchedEffect(page?.index) {
        zoom = 1f
        imageOffset = Offset.Zero
    }

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

    val gestureModifier = Modifier
        .pointerInput(page?.index, busy) {
            var distance = 0f
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    distance += amount
                },
                onDragEnd = {
                    val current = page?.index ?: 0
                    if (!busy && zoom <= 1.01f && abs(distance) > 80f) {
                        val target = current + if (distance < 0f) 1 else -1
                        if (target >= 0 && target < (page?.total ?: 0)) task = scope.launch { load(target) }
                    }
                    distance = 0f
                },
                onDragCancel = { distance = 0f },
            )
        }
        .pointerInput(controlsVisible) {
            detectTapGestures(onTap = { controlsVisible = !controlsVisible })
        }

    Box(
        Modifier.fillMaxSize().background(Color.Black).then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        page?.let { current ->
            Image(
                current.bitmap.asImageBitmap(),
                "测试漫画第 ${current.index + 1} 页",
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = zoom
                        scaleY = zoom
                        translationX = imageOffset.x
                        translationY = imageOffset.y
                    }
                    .pointerInput(page?.index) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                            imageOffset = if (zoom <= 1.01f) Offset.Zero else imageOffset + pan
                        }
                    }
                    .pointerInput(page?.index) {
                        detectTapGestures(onDoubleTap = {
                            zoom = if (zoom > 1.01f) 1f else 2f
                            imageOffset = Offset.Zero
                        })
                    },
                contentScale = ContentScale.Fit,
            )
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
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) { Text("‹ 返回", color = Color.White, fontSize = 16.sp) }
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(comic.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text("自建测试 · ${page?.let { "第 ${it.index + 1} / ${it.total} 页" } ?: "读取中"}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
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
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .76f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
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
        } else {
            Text("点击屏幕显示阅读控制", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun CacheScreen(repository: ReaderRepository) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CacheSnapshot?>(null) }
    var endpoint by remember { mutableStateOf(repository.baseUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { snapshot = repository.cacheSnapshot() }
        catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
        WhiteCard {
            Text("本地测试服务器", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        WhiteCard {
            Text("验证边界", fontWeight = FontWeight.Bold)
            Text("此版本验证标准 CBZ、合成 ZIP64、Range 错误、网络中断和对象变化。尚未接入漫威目录与任何漫画下载站。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
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
