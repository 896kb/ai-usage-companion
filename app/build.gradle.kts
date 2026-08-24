plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kb896.aiusage"
    compileSdk = 36

    // buildConfig: BuildConfig.DEBUG gates the diagnostics section — see MainActivity.
    buildFeatures { aidl = true; buildConfig = true }

    defaultConfig {
        applicationId = "dev.kb896.aiusage"
        minSdk = 34
        targetSdk = 36
        // Version comes from the release tag when CI is building one, so a published APK
        // cannot go on claiming an old number while the tags move past it. Locally it
        // stays <version>-dev, which is a useful thing to see in an installed build.
        //
        // This is the ONLY place a version is written down. Everything that needs to say
        // what it is — the User-Agent this app sends, and anything added later — reads
        // BuildConfig.VERSION_NAME rather than repeating the digits. Four User-Agent
        // headers were still introducing themselves as 0.1 long after this line had
        // moved on, which is the drift the comment above was already warning about.
        //
        // versionCode must increase for every update Android will accept, and a tag name
        // does not give a number to derive one from. CI_PIPELINE_IID is monotonic per
        // project, which is exactly the guarantee needed and nothing more.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("CI_COMMIT_TAG")?.removePrefix("v") ?: "0.6.3-dev"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /**
     * Release signing, from the environment — never from a file in this repository.
     *
     * This used to be `signingConfigs.getByName("debug")`, which is fine for a build you
     * install on your own phone over a cable and actively wrong for one you hand to
     * anyone else: the debug key is not a secret, so anyone can forge an update for it.
     *
     * Absent credentials produce an UNSIGNED release rather than a debug-signed one. An
     * unsigned APK refuses to install and says so; a debug-signed one installs happily
     * and is the thing we are trying not to publish.
     */
    val releaseStore = System.getenv("KEYSTORE_PATH")
    if (releaseStore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseStore)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Explicitly NOT debuggable, and explicitly not the debug key. The token
            // store is only as private as the app's uid: `run-as` on a debuggable build
            // runs AS that uid, so it can read the encrypted tokens and use the very
            // Keystore key that encrypted them.
            isDebuggable = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // ComponentActivity + lifecycleScope — MainActivity collects a StateFlow (Task 17).
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // RingGeometryTest needs a real android.os.Bundle (put/get/containsKey) — the plain
    // JVM mockable-android.jar throws "not mocked" for it, same trap as org.json (see
    // UsageParser.kt). Scoped to that one test class via @RunWith(RobolectricTestRunner).
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
