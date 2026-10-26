plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
tasks.register<JavaExec>("verifyCore") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.watchersarchive.core.CoreTestMainKt")
    args(providers.gradleProperty("testServer").getOrElse("http://127.0.0.1:8765"))
}
