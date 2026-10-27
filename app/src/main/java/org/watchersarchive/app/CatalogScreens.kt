package org.watchersarchive.app

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun DiscoveryScreen(
    repository: ReaderRepository,
    searchState: DiscoverySearchState = remember { DiscoverySearchState() },
    onOpen: (CatalogSourceRecord) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(emptyList<CatalogSourceRecord>()) }
    var guides by remember { mutableStateOf(emptyList<OfficialGuide>()) }
    var characters by remember { mutableStateOf(emptyList<OfficialCharacterProfile>()) }
    var query by searchState::query
    var results by searchState::results
    var refreshing by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchPage by searchState::page
    var moreResults by searchState::moreResults
    var notice by remember { mutableStateOf<String?>(null) }
    var searchNotice by searchState::notice
    var officialNotice by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<RecommendationCollection?>(null) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val catalogueFocus = remember { FocusRequester() }
    fun clearSearch() {
        searchState.clear()
        // Keep focus inside this screen on an explicit non-editable target.
        // clearFocus can let Android restore the first TextField in non-touch mode.
        catalogueFocus.requestFocus()
        keyboard?.hide()
        scope.launch { searchState.listState.scrollToItem(0) }
    }
    BackHandler(enabled = query.isNotBlank()) { clearSearch() }

    suspend fun loadSaved() {
        try {
            records = repository.getComicsRecords().filter { it.source == "getcomics" }
            guides = repository.savedOfficialGuides()
            characters = repository.savedOfficialCharacters()
            if (query.isNotBlank() && results.isEmpty()) results = records.filter { it.title.contains(query.trim(), true) }
        } catch (c: CancellationException) { throw c }
        catch (_: Exception) { notice = "暂时无法读取保存的目录，可稍后重试。" }
    }
    suspend fun refreshArticles() {
        refreshing = true; notice = null
        try { records = repository.refreshGetComics() }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { notice = "暂时无法更新，已保留上次保存的漫画。" }
        finally { refreshing = false }
    }
    LaunchedEffect(Unit) { loadSaved() }
    LaunchedEffect(query) {
        val requestedQuery = query.trim()
        if (searchState.completedQuery == requestedQuery) return@LaunchedEffect
        searchState.listState.scrollToItem(0)
        searchNotice = null
        searchPage = 1; moreResults = false
        if (query.trim().length < 2) { results = emptyList(); searching = false; return@LaunchedEffect }
        results = records.filter { it.title.contains(query.trim(), true) }
        delay(650)
        searching = true
        try { results = repository.searchGetComics(query); moreResults = results.size == 30 }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { searchNotice = "在线搜索暂时不可用，显示已保存的匹配条目。" }
        finally { searching = false }
        searchState.completedQuery = requestedQuery
    }

    Box(Modifier.fillMaxSize().focusRequester(catalogueFocus).focusable().testTag("discovery-focus-anchor")) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 18.dp).testTag("discovery-list"),
        state = searchState.listState,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().testTag("catalogue-search"), singleLine = true,
                placeholder = { Text("搜索漫画、系列或大事件") }, shape = RoundedCornerShape(16.dp),
                leadingIcon = if (query.isNotBlank()) ({
                    IconButton(onClick = ::clearSearch, modifier = Modifier.testTag("search-back").semantics { contentDescription = "返回推荐" }) {
                        Text("‹", style = MaterialTheme.typography.headlineSmall)
                    }
                }) else null,
                trailingIcon = if (query.isNotBlank()) ({
                    IconButton(onClick = ::clearSearch, modifier = Modifier.testTag("search-clear").semantics { contentDescription = "清除搜索" }) {
                        Text("×", style = MaterialTheme.typography.titleLarge)
                    }
                }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { catalogueFocus.requestFocus(); keyboard?.hide() }),
            )
        }
        if (query.isNotBlank()) {
            item { Text(if (searching) "正在搜索…" else "搜索结果", style = MaterialTheme.typography.titleMedium) }
            item { Text("检索 GetComics 漫威分类，结果受来源收录范围限制。", color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            searchNotice?.let { item { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
            items(results, key = { it.externalId }) { ComicSourceRow(it, repository) { onOpen(it) } }
            if (moreResults) item {
                TextButton(enabled = !searching, onClick = { scope.launch {
                    val requestedQuery = query
                    val next = searchPage + 1
                    searching = true
                    try {
                        val batch = repository.searchGetComics(requestedQuery, next)
                        if (query == requestedQuery) {
                            results = (results + batch).distinctBy { it.externalId }
                            searchPage = next; moreResults = batch.size == 30
                        }
                    } catch (c: CancellationException) { throw c }
                    catch (_: Exception) { searchNotice = "下一页暂时无法加载，请稍后重试。" }
                    finally { searching = false }
                } }) { Text("加载更多") }
            }
            if (results.isEmpty() && !searching) item { Text("没有匹配的条目。", color = Color.Gray) }
        } else {
            item { Text("大事件专题", style = MaterialTheme.typography.titleLarge) }
            items(recommendationCollections, key = { "collection:${it.id}" }) { collection ->
                EventCollectionCard(collection, records, repository) { source = collection }
            }
            if (guides.isNotEmpty() || characters.isNotEmpty()) {
                item { Text("官方指南与角色", style = MaterialTheme.typography.titleLarge) }
                items(guides, key = { it.canonicalUrl }) { guide ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(guide.title, style = MaterialTheme.typography.titleMedium)
                            guide.description?.let { Text(it, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
                            Text("${guide.sections.sumOf { it.issues.size }} 个官方页面条目", color = Color.Gray)
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(guide.canonicalUrl))) }) { Text("查看官方指南") }
                        }
                    }
                }
                items(characters, key = { it.externalSlug }) { c ->
                    Card(onClick = { query = c.name }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(16.dp)) { Text(c.name, style = MaterialTheme.typography.titleMedium); c.overview?.let { Text(it, maxLines = 3) } }
                    }
                }
            }
            item {
                TextButton(onClick = { scope.launch {
                    officialNotice = "正在更新官方资料…"
                    try { repository.refreshOfficialGuide(); guides = repository.savedOfficialGuides(); officialNotice = null }
                    catch (c: CancellationException) { throw c }
                    catch (_: Exception) { officialNotice = "官方站暂不可用，专题入口和已保存资料仍可查看。" }
                    try { repository.refreshOfficialCharacter(); characters = repository.savedOfficialCharacters() }
                    catch (c: CancellationException) { throw c }
                    catch (_: Exception) { /* Independent source: do not hide the article list. */ }
                } }) { Text("更新官方资料") }
                officialNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近更新", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(enabled = !refreshing, onClick = { scope.launch { refreshArticles() } }) { Text(if (refreshing) "更新中…" else "刷新") }
                }
                notice?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
            }
            items(records, key = { it.externalId }) { ComicSourceRow(it, repository) { onOpen(it) } }
            if (records.isEmpty()) item { Text("点击刷新获取漫画。无需连接电脑测试服务器。", color = Color.Gray) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    }
    source?.let { collection ->
        AlertDialog(onDismissRequest = { source = null }, title = { Text(collection.title) },
            text = { Column { Text(collection.description); Spacer(Modifier.height(10.dp)); Text(collection.sourceLabel, color = Color.Gray); Text(collection.sourceUrl, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = { TextButton(onClick = { query = collection.searchTerm; source = null }) { Text("查看相关漫画") } },
            dismissButton = { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(collection.sourceUrl))) }) { Text("官方来源") } })
    }
}

