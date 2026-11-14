package com.travelassistant.server.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 把「本次请求实际以哪个账号执行」写进响应头，供前端发现身份漂移。
 *
 * <p>本系统是单 JSESSIONID Cookie 会话：同一浏览器第二个标签登录另一个账号时，
 * 浏览器 Cookie 会被覆盖，第一个标签随后的请求静默变成新账号。原来的 SPA 只在
 * 加载时读一次 {@code /api/account/me}，界面仍显示旧用户名，数据却已是新账号的，
 * 正是「多用户串号」最隐蔽的来源。
 *
 * <p>这里只暴露用户名（不含凭据、角色、令牌），前端把每个响应里的用户名与页面
 * 初始化时的身份比对：一旦不同就清掉本地状态并显式提示，而不是静默继续。
 * 匿名请求不写该头，避免给未登录探测提供账号枚举线索。
 */
public class AuthUserHeaderFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Auth-User";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.isAuthenticated()
        && !(auth instanceof AnonymousAuthenticationToken)) {
      response.setHeader(HEADER, auth.getName());
    }
    chain.doFilter(request, response);
  }
}
