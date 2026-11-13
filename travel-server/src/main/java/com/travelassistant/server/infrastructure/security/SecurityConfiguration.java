package com.travelassistant.server.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfiguration {

  /** BCrypt 密码编码器：注册时哈希、登录时比对。 */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
      @Value("${travel.agent.admin-token:}") String adminToken,
      @Value("${travel.rate-limit.login-per-minute:8}") int loginLimit,
      @Value("${travel.rate-limit.register-per-minute:20}") int registerLimit) throws Exception {
    ObjectMapper json = new ObjectMapper();

    http
        // 客户端为第一方控制台程序与浏览器 SPA（planner.html / index.html）。会话 Cookie 已
        // HttpOnly + SameSite=Strict（见 application.yml），跨站请求无法携带会话，故禁用 CSRF 令牌
        // ——这是已记录的可接受风险，非缺陷。生产务必启用 HTTPS（SESSION_COOKIE_SECURE=true）
        // 避免明文传输凭据；若将来放宽 SameSite 或引入第三方跨站调用，必须重新启用 CSRF 防护。
        .csrf(csrf -> csrf.disable())

        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/health", "/api/login", "/api/account/register",
                "/api/attractions", "/api/attractions/**", "/api/tickets", "/api/tickets/**",
                // 三个能力状态接口只返回布尔、统计与能力描述，不含用户数据；
                // 对外开放才能让健康检查与前端登录卡在建立会话前看到降级原因。
                // /api/agent/capabilities 同理：只含 id / 状态 / 能力标签 / 最近拒绝原因码，
                // 不含 endpoint、authRef 或任何凭据，前端登录前就能动态渲染可用入口。
                "/api/location/status", "/api/agent/status", "/api/agent/capabilities",
                // Provider 管理端点（reload/probe/shadow/activate/admission）改注册表状态、
                // 携服务端凭据发真实请求、消耗配额或泄漏 endpoint，不能对任意登录用户开放。
                // 这里放行是为了与「用户会话」解耦：真正的门禁交给 AdminTokenFilter，
                // 只认环境变量注入的 X-Admin-Token；未配置令牌时 fail-closed，一律 403。
                "/api/agent/providers/reload", "/api/agent/providers/*/probe",
                "/api/agent/providers/*/shadow", "/api/agent/providers/*/activate",
                "/api/agent/providers/*/admission",
                // 首页：/、/login、/app 由 PageController 转发到 index.html（登录 / 注册页）。
                // 业务界面统一在 /planner.html：页面公开，页内读 /api/account/me 判断登录态，
                // 401 时跳回 /?next=/planner.html；真正的数据与模型配额仍由 /api/** 拦截。
                // /chat.html、/ticket-assistant.html 已并入工作台，现在只做 302，
                // 仍要放行：否则旧书签会先撞 401、连跳转都看不到。
                // /api-console.html 是开发者用的接口测试台，自带登录卡，不进用户导航。
                // 页面本身公开，但 /api/agent/tickets/advice 会触发模型调用，必须登录后才能访问：
                // 匿名放行等于把模型配额暴露给任意请求。前端 api() 收到 401 会提示登录。
                // /api/agent/invoke 与 /api/chat 是 L1 编排与对话入口，会触发模型与地图配额，
                // 同样必须登录后才能访问；它们不在白名单里，由 .anyRequest().authenticated() 拦截。
                "/chat.html", "/ticket-assistant.html", "/api-console.html",
                "/", "/login", "/app", "/login.html", "/app.html", "/index.html", "/favicon.ico",
                "/planner.html", "/planner")
            .permitAll()
            .anyRequest().authenticated())

        // 服务端会话：认证成功后创建 HttpSession，并通过 JSESSIONID Cookie 维持。
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

        // 未认证请求直接返回 401，便于 JSON 客户端处理（不跳转登录页）。
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))

        // 表单登录：成功/失败均以 JSON 响应，并自动建立会话与 Cookie。
        .formLogin(form -> form
            .loginProcessingUrl("/api/login")
            .permitAll()
            .successHandler((req, res, authentication) -> writeJson(res, json, HttpStatus.OK,
                Map.of("authenticated", true, "username", authentication.getName())))
            .failureHandler((req, res, ex) -> writeJson(res, json, HttpStatus.UNAUTHORIZED,
                Map.of("authenticated", false, "error", "用户名或密码错误"))))

        // 退出：使服务端会话失效并清除 JSESSIONID Cookie。
        .logout(logout -> logout
            .logoutUrl("/api/logout")
            .invalidateHttpSession(true)
            .deleteCookies("JSESSIONID")
            .logoutSuccessHandler((req, res, ex) -> writeJson(res, json, HttpStatus.OK,
                Map.of("loggedOut", true))))

        // 管理令牌门禁置于授权过滤器之前：管理路径已在上面 permitAll 与会话解耦，
        // 由本过滤器独立校验 X-Admin-Token；未配置或令牌不符一律 403。
        .addFilterBefore(new AdminTokenFilter(adminToken, json), AuthorizationFilter.class)

        // 登录/注册限流置于认证过滤器之前：超限直接 429，不再走认证与业务逻辑（F7）。
        .addFilterBefore(new RateLimitFilter(new RateLimiter(), json, loginLimit, registerLimit),
            UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  private static void writeJson(HttpServletResponse response, ObjectMapper json,
      HttpStatus status, Map<String, Object> body) throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(json.writeValueAsString(body));
  }
}
