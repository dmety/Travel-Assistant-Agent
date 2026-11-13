package com.travelassistant.server.interfaces.rest;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前端地图配置下发。
 *
 * <p>百度 BMapGL 是浏览器端 JS API，AK 必须出现在页面请求里，所以这里只做「把服务端
 * 已配置的 AK 交给已登录页面」，不代为发起任何地图请求。密钥本身仍只存在 gitignored
 * 的 .env / 环境变量里，代码与测试都不含真实值。
 *
 * <p>本端点刻意不进 SecurityConfiguration 白名单：只对已登录会话开放，避免匿名探测。
 * 百度侧应给这个浏览器端 AK 配 Referer 域名白名单，不要复用服务端 IP 白名单的 Web 服务 key。
 */
@RestController
public class MapConfigController {

  private final String baiduAk;

  public MapConfigController(@Value("${travel.baidu.ak:}") String baiduAk) {
    this.baiduAk = baiduAk == null ? "" : baiduAk.trim();
  }

  /** 返回 provider / enabled / ak；未配置时 ak 为空串，前端自然回落到 SVG 示意底图。 */
  @GetMapping("/api/map/config")
  public Map<String, Object> config() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("provider", "baidu");
    body.put("enabled", !baiduAk.isEmpty());
    body.put("ak", baiduAk);
    return body;
  }
}
