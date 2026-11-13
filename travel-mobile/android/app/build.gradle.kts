plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 百度地图 AK：从 gitignored 的 local.properties(baiduMapAk) 或环境变量
// BAIDU_MAP_AK 读入，注入 Manifest 的 com.baidu.lbsapi.API_KEY meta-data。
// 与 Dart 侧 --dart-define=BAIDU_MAP_AK 必须同源（见 tools/build_mobile.*）。
val localProperties = java.util.Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
val baiduMapAk: String =
    localProperties.getProperty("baiduMapAk") ?: System.getenv("BAIDU_MAP_AK") ?: ""

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
