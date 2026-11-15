import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名口令放用户级 gradle.properties（~/.gradle/gradle.properties），不进仓库。
// 读不到就回落 debug 签名——保证别人 clone 下来也能编译，只是包名签名不同。
val signProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".gradle/gradle.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val storePath = signProps.getProperty("ZHIYI_STORE_FILE").orEmpty()
val hasReleaseKey = storePath.isNotBlank() && File(storePath).exists()

android {
    namespace = "app.zhiyi"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.zhiyi"
        minSdk = 26
        targetSdk = 36
        versionCode = 179
        versionName = "10.96"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    lint {
        checkReleaseBuilds = false    // lint 的 PrivateApiDetector 会崩在反射代码上，跳过
        abortOnError = false
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = File(storePath)
                storePassword = signProps.getProperty("ZHIYI_STORE_PASSWORD")
                keyAlias = signProps.getProperty("ZHIYI_KEY_ALIAS")
                keyPassword = signProps.getProperty("ZHIYI_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 先不开 R8（避免 Shizuku 反射/Compose 被裁），仅去掉 debuggable —— 这一步就能明显提速
            isMinifyEnabled = false
            isDebuggable = false
            // 有固定 keystore 就用它：换机器重编签名不变，用户能覆盖升级
            signingConfig = signingConfigs.getByName(if (hasReleaseKey) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 仅 JVM 单测用（WsClient 等纯 Java 逻辑可以脱离设备验证）
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
