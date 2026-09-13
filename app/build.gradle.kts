import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息读取顺序：Gradle 属性（-P / gradle.properties） > local.properties > 环境变量（CI 使用）。
// 注意：密钥、密码等敏感内容请勿写入 gradle.properties，该文件会随仓库提交。
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun signingValue(gradlePropertyName: String, localPropertyName: String, environmentName: String): String? =
    providers.gradleProperty(gradlePropertyName).orNull?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localPropertyName)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environmentName).orNull?.takeIf { it.isNotBlank() }

// 相对路径统一相对仓库根目录解析；CI 中由 RELEASE_STORE_FILE 指向临时目录里的证书副本。
val releaseStoreFile = signingValue("releaseStoreFile", "release.storeFile", "RELEASE_STORE_FILE")
    ?.let { rootProject.file(it) }
val releaseStorePassword = signingValue("releaseStorePassword", "release.storePassword", "RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingValue("releaseKeyAlias", "release.keyAlias", "RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingValue("releaseKeyPassword", "release.keyPassword", "RELEASE_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile?.exists() == true

if (releaseStoreFile != null && !hasReleaseSigning) {
    logger.warn("HyperLock: 未找到签名证书 ${releaseStoreFile.absolutePath}，release 构建将回退到调试签名。")
} else if (!hasReleaseSigning) {
    logger.warn("HyperLock: 未配置 release 签名，release 构建将使用调试签名。")
}

// 版本号：CI 发版时把 tag 通过 HYPERLOCK_TAG 传进来（v1.2.3 / v1.2.3-Beta-2 之类，只取数字部分），
// 因此 Release 包的版本号自动与 tag 一致；没有 tag（手动触发 main、本地构建）时回退到默认值。
// 也支持用 -PversionTag=vX.Y.Z 手动覆盖。
val releaseTag = providers.gradleProperty("versionTag").orNull?.takeIf { it.isNotBlank() }
    ?: providers.environmentVariable("HYPERLOCK_TAG").orNull?.takeIf { it.isNotBlank() }

val tagVersion = releaseTag?.removePrefix("v")
    ?.let { Regex("""^(\d+)\.(\d+)\.(\d+)""").find(it) }
val versionParts = tagVersion?.let { listOf(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
val appVersionName = versionParts?.joinToString(".") ?: "1.0.1"
val appVersionCode = versionParts?.let { (major, minor, patch) ->
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
} ?: 10001

logger.lifecycle("HyperLock: versionName=$appVersionName, versionCode=$appVersionCode, tag=${releaseTag ?: "无"}")

android {
    namespace = "io.github.windsnn.hyperlock"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.windsnn.hyperlock"
        minSdk = 33
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
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
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")
    implementation("io.github.kyant0:backdrop:2.0.0")
    implementation("io.github.kyant0:shapes:1.2.0")
    implementation("org.jetbrains.compose.ui:ui:1.11.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.0")
    implementation("org.jetbrains.compose.animation:animation:1.11.0")
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("io.github.proify.lyricon:subscriber:0.1.70")

    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
