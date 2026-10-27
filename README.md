# 观察者漫格 · Phase 1

当前修复版：**0.3.1**。启动先进入“官方推荐”，底部仅有“官方推荐 / 我的书架 / 缓存与设置”。
来源条目详情只有“开始阅读”和“加入书架”；正文检查在后台执行，HTML、CBR、损坏文件和缺少强 ETag 的来源不会进入阅读器。
来源站拒绝访问时保留本地快照，真实来源不依赖电脑测试 IP。大事件专题是有来源入口的检索集合，不能视为已下载、完整或官方排序的漫画合集。

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

## Dependency-free core verification

The pure Kotlin reader and cache tests can also be run without Gradle after the
Kotlin compiler jars are available. The following command is the exact standalone
entry point used during Phase 1 verification:

```powershell
$src = (Get-ChildItem core/src/main/kotlin,core/src/test/kotlin -Recurse -Filter *.kt | % FullName)
$cp = (Resolve-Path .tools/kotlin/*.jar | % Path) -join [IO.Path]::PathSeparator
java -cp $cp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -jvm-target 17 `
  -classpath .tools/kotlin/kotlin-stdlib-2.0.21.jar -d core/build/core-tests.jar $src
$run = (Resolve-Path core/build/core-tests.jar,.tools/kotlin/*.jar | % Path) -join [IO.Path]::PathSeparator
java -cp $run org.watchersarchive.core.CoreTestMainKt http://127.0.0.1:8765
```

The runner prints every Range request and checks standard ZIP, ZIP64 offsets above
4 GiB, malformed responses, cancellation, CRC/compression/path errors, the cache
hard limit, pinning/LRU, crash cleanup, and persistent progress/bookmarks/notes.
