package org.watchersarchive.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import java.net.URI
import org.watchersarchive.core.ArchiveError
import org.watchersarchive.core.ArchiveException

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
                    primaryContainer = Color(0xFFFFE0E4),
                    onPrimaryContainer = Color(0xFF881725),
                ),
            ) { ArchiveApp(repository) }
        }
    }
}

@Composable
internal fun ArchiveApp(repository: ReaderRepository) {
    var screen by remember { mutableStateOf(Screen.Official) }
    var selected by remember { mutableStateOf<DemoComic?>(null) }
    var selectedSource by remember { mutableStateOf<CatalogSourceRecord?>(null) }
    var detailParent by remember { mutableStateOf(Screen.Official) }
    val discoverySearch = rememberSaveable(saver = DiscoverySearchState.Saver) { DiscoverySearchState() }
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
    DisposableEffect(screen, view) {
        val previous = view.keepScreenOn
        if (screen == Screen.Reader) view.keepScreenOn = repository.keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }
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
            Surface(color = Color(0xFFFFECEE)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (screen == Screen.SourceDetail) TextButton(onClick = goBack) { Text("返回") }
                    ComicHeaderDecoration(Modifier.weight(1f))
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                NavigationBarItem(
                    modifier = Modifier.testTag("nav-watchers-library"),
                    selected = screen == Screen.Official || (screen == Screen.SourceDetail && detailParent == Screen.Official),
                    onClick = { screen = Screen.Official },
                    icon = { HeroGlyph(HeroMotif.Shield, Modifier.size(25.dp),
                        if (screen == Screen.Official || (screen == Screen.SourceDetail && detailParent == Screen.Official)) ArchiveRed else Color.Gray) },
                    label = { Text("观察者书库") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = ArchiveRed, selectedTextColor = ArchiveRed, indicatorColor = Color(0xFFFFE0E4)),
                )
                NavigationBarItem(
                    selected = screen == Screen.Library || (screen == Screen.SourceDetail && detailParent == Screen.Library),
                    onClick = { screen = Screen.Library },
                    icon = { Text("▤", fontSize = 22.sp) }, label = { Text("我的书架") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = ArchiveRed, selectedTextColor = ArchiveRed, indicatorColor = Color(0xFFFFE0E4)),
                )
                NavigationBarItem(
                    modifier = Modifier.testTag("nav-settings"),
                    selected = screen == Screen.Cache,
                    onClick = { screen = Screen.Cache },
                    icon = { Text("⚙", fontSize = 22.sp) }, label = { Text("设置") },
                    colors = NavigationBarItemDefaults.colors(selectedIconColor = ArchiveRed, selectedTextColor = ArchiveRed, indicatorColor = Color(0xFFFFE0E4)),
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
                Screen.Official -> DiscoveryScreen(repository, discoverySearch) { selectedSource = it; detailParent = Screen.Official; screen = Screen.SourceDetail }
                Screen.Cache -> CacheScreen(repository) { comic -> selectedSource = null; selected = comic; screen = Screen.Reader }
                Screen.Reader -> Unit
            }
        }
    }
}

