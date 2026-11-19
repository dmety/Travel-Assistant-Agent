# 百度地图 SDK 通过 JNI 反射加载 Java 类（如
# com.baidu.platform.comjni.tools.ParcelItem），这些类没有 Java 侧静态引用，
# release 构建会被 R8 shrink 掉，导致 ExceptionInInitializerError 启动崩溃。
# 官方要求整包 keep；插件 4.0.2 未提供 consumerProguardFiles，故由 app 层声明。
-keep class com.baidu.** { *; }
-keep class vi.com.gdi.bgl.android.** { *; }
-dontwarn com.baidu.**
-dontwarn vi.com.gdi.bgl.android.**

# Flutter 百度地图插件与 PlatformView 通道（反射注册）
-keep class com.baidu.bmfmap.** { *; }
-keep class io.flutter.plugin.** { *; }
