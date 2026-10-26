# 观察者漫格 · Phase 1

This is an Android-first prototype for validating remote CBZ/ZIP64 page reads and
the 500,000,000-byte temporary-cache boundary. Every comic shown by the app and
every archive served by the fixture server is synthetic test content; it is not a
Marvel catalogue or Marvel artwork.

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
