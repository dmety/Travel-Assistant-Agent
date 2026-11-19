package com.travelassistant.server.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Provider 管理端点独立管理令牌门禁（F1）。
 *
 * <p>reload / probe / shadow / activate / admission 会改注册表状态、携带服务端凭据发真实请求、
 * 消耗配额或泄漏 endpoint，不能对任意登录用户开放。改为只认 {@code X-Admin-Token} 头，
 * 与用户注册/会话解耦：匿名携带正确令牌即可管理，登录但无令牌一律拒绝。
 *
 * <p>令牌经环境变量注入；未配置（空）时 fail-closed，所有管理请求 403。比对用常量时间，
 * 避免按字节提前返回造成的计时侧信道。
 */
public class AdminTokenFilter extends OncePerRequestFilter {

  private static final AntPathMatcher MATCHER = new AntPathMatcher();
  private static final List<String> ADMIN_PATHS = List.of(
      "/api/agent/providers",
      "/api/agent/providers/reload",
      "/api/agent/providers/*/probe",
      "/api/agent/providers/*/shadow",
      "/api/agent/providers/*/activate",
      "/api/agent/providers/*/admission",
      "/api/agent/metrics/model-usage",
      "/api/agent/audit-log");

  private final String adminToken;
  private final ObjectMapper json;

  public AdminTokenFilter(String adminToken, ObjectMapper json) {
    this.adminToken = adminToken;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean isAdmin = ADMIN_PATHS.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    if (!isAdmin) {
      chain.doFilter(request, response);
      return;
    }
    if (!tokenValid(request.getHeader("X-Admin-Token"))) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setCharacterEncoding("UTF-8");
      response.getWriter().write(json.writeValueAsString(
          Map.of("error", "管理端点需要有效的管理令牌")));
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean tokenValid(String provided) {
    if (adminToken == null || adminToken.isEmpty() || provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8),
        adminToken.getBytes(StandardCharsets.UTF_8));
  }
}