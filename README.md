# 观察者漫格 · Phase 1

当前版本：**0.6.1**。启动先进入“官方推荐”，底部仅有“官方推荐 / 我的书架 / 缓存与设置”。
来源详情提供“开始阅读”“加入书架”和用户主动的“下载整本”。下载完成后优先本地读取，支持暂停、继续和删除下载；已下载正文与临时阅读缓存分开保存。阅读器不再展示缓存、传输字节、日志或调试信息，开发夹具只在 debug 包长按设置页版本号后显示。
正文检查在后台执行，HTML、损坏文件和缺少强 ETag 的远程来源不会进入阅读器或下载任务。支持 ZIP/ZIP64 和普通非固实 RAR4/CBR；RAR5、固实、加密、分卷和 PPM 压缩明确拒绝。
来源站拒绝访问时保留本地快照，真实来源不依赖电脑测试 IP。大事件专题是有来源入口的检索集合，不能视为已下载、完整或官方排序的漫画合集。

0.6.0 的离线存储边界、后台调度、122 项 App 主机测试及设备验证限制见 [验证记录](docs/0.6.0-verification.md)。图内中文覆盖与整本预译仍是设计方案，本版本没有声称实现。
0.6.1 统一首次下载和继续下载的网络选择，明确提供“允许使用手机流量”，并禁止真实漫画退回电脑测试地址。正常目录和正文由手机直接访问公开来源；电脑上的 LAN 地址仅分发安装包/提供开发夹具。新增验证见 [0.6.1 记录](docs/0.6.1-verification.md)。

Windows 的 Gradle test worker 无法正确加载中文路径下的类时，使用指向同一工作区的 ASCII Junction：

```powershell
$alias = "$env:LOCALAPPDATA\Temp\watchers-workspace"
if (!(Test-Path $alias)) { New-Item -ItemType Junction -Path $alias -Target $PWD.Path }
Set-Location $alias
$env:GRADLE_USER_HOME = "$env:LOCALAPPDATA\Temp\gradle-home-phase1"
$env:ANDROID_USER_HOME = "$env:LOCALAPPDATA\Temp\android-user-phase1"
$env:TEMP = "$PWD\.tools\tmp"
$env:TMP = $env:TEMP
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon --max-workers=2
```

这套测试包含 Robolectric Activity/Compose 布局、原生 BitmapFactory、触摸输入、来源解析、真实 loopback Range、持久化和取消租约交接。
报告在 `app/build/reports/tests/testDebugUnitTest/index.html`。它们是主机运行测试；不能替代手机上的 instrumentation 或手动视觉验收。
SDK、Robolectric 运行包、下载缓存和测试生成物均保持 Git 忽略。已有提交与用户数据库不被重置。

This is an Android-first prototype for validating remote CBZ/ZIP64 page reads and
the 500,000,000-byte temporary-cache boundary. Every archive served by the fixture
server is synthetic test content. Source articles in the catalogue come from
GetComics; they are separate from readable files and from official Marvel relations.

## Build prerequisites

- JDK 17.
- Android SDK platform 35 and Build Tools 35.0.0.
- Gradle 8.9 (the project wrapper is pinned to this version).

`tools/bootstrap.py --android` downloads the pinned Android platform, build tools,
platform tools, Kotlin compiler and Gradle distribution into the ignored `.tools`
directory. It does not change global SDK settings or accept Android SDK licences.
If a corporate network blocks one of the downloads, install the same versions by
the normal vendor process and set `local.properties` to the SDK path.

Once the wrapper and dependencies are available:

```powershell
.\gradlew.bat test :app:assembleDebug
```

On the restricted Windows validation host, use an ASCII Gradle home and temporary
directory so the Kotlin daemon and Android tools do not inherit the Chinese project
path:

```powershell
$env:GRADLE_USER_HOME = "$env:LOCALAPPDATA\Temp\gradle-home-phase1"
$env:TEMP = "$PWD\.tools\tmp"
$env:TMP = $env:TEMP
$env:ANDROID_USER_HOME = "$env:LOCALAPPDATA\Temp\android-user-phase1"
.\gradlew.bat test :app:assembleDebug --no-daemon
```

