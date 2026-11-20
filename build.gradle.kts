import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    id("dev.mantikafasi.manticord.plugin")
}

val releaseSigningFile = rootProject.file("keystore.properties")
val releaseSigning = Properties().apply {
    if (releaseSigningFile.exists()) releaseSigningFile.inputStream().use(::load)
}

manticordPlugin {
    id.set("emojireplace")
    entrypoint.set("dev.mantikafasi.manticord.emojireplace.EmojiReplacePlugin")
    description.set("Choose a style for standard emojis in chat and the emoji picker.")
    manticordVersionMinimum.set(2)
    manticordVersionMaximumExclusive.set(Int.MAX_VALUE)
    usesResources.set(true)
    async()
}

android {
    namespace = "dev.mantikafasi.manticord.emojireplace"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord.emojireplace"
        minSdk = 23
        targetSdk = 36
        versionCode = 6
        versionName = "1.4.1"
    }

    signingConfigs {
        if (releaseSigningFile.exists()) create("release") {
            storeFile = rootProject.file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions.unitTests.isIncludeAndroidResources = true
    androidResources.noCompress += "emojipack"
}

dependencies {
    val localPluginApi = file("../manticordrebuild/build/plugin-sdk/manticord-plugin-api-1.0.6.jar")
    val pluginApi = if (localPluginApi.exists()) files(localPluginApi) else "dev.mantikafasi.manticord:plugin-api:1.0.6"
    compileOnly(pluginApi)
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.rxjava)
    compileOnly("com.facebook.fresco:fbcore:3.7.0")
    compileOnly("androidx.fragment:fragment:1.8.9")
    compileOnly("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation(pluginApi)
    testImplementation("androidx.fragment:fragment:1.8.9")
    testImplementation("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

val emojiArtwork = layout.projectDirectory.file("src/main/assets/emoji-art.emojipack")
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        check(emojiArtwork.asFile.isFile) {
            "Generate the emoji artwork before building. See Build from source in README.md."
        }
    }
}
