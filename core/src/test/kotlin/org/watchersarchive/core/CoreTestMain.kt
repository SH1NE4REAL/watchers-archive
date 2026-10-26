package org.watchersarchive.core

/** One explicit JVM verification entry point used when Gradle/JUnit are unavailable. */
fun main(args: Array<String>) {
    val baseUrl = args.firstOrNull() ?: "http://127.0.0.1:8765"
    println("[core] reader integration: $baseUrl")
    ReaderIntegration.run(baseUrl)
    println("[core] cache and persistence tests")
    runCacheTests { name, test ->
        try {
            test()
            println("PASS $name")
        } catch (t: Throwable) {
            println("FAIL $name: ${t.message}")
            throw t
        }
    }
    println("CORE_TESTS_PASS")
}
