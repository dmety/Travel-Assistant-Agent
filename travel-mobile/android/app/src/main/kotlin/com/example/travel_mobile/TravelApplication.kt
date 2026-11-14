package com.example.travel_mobile

import android.app.Application
import com.baidu.mapapi.SDKInitializer
import com.baidu.mapapi.common.BaiduMapSDKException

/**
 * 百度地图 SDK 必须在任何地图视图创建之前完成初始化。
 *
 * flutter_baidu_mapapi_base 提供 BmfMapApplication 做这件事，但它内部写死了
 * `setAgreePrivacy(this, false)`，且直接引用插件内部类不利于后续升级。
 * 这里按「先同意隐私合规、再初始化」的官方推荐顺序自行实现，
 * 并在 AndroidManifest.xml 的 application android:name 中声明。
 *
 * 初始化失败时不抛异常阻断 App：MapPanel 依赖百度 Widget 创建成功与否，
 * 原生侧失败会让真地图留白，Dart 侧仍可回落到 SVG 示意底图。
 */
class TravelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            SDKInitializer.setAgreePrivacy(this, true)
            SDKInitializer.initialize(this)
        } catch (_: BaiduMapSDKException) {
            // 交由地图视图层降级处理，不阻断其余功能
        }
    }
}