@Composable
internal fun ShelfScreen(repository: ReaderRepository, onSource: (CatalogSourceRecord) -> Unit) {
    val scope = rememberCoroutineScope()
    var names by remember { mutableStateOf(emptyList<String>()) }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf(emptyList<CatalogSourceRecord>()) }
    var memberships by remember { mutableStateOf(emptyMap<String, Set<String>>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var actionBook by remember { mutableStateOf<CatalogSourceRecord?>(null) }
    var showMove by remember { mutableStateOf(false) }
    var deleteFolder by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importNotice by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        names = repository.shelfNames()
        records = repository.getComicsRecords()
        memberships = names.associateWith { repository.shelfItems(it) }
    }
    val pickComic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; error = null
            try { val record = repository.importLocalComic(uri.toString()); reload(); importNotice = "已导入：${record.title}" }
            catch (c: CancellationException) { throw c }
            catch (e: Exception) { error = e.message ?: "此文件暂时无法读取，请选择本地 CBZ 或非固实 RAR4。" }
            finally { importing = false }
        }
    }
    LaunchedEffect(Unit) {
        try { reload() }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { error = "无法加载书架，请稍后重试。" }
    }
    BackHandler(enabled = selectedFolder != null) { selectedFolder = null }
    val customFolders = names.filter { it != "默认书架" }
    val groupedIds = customFolders.flatMap { memberships[it].orEmpty() }.toSet()
    val allIds = memberships.values.flatten().toSet()
    val visibleIds = selectedFolder?.let { memberships[it].orEmpty() } ?: (allIds - groupedIds)
    val books = records.filter { it.externalId in visibleIds }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp).testTag("shelf-grid"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectedFolder != null) TextButton(onClick = { selectedFolder = null }) { Text("‹ 书架") }
                Text(selectedFolder ?: "我的书架", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                if (selectedFolder == null) TextButton(onClick = { showCreate = true }) { Text("新建分类") }
                if (selectedFolder != null) TextButton(enabled = !updating, onClick = { deleteFolder = selectedFolder }) { Text("删除分类") }
                if (selectedFolder == null) TextButton(enabled = !importing, onClick = { pickComic.launch(arrayOf("*/*")) }) { Text(if (importing) "导入中…" else "导入") }
            }
        }
        error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error) } }
        importNotice?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = Color.Gray, style = MaterialTheme.typography.bodySmall) } }
        if (selectedFolder == null) {
            items(customFolders, key = { "folder:$it" }) { name ->
                val memberRecords = records.filter { it.externalId in memberships[name].orEmpty() }
                ShelfFolderTile(name, memberRecords, repository, onLongClick = { deleteFolder = name }) { selectedFolder = name }
            }
        }
        items(books, key = { it.externalId }) { record ->
            ShelfBookTile(record, repository, onLongClick = { actionBook = record }) { onSource(record) }
        }
        if (books.isEmpty() && (selectedFolder != null || customFolders.isEmpty())) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(if (selectedFolder == null) "书架还没有漫画。在漫画详情页点击“加入书架”即可。" else "这个分类还没有漫画。可在漫画详情页加入此分类。", color = Color.Gray)
            }
        }
    }
    if (showCreate) AlertDialog(onDismissRequest = { showCreate = false }, title = { Text("新建分类") }, text = {
        Column { OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("分类名称") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }, confirmButton = { TextButton(enabled = newName.isNotBlank() && newName.trim() != "全部" && !creating, onClick = { scope.launch {
        creating = true
        try { repository.createShelf(newName); reload(); newName = ""; error = null; showCreate = false }
        catch (c: CancellationException) { throw c }
        catch (e: Exception) { error = e.message ?: "创建失败，请重试。" }
        finally { creating = false }
    } }) { Text("创建") } }, dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } })

    deleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { if (!updating) deleteFolder = null },
            title = { Text("删除分类“$folder”？") },
            text = { Text("分类内 ${memberships[folder].orEmpty().size} 本漫画会回到书架。只删除分类，不删除漫画文件、阅读进度、书签或笔记。") },
            confirmButton = {
                TextButton(enabled = !updating, modifier = Modifier.testTag("confirm-delete-shelf"), onClick = {
                    scope.launch {
                        updating = true
                        try {
                            repository.deleteShelf(folder)
                            reload()
                            if (selectedFolder == folder) selectedFolder = null
                            deleteFolder = null
                            error = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "分类删除未完成，请稍后重试。"; deleteFolder = null }
                        finally { updating = false }
                    }
                }) { Text(if (updating) "删除中…" else "删除分类", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !updating, onClick = { deleteFolder = null }) { Text("取消") } },
        )
    }

    actionBook?.let { book ->
        fun performShelfChange(change: suspend () -> Unit) {
            scope.launch {
                updating = true
                try { change(); reload(); actionBook = null; showMove = false; error = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "书架操作未完成，请稍后重试。"; actionBook = null; showMove = false }
                finally { updating = false }
            }
        }
        if (showMove) {
            AlertDialog(onDismissRequest = { if (!updating) { showMove = false; actionBook = null } },
                title = { Text("移动到分类") }, text = {
                    Column {
                        names.filter { it != selectedFolder }.forEach { name ->
                            TextButton(modifier = Modifier.testTag("shelf-move-target:$name"), enabled = !updating, onClick = {
                                performShelfChange { repository.moveToShelf(selectedFolder, name, book.externalId) }
                            }) { Text(if (name == "默认书架") "直接放在书架" else name) }
                        }
                    }
                }, confirmButton = { TextButton(enabled = !updating, onClick = { showMove = false; actionBook = null }) { Text("取消") } })
        } else {
            AlertDialog(onDismissRequest = { if (!updating) actionBook = null },
                title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column {
                        selectedFolder?.let { current ->
                            TextButton(enabled = !updating, onClick = {
                                performShelfChange { repository.moveToShelf(current, "默认书架", book.externalId) }
                            }) { Text("移出当前分类") }
                        }
                        TextButton(enabled = !updating, onClick = { showMove = true }) { Text("移动到其他分类") }
                        TextButton(enabled = !updating, onClick = {
                            performShelfChange { repository.removeFromShelf("全部", book.externalId) }
                        }) { Text("从书架移除", color = MaterialTheme.colorScheme.error) }
                        Text("仅调整书架，不删除漫画文件、阅读进度或笔记。", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }, confirmButton = { TextButton(enabled = !updating, onClick = { actionBook = null }) { Text("取消") } })
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ShelfBookTile(record: CatalogSourceRecord, repository: ReaderRepository, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("shelf-book:${record.externalId}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CoverImage(record.coverUrl, repository, Modifier.fillMaxWidth().aspectRatio(0.68f))
        Text(record.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ShelfFolderTile(name: String, records: List<CatalogSourceRecord>, repository: ReaderRepository, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).testTag("shelf-folder:$name"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth().aspectRatio(0.68f), shape = RoundedCornerShape(6.dp), color = Color(0xFFE9E4DF), shadowElevation = 1.dp) {
            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("分类 · ${records.size} 本", color = Color(0xFF7D6654), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(2) { row ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            repeat(2) { column ->
                                val record = records.getOrNull(row * 2 + column)
                                if (record != null) CoverImage(record.coverUrl, repository, Modifier.weight(1f).fillMaxHeight())
                                else Box(Modifier.weight(1f).fillMaxHeight().background(Color.White.copy(alpha = 0.55f), RoundedCornerShape(3.dp)))
                            }
                        }
                    }
                }
            }
        }
        Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun EventCollectionCard(collection: RecommendationCollection, records: List<CatalogSourceRecord>, repository: ReaderRepository, onClick: () -> Unit) {
    val matchingCover = records.firstOrNull { it.title.contains(collection.searchTerm, ignoreCase = true) && !it.coverUrl.isNullOrBlank() }?.coverUrl
    var eventCover by remember(collection.id, matchingCover) { mutableStateOf(collection.coverUrl ?: matchingCover) }
    LaunchedEffect(collection.id, matchingCover) {
        if (eventCover == null) {
            try { eventCover = repository.eventCover(collection) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Existing covers and event metadata remain visible offline. */ }
        }
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("event-card:${collection.id}"), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(eventCover, repository, Modifier.width(92.dp).height(136.dp))
            Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(collection.title, style = MaterialTheme.typography.titleLarge)
                Text(collection.description, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("查看相关漫画 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
internal fun ArticleDetailScreen(record: CatalogSourceRecord, repository: ReaderRepository, onRead: (DemoComic) -> Unit) {
    val scope = rememberCoroutineScope()
    var preparing by remember(record.externalId) { mutableStateOf(false) }
    var message by remember(record.externalId) { mutableStateOf<String?>(null) }
    var chooseShelf by remember(record.externalId) { mutableStateOf(false) }
    var shelfNames by remember { mutableStateOf(emptyList<String>()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(verticalAlignment = Alignment.Top) {
            CoverImage(record.coverUrl, repository, Modifier.width(110.dp).height(164.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(record.title, style = MaterialTheme.typography.titleLarge)
                record.sizeLabel?.let { Text(it, color = Color.Gray) }
                record.yearLabel?.let { Text(it, color = Color.Gray) }
            }
        } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = !preparing, modifier = Modifier.weight(1f), onClick = { scope.launch {
                    preparing = true; message = "正在打开漫画…"
                    try { onRead(repository.prepareSourceReading(record)); message = null }
                    catch (c: CancellationException) { throw c }
                    catch (e: Exception) { message = e.message ?: "暂时无法打开该漫画。" }
                    finally { preparing = false }
                } }) { Text(if (preparing) "准备中…" else "开始阅读") }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { scope.launch {
                    try { shelfNames = repository.shelfNames(); chooseShelf = true }
                    catch (c: CancellationException) { throw c }
                    catch (_: Exception) { message = "暂时无法读取书架。" }
                } }) { Text("加入书架") }
            }
            if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            message?.let { Text(it, Modifier.padding(top = 10.dp), color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
        }
        item { DownloadComicAction(record, repository) }
        item { Text("简介", style = MaterialTheme.typography.titleMedium); Text(record.synopsis ?: "来源暂未提供简介。", Modifier.padding(top = 8.dp)) }
        item {
            Text("来源：${if (record.source == "local") "手机本地文件" else "GetComics"}", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
        }
    }
    if (chooseShelf) AlertDialog(onDismissRequest = { chooseShelf = false }, title = { Text("加入书架") }, text = {
        Column { shelfNames.forEach { name -> TextButton(onClick = { scope.launch {
            try { repository.addToShelf(name, record.externalId); message = "已加入$name"; chooseShelf = false }
            catch (c: CancellationException) { throw c }
            catch (_: Exception) { message = "加入书架失败，请重试。"; chooseShelf = false }
        } }) { Text(name) } } }
    }, confirmButton = { TextButton(onClick = { chooseShelf = false }) { Text("取消") } })
}

@Composable
internal fun ComicSourceRow(record: CatalogSourceRecord, repository: ReaderRepository, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(record.coverUrl, repository, Modifier.width(66.dp).height(96.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(record.title, style = MaterialTheme.typography.titleMedium, maxLines = 3)
                Text(record.sizeLabel ?: "大小未知", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun CoverImage(url: String?, repository: ReaderRepository, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf(url?.let(repository::peekCover)) }
    LaunchedEffect(url) {
        bitmap = url?.let(repository::peekCover)
        try { bitmap = url?.let { repository.loadCover(it) } }
        catch (c: CancellationException) { throw c }
        catch (_: Exception) { /* A failed image must not crash the catalogue. */ }
    }
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = Color(0xFFF3E6E7)) {
        bitmap?.let { Image(it.asImageBitmap(), "漫画封面", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("暂无封面", color = Color.Gray, style = MaterialTheme.typography.labelSmall) }
    }
}
