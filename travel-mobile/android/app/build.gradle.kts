plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

import java.util.Properties

// 百度地图 AK：从 gitignored 的 local.properties(baiduMapAk) 或环境变量
// BAIDU_MAP_AK 读入，注入 Manifest 的 com.baidu.lbsapi.API_KEY meta-data。
// 与 Dart 侧 --dart-define=BAIDU_MAP_AK 必须同源（见 tools/build_mobile.*）。
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val baiduMapAk: String =
    localProperties.getProperty("baiduMapAk") ?: System.getenv("BAIDU_MAP_AK") ?: ""

// AGP 9.x 已移除 Jetifier，无法自动把旧 support 库重写为 AndroidX。
// flutter_baidu_mapapi_map 4.0.2 传递依赖 android.arch.lifecycle:extensions:1.1.1
// -> com.android.support:support-compat:26.1.0；其 android.support.v4.os.* 与
// INotificationSideChannel 等类和 androidx.core:core:1.13.1 重复，触发
// checkReleaseDuplicateClasses 失败，这些类 androidx.core 已有等价实现。
// 只能排除 support-compat 这一个模块：整组排除会连带删掉 support-fragments，
// 导致 android.arch.lifecycle.ProcessLifecycleOwnerInitializer 运行时找不到
// FragmentManager$FragmentLifecycleCallbacks，app 启动即崩（已实测）。
configurations.all {
    exclude(group = "com.android.support", module = "support-compat")
}

android {
    namespace = "com.example.travel_mobile"
    compileSdk = flutter.compileSdkVersion
    // 已在本机 SDK 安装 NDK 28.2.13676358（ndk/r28c），与 Flutter 期望版本一致。
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.travel_mobile"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // 百度地图 AK 占位符（空值时原生 SDK 不鉴权，对应 Dart 侧 SVG 降级）
        manifestPlaceholders["BAIDU_MAP_AK"] = baiduMapAk
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
            // 百度 SDK 的 JNI 反射类必须保留，否则 release 启动即崩（详见 proguard-rules.pro）。
            // 只追加自己的规则，沿用 Flutter Gradle Plugin 默认的 shrinker 配置。
            proguardFile("proguard-rules.pro")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// Application 层需要直接调用 SDKInitializer 完成初始化。百度 SDK 的类目前只由
// flutter_baidu_mapapi_base 插件模块以 implementation 引入，app 的 compileClasspath
// 看不到；这里显式声明同一坐标（Gradle 依赖去重，不会重复打包或增大 APK）。
dependencies {
    implementation("com.baidu.lbsyun:base:8.2.0")
}
