plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

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
        manifestPlaceholders["YANDEX_CLIENT_ID"] = providers.gradleProperty("YANDEX_CLIENT_ID").orElse("unset").get()
    }

    buildFeatures {
        buildConfig = true
        compose = true
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
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
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}
