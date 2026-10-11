plugins {
    id("com.android.application")
}

android {
    namespace = "org.openlamp.local"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "org.openlamp.local"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        getByName("debug") {
            // Reuse the original app's key so updates preserve its controller ID.
            storeFile = rootProject.file(".keys/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            // Local-use release: the existing key permits updating the installed app.
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

abstract class PrepareLicenseAssets : DefaultTask() {
    @get:InputFiles
    abstract val licenseFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val destination = outputDirectory.get().asFile
        destination.mkdirs()
        licenseFiles.files.forEach { it.copyTo(destination.resolve(it.name), overwrite = true) }
    }
}

val prepareLicenseAssets by tasks.registering(PrepareLicenseAssets::class) {
    licenseFiles.from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"), rootProject.file("UPSTREAM-LICENSE"), rootProject.file("LICENSE-APACHE-2.0"))
    outputDirectory.set(layout.buildDirectory.dir("license-assets"))
}

val createDebugKeystore by tasks.registering(Exec::class) {
    val keystore = rootProject.file(".keys/debug.keystore")
    onlyIf { !keystore.exists() }
    doFirst { keystore.parentFile.mkdirs() }
    commandLine(
        "${System.getProperty("java.home")}/bin/keytool", "-genkeypair",
        "-keystore", keystore.absolutePath, "-storepass", "android", "-keypass", "android",
        "-alias", "androiddebugkey", "-dname", "CN=OpenLamp Local Debug", "-keyalg", "RSA",
        "-keysize", "2048", "-validity", "3650",
    )
}

androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLicenseAssets, PrepareLicenseAssets::outputDirectory)
}

tasks.matching { it.name in setOf("validateSigningDebug", "validateSigningRelease") }.configureEach {
    dependsOn(createDebugKeystore)
}

dependencies {
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
