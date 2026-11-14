package com.travelassistant.server.interfaces.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面入口：{@code /} 转发到 {@code static/index.html}（登录 / 注册页）。
 *
 * <p>登录成功后进入 {@code static/chat.html}（AI 对话页），未登录访问对话页会被页内
 * 脚本读 {@code /api/account/me} 拿到的 401 送回首页，并带上 {@code next} 参数；
 * 接口测试台在 {@code static/api-console.html}，自带登录卡，登录后同一页面切换为测试台。
 *
 * <p>{@code /login} 与 {@code /app} 是历史链接，保留转发以免旧书签失效。
 * 所有业务界面已收敛到 {@code static/planner.html}：{@code /planner} 转发过去，
 * 旧的 {@code /chat.html} 与 {@code /ticket-assistant.html} 302 过去。
 * 接口测试台仍是开发者工具，留在 {@code static/api-console.html}，不进用户导航。
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

  /**
   * 旧的对话页与购票页已并入行程工作台，页面文件不再保留。
   * 旧书签与文档里的链接统一 302 到工作台，别让它们撞上 404 再被安全链拦成 401。
   */
  @GetMapping({"/chat.html", "/ticket-assistant.html"})
  public String legacyAppPages() {
    return "redirect:/planner.html";
  }

  /** 新版行程工作台入口：静态页本身公开，数据接口仍需登录。 */
  @GetMapping("/planner")
  public String plannerPage() {
    return "forward:/planner.html";
  }

  /** 管理后台入口：静态页本身公开，注册表与准入接口由 AdminTokenFilter 以 X-Admin-Token 鉴权。 */
  @GetMapping("/admin")
  public String adminPage() {
    return "forward:/admin.html";
  }
}
