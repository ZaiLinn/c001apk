import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version follows the shared release line in the Flutter pubspec until
// the Flutter Android host is retired.
val pubspecVersion = rootProject.file("../flutter/pubspec.yaml").readLines()
    .first { it.startsWith("version: ") }.removePrefix("version: ").trim()
val appVersionName = pubspecVersion.substringBefore('+')
val appVersionCode = pubspecVersion.substringAfter('+').toInt()

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.isFile) file.inputStream().use(::load)
}
val storeFilePath = System.getenv("KEYSTORE") ?: keystoreProperties.getProperty("storeFile")
val releaseStoreFile = storeFilePath?.let(::file)
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: System.getenv("REVIA_KS_PASS")
    ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("REVIA_KS_PASS")
    ?: keystoreProperties.getProperty("keyPassword")
val hasReleaseSigning = releaseStoreFile?.isFile == true && !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() && !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "com.zailinn.c001apk"
    compileSdk = 37

    defaultConfig {
        // Preview package: installs next to the Flutter build until feature parity.
        applicationId = "com.zailinn.c001apk.compose"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Preview builds do not offer the Flutter release APKs as updates.
        buildConfigField("boolean", "PREVIEW", "true")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

// Never produce an unsigned or debug-signed release when credentials are missing.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            throw GradleException(
                "Release signing is not configured. Set KEYSTORE, KEY_ALIAS and KEYSTORE_PASSWORD/KEY_PASSWORD " +
                    "(or REVIA_KS_PASS); see docs/DEVELOPMENT.md.",
            )
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.material.kolor)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Screenshots are written only when explicitly requested: -Proborazzi.record=true
    systemProperty("roborazzi.test.record", providers.gradleProperty("roborazzi.record").getOrElse("false"))
    systemProperty("roborazzi.output.dir", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.absolutePath)
    // Optional Maven mirror for the Robolectric Android runtime download.
    providers.gradleProperty("robolectric.repo").orNull?.let { systemProperty("robolectric.dependency.repo.url", it) }
}
