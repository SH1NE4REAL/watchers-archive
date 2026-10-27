plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.github.junrar:junrar:7.5.5")
}

// Android consumes the packaged JAR. Directory-based dex transforms otherwise
// mix the canonical Chinese path and its ASCII Junction on this Windows host.
configurations.create("androidArchive") {
    isCanBeConsumed = true
    isCanBeResolved = false
    extendsFrom(configurations["implementation"], configurations["runtimeOnly"])
    outgoing.artifact(tasks.named("jar"))
}
tasks.register<JavaExec>("verifyCore") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.watchersarchive.core.CoreTestMainKt")
    args(providers.gradleProperty("testServer").getOrElse("http://127.0.0.1:8765"))
}
