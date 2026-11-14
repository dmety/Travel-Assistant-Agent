package com.travelassistant.server.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 登录 / 注册速率限制门禁（F7）：挡住在线暴力破解与批量注册。
 *
 * <p>登录按 {@code ip|username} 计数（用户名取自表单参数）；注册用户名在 JSON 体里、过滤器不便解析，
 * 故按 {@code ip} 计数。两者都是进程内滑动窗口，超限返回 429。
 *
 * <p>只认 {@link HttpServletRequest#getRemoteAddr()}，不信任 X-Forwarded-For：无受信任代理配置时
 * 盲信 XFF 会被伪造绕过。生产置于反代后须先配置 forwarded headers 再谈按真实客户端 IP 限流。
 */
public class RateLimitFilter extends OncePerRequestFilter {

  private static final long WINDOW_MS = 60_000;

  private final RateLimiter limiter;
  private final ObjectMapper json;
  private final int loginLimit;
  private final int registerLimit;

  public RateLimitFilter(RateLimiter limiter, ObjectMapper json, int loginLimit, int registerLimit) {
    this.limiter = limiter;
    this.json = json;
    this.loginLimit = loginLimit;
    this.registerLimit = registerLimit;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean post = "POST".equalsIgnoreCase(request.getMethod());
    boolean isLogin = post && "/api/login".equals(path);
    boolean isRegister = post && "/api/account/register".equals(path);
    if (!isLogin && !isRegister) {
      chain.doFilter(request, response);
      return;
    }

    String ip = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    String key;
    int limit;
    if (isLogin) {
      String username = request.getParameter("username");
      key = "login|" + ip + "|" + (username == null ? "" : username);
      limit = loginLimit;
    } else {
      key = "register|" + ip;
      limit = registerLimit;
    }

    if (!limiter.tryAcquire(key, limit, WINDOW_MS)) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write(json.writeValueAsString(
          Map.of("error", "尝试过于频繁，请稍后再试")));
      return;
    }
    chain.doFilter(request, response);
  }
}