The debug variant uses Android's default local debug signing, with `ANDROID_USER_HOME`
redirected to a writable temporary directory. No release signing key is stored in the
repository. The checked-in wrapper JAR is extracted from
the verified Gradle 8.9 distribution and has SHA-256
`498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`.

The Android instrumentation suite expects the local fixture server and an emulator
or device. Start the server and connect it to the emulator with:

```powershell
python tools/range_server.py --bind 127.0.0.1 --port 8765
adb reverse tcp:8765 tcp:8765
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.testServer=http://127.0.0.1:8765
```

For an Android emulator without `adb reverse`, set the app's test endpoint to
`http://10.0.2.2:8765` on the cache/settings screen. The app refuses to fall back
to a full download when a source returns HTTP 200 to a Range request.

## Core verification

The core now includes Junrar for bounded RAR4 reading, so use Gradle's runtime
classpath rather than the old stdlib-only standalone command. Start the fixture
server, then run:

```powershell
python tools/range_server.py --bind 127.0.0.1 --port 8765
# In a second shell, with the ASCII workspace/build environment above:
.\gradlew.bat :core:verifyCore -PtestServer=http://127.0.0.1:8765 --no-daemon
```

The runner checks standard ZIP, ZIP64 and large offsets, local byte sources,
non-solid RAR4, cancellation, CRC, memory/transfer limits and persistent data.
It never needs a complete multi-GB fixture file.

## 0.5.0 behaviour

- Event collections are full-width vertical cards with sourced cover images.
- The shelf is a three-column cover grid. Named categories appear as cover folders.
- Long-press a category to delete it and return its members to the main shelf.
- Read-ahead caches a rolling window of at most two upcoming pages without advancing progress. Each page is limited to 8MiB packed / 16MiB unpacked and the window to 24MiB unpacked; no extra preview Bitmap is decoded. Cached pages bypass unrelated slow preview HTTP work. The setting can be disabled to reduce speculative traffic. RAR page transfers use fewer bounded ranges.
- Back from search clears the query and returns to recommendations; returning from an article preserves search results and scroll position. The top decoration is original red comic-panel artwork instead of the application title.
- The reader's optional “译文” action offers English OCR with paired Chinese text. Opening it checks only the local model; first use requires explicitly choosing the approximately 30MB language-model download, using Wi-Fi by default. Images and recognized lettering are processed on-device. SDK model storage is independent of the disposable comic-page cache. Closing cancels page processing, although an SDK model download can continue. It does not replace speech bubbles or provide an official Chinese edition. Actual model delivery, physical-phone OCR, lettering order and translation quality still require device validation; controller/UI tests use a fake engine. See `docs/0.5.0-verification.md` for the exact scope.
- Long-press a book to move it between categories, return it to the main shelf or remove
  all shelf memberships. These actions never delete source files, progress, bookmarks or notes.
- Cover loading has a 16MiB bitmap memory budget, shared requests, bounded thumbnails
  and the same 500,000,000-byte disk budget as reading pages. Cached covers are shown
  immediately; the first load still needs network access.
- GetComics' own published HTTP image/download links are canonicalized to HTTPS.
  Only normal public redirects/public download APIs are resolved; login/CAPTCHA/HTML
  interstitials are never bypassed.
- Supported RAR4 files use bounded header/page reads. Initial indexing can take time
  because RAR4 has no ZIP central directory. A 2MiB version-bound block cache retains
  header data separately so validation and opening do not fetch the index twice.
- Validated first-page bytes are reused through the normal CacheManager budget.
- The shelf's Import action opens Android's system document picker. Only the selected
  seekable document is read through persistent URI permission and 64-bit pread; no
  whole archive is copied. Cloud/pipe providers without seek support are rejected.
- Junrar's codec allocation is protected before extraction; PPM compression is rejected
  before allocating its potentially archive-controlled heap. See THIRD_PARTY_NOTICES.md.

One bounded real source check read page 1 of GetComics post 409443 (RAR4):
121 pages, 237,924,661B archive, 2,446,144B transferred including indexing,
1987x3056 JPEG decoded with CRC verification. No full archive or image body was saved.
This is a JVM + real HTTP proof, not physical-phone acceptance.
