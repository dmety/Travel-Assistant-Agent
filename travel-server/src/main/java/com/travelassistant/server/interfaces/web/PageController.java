package com.travelassistant.server.interfaces.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 测试用页面路由：把 {@code /login} 与根路径 {@code /} 转发到静态 {@code login.html}。
 * 仅用于本地/测试演示，生产环境可移除或替换为正式前端。
 */
@Controller
public class PageController {

  @GetMapping({"/", "/login"})
  public String loginPage() {
    return "forward:/login.html";
  }
}