@Composable
internal fun ReaderScreen(
    comic: DemoComic,
    repository: ReaderRepository,
    createEnglishOcr: () -> EnglishOcrEngine = { MlKitEnglishOcrEngine() },
    englishDictionaryOverride: EnglishDictionaryProvider? = null,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var session by remember(comic.id) { mutableStateOf<ReaderSession?>(null) }
    var page by remember(comic.id) { mutableStateOf<ReaderPage?>(null) }
    var requestedIndex by remember(comic.id) { mutableIntStateOf(0) }
    var busy by remember(comic.id) { mutableStateOf(true) }
    var error by remember(comic.id) { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var bookmarks by remember(comic.id) { mutableStateOf(emptySet<Int>()) }
    var task by remember { mutableStateOf<Job?>(null) }
    var controlsVisible by remember(comic.id) { mutableStateOf(false) }
    var englishAssistantEnabled by remember(comic.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val englishDictionary = remember(comic.id, context) { PublicEnglishDictionaryProvider(context.applicationContext) }
    val englishAssistant = remember(comic.id, englishDictionaryOverride) { EnglishAssistantController(createEnglishOcr(), englishDictionaryOverride ?: englishDictionary) }
    val englishSpeech = remember(comic.id, englishAssistantEnabled) { if (englishAssistantEnabled) DeviceEnglishSpeech(context.applicationContext) else null }
    val noSpeech = remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val speechReady by (englishSpeech?.ready ?: noSpeech).collectAsState()
    val englishState by englishAssistant.state.collectAsState()
    var downloadingEnglishModel by remember { mutableStateOf(false) }
    var englishModelNotice by remember { mutableStateOf<String?>(null) }
    var englishModelTask by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(englishAssistant, englishDictionary) { onDispose { englishAssistant.close(); englishDictionary.close() } }
    DisposableEffect(englishSpeech) { onDispose { englishSpeech?.close() } }
    val readerLifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(readerLifecycle, englishSpeech) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) englishSpeech?.stop() }
        readerLifecycle.addObserver(observer)
        onDispose { readerLifecycle.removeObserver(observer) }
    }
    suspend fun load(index: Int) {
        englishAssistant.disable(); englishSpeech?.stop()
        requestedIndex = index; busy = true; error = null; notice = null
        try {
            page = checkNotNull(session) { "尚未打开归档，请重新打开。" }.loadPage(index)
        } catch (e: CancellationException) {
            notice = "已取消读取。"; throw e
        } catch (e: Exception) {
            error = readerErrorMessage(e)
        } finally { busy = false }
    }

    LaunchedEffect(comic.id) {
        task = launch {
            try {
                bookmarks = repository.bookmarkPages(comic.id)
                session = repository.open(comic)
                val restored = repository.lastPage(comic.id).coerceIn(0, (session!!.totalPages - 1).coerceAtLeast(0))
                load(restored)
            } catch (e: CancellationException) { notice = "读取已取消。"; throw e }
            catch (e: Exception) { error = readerErrorMessage(e) }
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
    // Keep the displayed page pinned while Compose owns the corresponding UI. When
    // navigation replaces it (or the screen leaves composition), release only that
    // page lease; the session still owns any in-flight/candidate leases.
    DisposableEffect(page) {
        val displayedPage = page
        onDispose { displayedPage?.release?.invoke() }
    }
    LaunchedEffect(page?.index, session, englishState is EnglishAssistantState.Recognizing) {
        val current = page ?: return@LaunchedEffect
        if (repository.prefetchEnabled && englishState !is EnglishAssistantState.Recognizing) session?.prefetchPage(current.index + 1)
        else session?.cancelPrefetch()
    }

    LaunchedEffect(page?.index, englishAssistantEnabled, busy) {
        if (busy) { englishAssistant.disable(); return@LaunchedEffect }
        val current = page ?: return@LaunchedEffect
        if (englishAssistantEnabled) {
            englishSpeech?.stop()
            englishAssistant.openPage(
                "${comic.id}:${comic.verifiedLength}:${current.stablePageId ?: "${current.index}:${current.bitmap.generationId}"}",
                current.bitmap,
            )
        } else englishAssistant.disable()
    }

    fun selectEnglishToken(x: Float, y: Float, phrase: Boolean): Boolean {
        if (!englishAssistantEnabled || busy) return false
        val recognized = englishState.recognizedPage() ?: return false
        val current = page ?: return false
        val expectedKey = "${comic.id}:${comic.verifiedLength}:${current.stablePageId ?: "${current.index}:${current.bitmap.generationId}"}"
        if (recognized.pageKey != expectedKey) return false
        val token = englishTokenAt(recognized, x, y) ?: return false
        val selection = if (phrase) englishLinePhrase(recognized, token) ?: token else token
        englishSpeech?.stop(); englishModelNotice = null
        scope.launch { englishAssistant.lookup(selection, recognized.pageKey) }
        return true
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        page?.let { current ->
            ReaderGestureSurface(current.bitmap, current.index, enabled = !busy,
                onPrevious = { if (!busy && current.index > 0) { busy = true; task = scope.launch { load(current.index - 1) } } },
                onNext = { if (!busy && current.index + 1 < current.total) { busy = true; task = scope.launch { load(current.index + 1) } } },
                onToggleControls = { controlsVisible = !controlsVisible },
                onImageTap = { x, y -> selectEnglishToken(x, y, false) },
                onImageLongPress = { x, y -> selectEnglishToken(x, y, true) },
                overlay = { transform, bounds ->
                    if (englishAssistantEnabled && !busy) {
                        englishState.recognizedPage()?.let { recognized ->
                            val selected = (englishState as? EnglishAssistantState.Selected)?.token
                                ?: (englishState as? EnglishAssistantState.LookingUp)?.token
                            EnglishAssistantOverlay(recognized, transform, bounds, selected)
                        }
                    }
                })
        } ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(14.dp))
            Text(if (busy) "正在打开漫画…" else "暂时无法显示漫画", color = Color.White)
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
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { error = "书签未能保存，请重试。" }
                        }
                    }) { Text(if (page?.index?.let { it in bookmarks } == true) "已书签" else "书签", color = Color.White) }
                }
                Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(horizontal = 12.dp)) {
                    TextButton(enabled = page != null && !busy, modifier = Modifier.testTag("toggle-english-assistant"), onClick = {
                        englishAssistantEnabled = !englishAssistantEnabled
                        controlsVisible = false
                        if (!englishAssistantEnabled) {
                            englishAssistant.disable(); englishSpeech?.stop(); englishModelTask?.cancel()
                        }
                    }) { Text(if (englishAssistantEnabled) "关闭英文助手" else "英文助手", color = if (englishAssistantEnabled) Color(0xFFFFD166) else Color.White) }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .76f)).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = ArchiveRed)
                    error?.let { ErrorText(it) }
                    notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.LightGray, modifier = Modifier.padding(vertical = 4.dp)) }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy && (page?.index ?: 0) > 0, modifier = Modifier.weight(1f), onClick = { task = scope.launch { load((page?.index ?: 0) - 1) } }) { Text("上一页") }
                        Button(enabled = !busy && page != null && page!!.index + 1 < page!!.total, modifier = Modifier.weight(1f), onClick = { task = scope.launch { load(page!!.index + 1) } }) { Text("下一页") }
                        if (busy) OutlinedButton(onClick = { task?.cancel() }) { Text("取消") }
                        else if (error != null && session != null) OutlinedButton(onClick = { task = scope.launch { load(requestedIndex) } }) { Text("重试") }
                    }
                }
            }
        }

        if (englishAssistantEnabled && !busy) {
            Column(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.Bottom,
            ) {
                when (englishState) {
                    EnglishAssistantState.Recognizing -> EnglishAssistantStatus(englishState)
                    is EnglishAssistantState.Failed -> EnglishAssistantStatus(englishState)
                    is EnglishAssistantState.Ready -> EnglishAssistantStatus(englishState)
                    else -> Unit
                }
                EnglishAssistantCard(englishState, speechReady, downloadingEnglishModel, englishModelNotice,
                    onSpeak = { text ->
                        if (englishSpeech?.speak(text) != true) englishModelNotice = "本机语音暂不可用，请在系统文字转语音设置中检查英文语音。"
                    },
                    onDownloadModel = { wifiOnly ->
                        if (!downloadingEnglishModel) englishModelTask = scope.launch {
                            downloadingEnglishModel = true; englishModelNotice = "正在下载语言模型…"
                            try {
                                englishDictionary.downloadModel(wifiOnly)
                                englishModelNotice = "语言模型已就绪，后续对白辅助在手机上处理。"
                                (englishState as? EnglishAssistantState.Selected)?.let { selected ->
                                    englishAssistant.lookup(selected.token, selected.page.pageKey)
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { englishModelNotice = "语言模型未能下载，稍后重试。内置词典释义仍可使用。" }
                            finally { downloadingEnglishModel = false }
                        }
                    },
                    onPhrase = { phrase ->
                        val expectedPage = englishState.recognizedPage()?.pageKey
                        englishSpeech?.stop()
                        scope.launch { if (expectedPage != null) englishAssistant.lookup(phrase, expectedPage) }
                    },
                    onDismiss = { englishSpeech?.stop(); englishAssistant.dismissSelection() },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun CacheScreen(repository: ReaderRepository, onOpenTest: (DemoComic) -> Unit) {
    var snapshot by remember { mutableStateOf<CacheSnapshot?>(null) }
    var endpoint by remember { mutableStateOf(repository.baseUrl) }
    var result by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeveloper by remember { mutableStateOf(false) }
    var showStorage by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var preload by remember { mutableStateOf(repository.prefetchEnabled) }
    var keepAwake by remember { mutableStateOf(repository.keepScreenOn) }
    var allowMobile by remember { mutableStateOf(repository.allowMobileDownloads) }
    LaunchedEffect(showDeveloper) {
        if (BuildConfig.DEBUG && showDeveloper) {
            try { snapshot = repository.cacheSnapshot() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "暂时无法读取存储状态。" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("阅读", style = MaterialTheme.typography.titleMedium, color = ArchiveRed)
        WhiteCard {
            SettingsToggleRow("连续翻页加速", "提前准备后续页面，让翻页更顺畅。", preload, "setting-prefetch") {
                preload = it; repository.prefetchEnabled = it
            }
            SettingsToggleRow("阅读时屏幕常亮", "仅在阅读漫画时保持屏幕亮起。", keepAwake, "setting-keep-screen-on") {
                keepAwake = it; repository.keepScreenOn = it
            }
        }
        Text("下载与存储", style = MaterialTheme.typography.titleMedium, color = ArchiveRed)
        WhiteCard {
            SettingsToggleRow("默认允许流量下载", "影响新下载和继续下载的默认选择，已排队任务保持原选择。", allowMobile, "setting-mobile-data") {
                allowMobile = it; repository.allowMobileDownloads = it
            }
        }
        OfflineDownloadsCard(repository)
        WhiteCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("存储管理", fontWeight = FontWeight.Bold)
                    Text("临时内容自动整理，离线漫画单独保留。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                TextButton(modifier = Modifier.testTag("open-storage-management"), onClick = { showStorage = true }) { Text("管理") }
            }
        }
        WhiteCard {
            TextButton(onClick = { showAbout = true }, modifier = Modifier.fillMaxWidth()) { Text("关于与版本") }
            Text("版本 ${BuildConfig.VERSION_NAME}", color = Color.Gray, style = MaterialTheme.typography.bodySmall,
                modifier = if (BuildConfig.DEBUG) Modifier.testTag("settings-version").combinedClickable(onClick = {}, onLongClick = { showDeveloper = !showDeveloper }) else Modifier.testTag("settings-version"))
        }
        if (BuildConfig.DEBUG && showDeveloper) WhiteCard {
            Text("开发测试（仅测试漫画需要电脑）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { showDeveloper = false }) { Text("收起开发验证") }
            Text("硬上限 500,000,000 字节\n已提交：${snapshot?.committedBytes ?: "—"} 字节\n未提交（预留+临时）：${snapshot?.reservedBytes ?: "—"} 字节\n被读取任务占用：${snapshot?.pinnedEntries ?: "—"} 项", style = MaterialTheme.typography.bodySmall)
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
        if (BuildConfig.DEBUG && showDeveloper) WhiteCard {
            Text("测试漫画", fontWeight = FontWeight.Bold)
            Text("目录、书架和真实来源不使用上述电脑地址。只有以下自建测试漫画依赖测试服务器。", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            repository.comics.forEach { comic -> TextButton(onClick = { onOpenTest(comic) }) { Text(comic.title) } }
        }
    }
    if (showStorage) SettingsStorageDialog(repository) { showStorage = false }
    if (showAbout) androidx.compose.material3.AlertDialog(onDismissRequest = { showAbout = false },
        title = { Text("关于观察者漫格") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("版本 ${BuildConfig.VERSION_NAME}")
            Text("个人漫画书库与阅读器。")
            Text("书架、阅读进度与书签保存在本机。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { showAbout = false }) { Text("关闭") } })
}

@Composable
private fun SettingsToggleRow(title: String, description: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(description, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

private fun readerErrorMessage(failure: Exception): String = when (failure) {
    is ComicSourceException -> failure.message ?: "漫画来源暂时无法访问，请稍后重试。"
    is ArchiveException -> when (failure.code) {
        ArchiveError.CANCELLED -> "已取消读取。"
        ArchiveError.NETWORK, ArchiveError.HTTP_ERROR -> "暂时无法加载，请检查网络后重试。"
        ArchiveError.OBJECT_CHANGED -> "漫画来源已更新，请返回详情重新打开。"
        ArchiveError.CORRUPT_ARCHIVE, ArchiveError.CRC_MISMATCH, ArchiveError.INVALID_LENGTH -> "漫画文件可能已损坏，请重新打开后重试。"
        ArchiveError.UNSUPPORTED_ARCHIVE, ArchiveError.UNSUPPORTED_COMPRESSION, ArchiveError.UNSUPPORTED_IMAGE -> "当前版本暂不支持这部漫画的文件格式。"
        ArchiveError.LIMIT_EXCEEDED -> "这部漫画的文件或页面过大，暂时无法打开。"
        ArchiveError.RANGE_NOT_SUPPORTED, ArchiveError.INVALID_CONTENT_RANGE, ArchiveError.IDENTITY_REQUIRED -> "此漫画来源暂时无法稳定读取，请稍后重试。"
    }
    is SecurityException -> "无法访问本地文件，请重新导入漫画。"
    else -> "暂时无法打开漫画，请返回后重试。"
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
