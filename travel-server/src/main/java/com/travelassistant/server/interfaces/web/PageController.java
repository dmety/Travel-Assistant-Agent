package com.travelassistant.server.interfaces.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 测试前端入口：只有一个页面 {@code static/index.html}，登录前显示登录/注册卡，
 * 登录后同一页面切换为接口测试台。
 *
 * <p>{@code /login} 与 {@code /app} 是历史链接，保留转发以免旧书签失效。
 * 仅用于本地/测试演示，生产环境可移除或替换为正式前端。
 */
@Controller
public class PageController {

  @GetMapping({"/", "/login", "/app"})
  public String indexPage() {
    return "forward:/index.html";
  }

  /**
   * 旧版两个独立页面的地址，已被单页取代。保留 302 而不是让它 404，
   * 避免队友的书签与文档里的旧链接直接失效。
   */
  @GetMapping({"/login.html", "/app.html"})
  public String legacyPages() {
    return "redirect:/";
  }
}
