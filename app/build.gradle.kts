plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "org.watchersarchive.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "org.watchersarchive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.10.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    // AGP transparently expands assets ending in .gz. These .dict files contain
    // bounded gzip shards and must retain their encoding inside the APK.
    androidResources { noCompress += "dict" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            val localAndroidRuntime = rootProject.file(".tools/robolectric")
            if (localAndroidRuntime.resolve("android-all-instrumented-9-robolectric-4913185-2-i7.jar").isFile &&
                localAndroidRuntime.resolve("android-all-instrumented-15-robolectric-12650502-i7.jar").isFile) {
                it.systemProperty("robolectric.dependency.dir", localAndroidRuntime.absolutePath)
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Keep every supported ABI, but compress bundled OCR/translation libraries
        // for browser-delivered APKs. Android extracts them during installation.
        jniLibs.useLegacyPackaging = true
    }
}
dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("org.json:json:20240303")
    implementation(project(path = ":core", configuration = "androidArchive"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
