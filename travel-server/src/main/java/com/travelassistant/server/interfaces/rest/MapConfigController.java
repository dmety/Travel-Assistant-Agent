package com.travelassistant.server.interfaces.rest;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端地图配置下发（后端注入浏览器端 AK）。
 *
 * <p>百度 BMapGL 是浏览器端 JS API，AK 必须出现在页面请求里，所以这里只做「把后端
 * 已配置的浏览器端 AK 交给已登录页面」，不代为发起任何地图请求。密钥本身仍只存在
 * gitignored 的 .env / 环境变量里，代码与测试都不含真实值，前端 HTML 也不硬编码 AK。
 *
 * <p>本端点刻意不进 SecurityConfiguration 白名单：只对已登录会话开放，避免匿名探测。
 * 这里读独立的 travel.baidu.browser-ak（应用类型「浏览器端」，Referer 白名单 + 启用
 * JavaScript API GL），不复用服务端 WebAPI 的 travel.baidu.ak（IP 白名单）——把服务端
 * key 喂给浏览器 JS API 会被百度拒绝并返回「APP服务被禁用了」。未配置时 enabled=false，
 * 前端优雅降级到 SVG 示意底图。
 *
 * <p>安全边界：后端注入只保证 AK 不进仓库、不被匿名读取；浏览器端 AK 仍会随 SDK 请求
 * 出现在已登录用户的网络面板里（JS API 的固有特性），真正的防盗用靠百度控制台的
 * Referer 域名白名单 + 配额限制。
 */
@RestController
public class MapConfigController {

  private final String browserAk;

  public MapConfigController(@Value("${travel.baidu.browser-ak:}") String browserAk) {
    this.browserAk = browserAk == null ? "" : browserAk.trim();
  }

  /** 返回 provider / enabled / ak；未配置时 ak 为空串，前端自然回落到 SVG 示意底图。 */
  @GetMapping("/api/map/config")
  public Map<String, Object> config() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("provider", "baidu");
    body.put("enabled", !browserAk.isEmpty());
    body.put("ak", browserAk);
    return body;
  }
}