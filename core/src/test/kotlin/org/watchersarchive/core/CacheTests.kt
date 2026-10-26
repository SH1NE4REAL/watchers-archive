package org.watchersarchive.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private inline fun <reified T : Throwable> expectCacheFailure(action: () -> Unit) {
    try { action() } catch (error: Throwable) {
        check(error is T) { "Expected ${T::class.java.simpleName}, got $error" }
        return
    }
    error("Expected ${T::class.java.simpleName}")
}

private fun cacheTestDirectory(action: (File) -> Unit) {
    val directory = Files.createTempDirectory("watchers-cache-test-").toFile()
    val absolute = directory.canonicalFile
    try { action(directory) } finally {
        // This cleanup only ever targets the directory created by this function.
        check(directory.canonicalFile == absolute && directory.name.startsWith("watchers-cache-test-"))
        check(directory.deleteRecursively()) { "Cannot remove test directory $directory" }
    }
}

/** Dependency-free tests shared by the Gradle and standalone JVM runners. */
fun runCacheTests(test: (String, () -> Unit) -> Unit) {
    test("cache: production hard limit cannot be increased") {
        cacheTestDirectory { root ->
            expectCacheFailure<IllegalArgumentException> { CacheManager(root, CacheManager.HARD_LIMIT + 1) }
            CacheManager(root).use { cache ->
                check(cache.snapshot().limitBytes == 500_000_000L)
                expectCacheFailure<CacheFullException> { cache.beginWrite("too-big", "A", 500_000_001L) }
                // Reserve almost 0.5 GB without writing a large file or allocating a large array.
                // Leave less than one allocation unit after the fixed directory margin;
                // the next one-byte reservation must be rejected atomically.
                val outputSize = 500_000_000L - 4_096L - 4_096L + 1L
                cache.beginWrite("production-boundary", "A", outputSize).use {
                    check(cache.snapshot().accountedBytes <= 500_000_000L)
                    expectCacheFailure<CacheFullException> { cache.beginWrite("overflow", "B", 1) }
                    check(root.listFiles()!!.filter { it.name.endsWith(".part") }.sumOf { it.length() } == 0L)
                }
                check(cache.snapshot().reservedBytes == 0L)
            }
        }
    }
    test("cache: atomic committed bytes are readable and pinned") {
        cacheTestDirectory { root ->
            CacheManager(root, 32_768).use { cache ->
                val payload = ByteArray(5_000) { (it % 251).toByte() }
                cache.put("page-0", "comic-A", payload).use { lease ->
                    check(lease.readBytes().contentEquals(payload))
                    check(cache.snapshot().pinnedFiles == 1)
                    check(cache.clearUnpinned() == 0L)
                    check(lease.file.exists())
                }
                cache.acquire("page-0", "comic-A")!!.use { check(it.readBytes().contentEquals(payload)) }
                check(cache.clearUnpinned() > 0)
                check(cache.acquire("page-0", "comic-A") == null)
            }
        }
    }
    test("cache: reservation counts partial files and rejects overflow") {
        cacheTestDirectory { root ->
            CacheManager(root, 16_384).use { cache ->
                cache.beginWrite("page", "A", 8_192).use { writer ->
                    check(cache.snapshot().reservedBytes == 8_192L)
                    writer.write(ByteArray(1))
                    val snapshot = cache.snapshot()
                    check(snapshot.temporaryBytes == 4_096L && snapshot.reservedBytes == 4_096L)
                    // The fixed directory margin remains reserved; unused budget is
                    // intentionally available for another write only when its whole
                    // rounded reservation fits.
                    check(snapshot.accountedBytes == 12_288L)
                    check(snapshot.accountedBytes <= snapshot.limitBytes)
                    expectCacheFailure<CacheFullException> { cache.beginWrite("other", "B", 1) }
                    expectCacheFailure<IOException> { writer.write(ByteArray(8_192)) }
                    expectCacheFailure<IOException> { writer.commit() }
                }
                check(cache.snapshot().temporaryBytes == 0L && cache.snapshot().reservedBytes == 0L)
            }
        }
    }
    test("cache: concurrent reservation cannot oversubscribe budget") {
        cacheTestDirectory { root ->
            CacheManager(root, 16_384).use { cache ->
                val executor = Executors.newFixedThreadPool(2)
                val start = CountDownLatch(1)
                val finish = CountDownLatch(1)
                val results = (0..1).map { index ->
                    executor.submit<Boolean> {
                        start.await()
                        try {
                            cache.beginWrite("page-$index", "comic-$index", 8_192).use {
                                finish.await(2, TimeUnit.SECONDS)
                                check(cache.snapshot().accountedBytes <= cache.budgetBytes)
                            }
                            true
                        } catch (_: CacheFullException) { finish.countDown(); false }
                    }
                }
                start.countDown()
                try { check(results.count { it.get(5, TimeUnit.SECONDS) } == 1) }
                finally { finish.countDown(); executor.shutdownNow() }
                check(cache.snapshot().accountedBytes <= cache.budgetBytes)
            }
        }
    }
    test("cache: pinned comic survives per-comic LRU eviction") {
        cacheTestDirectory { root ->
            // Leave only one rounded block free so adding the larger C page
            // must evict the unpinned B comic while pinned A remains.
            CacheManager(root, 20_480).use { cache ->
                val pinned = cache.put("page-1", "A", ByteArray(4_096))
                cache.put("page-2", "A", ByteArray(4_096)).close()
                cache.put("page-1", "B", ByteArray(4_096)).use { check(it.file.setLastModified(1_000)) }
                try {
                    cache.put("page-1", "C", ByteArray(8_192)).close()
                    check(cache.acquire("page-1", "B") == null)
                    cache.acquire("page-2", "A")!!.close()
                    check(pinned.file.exists())
                    check(cache.snapshot().accountedBytes <= cache.budgetBytes)
                } finally { pinned.close() }
            }
        }
    }
    test("cache: cancellation leaves no partial entry or reservation") {
        cacheTestDirectory { root ->
            CacheManager(root, 65_536).use { cache ->
                var checks = 0
                expectCacheFailure<InterruptedException> {
                    cache.put("cancel", "A", ByteArray(32_768)) {
                        if (++checks == 2) throw InterruptedException("test cancellation")
                    }
                }
                check(cache.acquire("cancel", "A") == null)
                val snapshot = cache.snapshot()
                check(snapshot.fileCount == 0 && snapshot.temporaryBytes == 0L && snapshot.reservedBytes == 0L)
                check(root.listFiles()!!.none { it.name.endsWith(".part") })
            }
        }
    }
    test("cache: second manager is rejected and restart recovers orphan") {
        cacheTestDirectory { root ->
            CacheManager(root, 32_768).use { cache ->
                expectCacheFailure<IOException> { CacheManager(root, 32_768) }
                cache.put("saved", "A", ByteArray(3_000) { 7 }).close()
            }
            File(root, "crashed-write.part").writeBytes(ByteArray(7_000))
            CacheManager(root, 32_768).use { cache ->
                check(!File(root, "crashed-write.part").exists())
                check(cache.snapshot().temporaryBytes == 0L)
                cache.acquire("saved", "A")!!.use { check(it.readBytes().all { value -> value == 7.toByte() }) }
            }
        }
    }
    test("cache: lowered restart budget reconciles existing files") {
        cacheTestDirectory { root ->
            CacheManager(root, 65_536).use { cache ->
                repeat(4) { cache.put("page", "$it", ByteArray(8_192)).close() }
            }
            CacheManager(root, 16_384).use { cache -> check(cache.snapshot().accountedBytes <= 16_384) }
        }
    }
    test("library: cache clear and restart preserve catalogue progress bookmarks notes") {
        cacheTestDirectory { root ->
            val cacheDirectory = File(root, "temporary-cache")
            val libraryDirectory = File(root, "persistent-library")
            PersistentLibrary(libraryDirectory).use { library ->
                library.registerComic("standard", "明确标注的测试漫画", "/standard.cbz")
                library.setProgress("standard", 2)
                library.addBookmark("standard", 1)
                library.addBookmark("standard", 2)
                library.removeBookmark("standard", 1)
                library.setNote("standard", 2, "Persistent user note")
                CacheManager(cacheDirectory, 32_768).use { cache ->
                    cache.put("page", "standard", ByteArray(4_096)).close()
                    cache.clearUnpinned()
                    check(library.progress("standard") == 2)
                    check(library.bookmarks("standard") == setOf(2))
                }
            }
            // Reopen new instances, emulating process restart rather than retaining object state.
            PersistentLibrary(libraryDirectory).use { library ->
                check(library.comics().single().title == "明确标注的测试漫画")
                check(library.comics().single().testContent)
                check(library.progress("standard") == 2)
                check(library.bookmarks("standard") == setOf(2))
                check(library.note("standard", 2) == "Persistent user note")
            }
        }
    }
}
