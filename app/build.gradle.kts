plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

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

android {
    namespace = "net.inkyquill.pocketeditor"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.inkyquill.pocketeditor"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["YANDEX_CLIENT_ID"] = providers.gradleProperty("YANDEX_CLIENT_ID")
            .orElse(providers.environmentVariable("YANDEX_CLIENT_ID"))
            .orElse("unset")
            .get()
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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.yandex.authsdk)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit.jupiter)
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
