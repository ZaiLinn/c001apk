import java.util.Properties
import java.util.zip.ZipFile
import javax.tools.ToolProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release version lives in gradle.properties; the release workflow checks the tag against it.
val appVersionName = providers.gradleProperty("c001apk.versionName").get()
val appVersionCode = providers.gradleProperty("c001apk.versionCode").get().toInt()

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
        applicationId = "com.zailinn.c001apk"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Only the officially signed release offers GitHub releases as updates.
        buildConfigField("boolean", "UPDATE_CHECK", "false")
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
            buildConfigField("boolean", "UPDATE_CHECK", "true")
        }
        // Optimized like release (R8, not debuggable) but signed with the debug key,
        // so it installs over a debug build to judge real scrolling performance.
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "UPDATE_CHECK", "false")
            matchingFallbacks += "release"
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

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))
}

// Never produce an unsigned or debug-signed release when credentials are missing.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    val signingReady = hasReleaseSigning
    doFirst {
        if (!signingReady) {
            throw GradleException(
                "Release signing is not configured. Set KEYSTORE, KEY_ALIAS and KEYSTORE_PASSWORD/KEY_PASSWORD " +
                    "(or REVIA_KS_PASS); see docs/DEVELOPMENT.md.",
            )
        }
    }
}

// Semantic folding: ONNX Runtime's Java API and the OrtRuntime adapter are
// dexed on their own into the semantic-runtime.jar asset. Keeping them out of
// the app dex means R8 never merges their code into app classes, and the APK
// carries no native libraries: those are downloaded on demand in settings.
val semanticRuntimeAar = configurations.create("semanticRuntimeAar") {
    isTransitive = false
    isCanBeConsumed = false
}

abstract class SemanticRuntimeTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: ConfigurableFileCollection

    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val adapterSources: DirectoryProperty

    @get:CompileClasspath
    abstract val bootClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        val classesJar = File(work, "ort-classes.jar")
        ZipFile(aar.singleFile).use { zip ->
            zip.getInputStream(zip.getEntry("classes.jar")).use { input -> classesJar.outputStream().use(input::copyTo) }
        }
        val adapterClasses = File(work, "adapter").apply { mkdirs() }
        val sources = adapterSources.get().asFileTree.files.filter { it.extension == "java" }.map { it.path }
        val javac = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "A JDK is required to build the semantic runtime" }
        val args = listOf("--release", "11", "-classpath", classesJar.path, "-d", adapterClasses.path) + sources
        check(javac.run(null, null, null, *args.toTypedArray()) == 0) { "Compiling the semantic adapter failed" }
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        com.android.tools.r8.D8.run(
            com.android.tools.r8.D8Command.builder()
                .addProgramFiles(classesJar.toPath())
                .addProgramFiles(adapterClasses.walkTopDown().filter { it.extension == "class" }.map { it.toPath() }.toList())
                .addLibraryFiles(bootClasspath.files.map { it.toPath() })
                .setMinApiLevel(24)
                .setMode(com.android.tools.r8.CompilationMode.RELEASE)
                .setOutput(File(out, "semantic-runtime.jar").toPath(), com.android.tools.r8.OutputMode.DexIndexed)
                .build(),
        )
    }
}

val semanticRuntime = tasks.register<SemanticRuntimeTask>("semanticRuntimeDex") {
    aar.from(semanticRuntimeAar)
    adapterSources.set(layout.projectDirectory.dir("src/semantic/java"))
    bootClasspath.from(androidComponents.sdkComponents.bootClasspath)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(semanticRuntime, SemanticRuntimeTask::outputDir)
    }
}

dependencies {
    semanticRuntimeAar(variantOf(libs.onnxruntime.android) { artifactType("aar") })
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
