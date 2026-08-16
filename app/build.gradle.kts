plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Naive KEY=VALUE reader for the local-dev `.env` file. Not a general-purpose
// dotenv implementation: no quoting, no escaping, no multiline values. CI
// never relies on this — it supplies YANDEX_CLIENT_ID purely as an OS env
// var from a GitHub secret (.github/workflows/android.yml), so this loader
// only has to cover the flat "YANDEX_CLIENT_ID=..." line developers keep in
// their local, gitignored .env.
val dotEnv: Map<String, String> = rootProject.file(".env")
    .takeIf { it.exists() }
    ?.readLines()
    ?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) return@mapNotNull null
        val key = trimmed.substringBefore("=").trim()
        val value = trimmed.substringAfter("=").trim()
        key to value
    }
    ?.toMap()
    ?: emptyMap()

fun envOrProperty(key: String): Provider<String> =
    providers.gradleProperty(key)
        .orElse(providers.environmentVariable(key))
        .orElse(providers.provider { dotEnv[key] })

val semanticVersionPattern = Regex(
    """(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
)

fun validateVersionName(value: String, source: String): String {
    require(value.matches(semanticVersionPattern)) {
        "$source must be a valid Semantic Version"
    }
    return value
}

val localVersionName = providers.fileContents(rootProject.layout.projectDirectory.file("version.txt"))
    .asText
    .map { validateVersionName(it.trim(), "version.txt") }
val releaseVersionName = providers.environmentVariable("POCKET_EDITOR_VERSION_NAME")
    .map { validateVersionName(it, "POCKET_EDITOR_VERSION_NAME") }
    .orElse(localVersionName)
    .get()
val releaseVersionCode = providers.environmentVariable("POCKET_EDITOR_VERSION_CODE").orNull
    ?.let { value ->
        require(value.matches(Regex("[1-9]\\d*"))) {
            "POCKET_EDITOR_VERSION_CODE must be a positive Android-safe integer"
        }
        value.toLongOrNull()?.takeIf { it <= 2_100_000_000L }?.toInt()
            ?: throw GradleException("Version code must be a positive Android-safe integer")
    }
    ?: 1

val releaseStoreFile = providers.environmentVariable("POCKET_EDITOR_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("POCKET_EDITOR_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("POCKET_EDITOR_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("POCKET_EDITOR_RELEASE_KEY_PASSWORD").orNull
val releaseSigningReady = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

val resolvedYandexClientId: String = envOrProperty("YANDEX_CLIENT_ID").orElse("").get()
val releaseFacingTasks = setOf("assembleRelease", "bundleRelease")
gradle.taskGraph.whenReady {
    val runningReleaseTask = allTasks.any { it.name in releaseFacingTasks }
    if (runningReleaseTask && resolvedYandexClientId.isBlank()) {
        throw GradleException(
            "YANDEX_CLIENT_ID is not set. Add it to .env, pass -PYANDEX_CLIENT_ID=..., " +
                "or set the YANDEX_CLIENT_ID environment variable before running a release build.",
        )
    }
}

android {
    namespace = "net.inkyquill.pocketeditor"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.inkyquill.pocketeditor"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["YANDEX_CLIENT_ID"] = resolvedYandexClientId.ifBlank {
            logger.warn("YANDEX_CLIENT_ID unset — Yandex sign-in will not work in this build")
            "unset"
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.keepDebugSymbols += "**/libandroidx.graphics.path.so"
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }

    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    sourceSets.getByName("test").resources.directories.add(rootProject.file("schemas").path)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.icons.lucide)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.footnotes)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.yandex.authsdk)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
    testImplementation(libs.networknt.json.schema.validator) {
        exclude(group = "tools.jackson.dataformat", module = "jackson-dataformat-yaml")
        exclude(group = "com.ethlo.time", module = "itu")
    }
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
