import com.android.build.gradle.BaseExtension

allprojects {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}

// flutter_baidu_mapapi_map 4.0.2 在自身 build.gradle 里写死 compileSdkVersion 35，
// 但其依赖 flutter_plugin_android_lifecycle:2.0.35 的 AAR metadata 要求下游编译 SDK ≥36，
// 否则 :flutter_baidu_mapapi_map:checkReleaseAarMetadata 失败。
// 本机 D:\AndroidSDK\platforms 已装 android-36；统一把 library 子模块提到 36，
// 不改 minSdk/targetSdk，因此不影响运行时行为与可安装设备范围。
subprojects {
    afterEvaluate {
        extensions.findByType(BaseExtension::class.java)?.compileSdkVersion(36)
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
