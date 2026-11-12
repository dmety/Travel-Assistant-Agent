package com.travelassistant.server.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfiguration {

  /** BCrypt 密码编码器：注册时哈希、登录时比对。 */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    ObjectMapper json = new ObjectMapper();

    http
        // 客户端为第一方控制台程序（非浏览器），且会话 Cookie 已 HttpOnly + SameSite=Strict，
        // 跨站无法读取/携带。生产务必启用 HTTPS 避免明文传输凭据。
        .csrf(csrf -> csrf.disable())

        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/health", "/api/login", "/api/account/register",
                "/api/attractions", "/api/attractions/**", "/api/tickets", "/api/tickets/**",
                // 三个能力状态接口只返回布尔、统计与能力描述，不含用户数据；
                // 对外开放才能让健康检查与前端登录卡在建立会话前看到降级原因。
                // /api/agent/capabilities 同理：只含 id / 状态 / 能力标签 / 最近拒绝原因码，
                // 不含 endpoint、authRef 或任何凭据，前端登录前就能动态渲染可用入口。
                "/api/location/status", "/api/agent/status", "/api/agent/capabilities",
                // 首页：/、/login、/app 由 PageController 转发到 index.html（登录 / 注册页）；
                // 登录后的对话页是 /chat.html，接口测试台是 /api-console.html（自带登录卡），
                // 三个页面本身都必须在未登录时可达，数据仍由 /api/** 拦截。
                // 对话页的登录态由页内 JS 读 /api/account/me 判断：401 时跳回 /?next=/chat.html。
                // /login.html、/app.html 是旧链接，302 到 /。
                // 页面本身公开，但 /api/agent/tickets/advice 会触发模型调用，必须登录后才能访问：
                // 匿名放行等于把模型配额暴露给任意请求。前端 api() 收到 401 会提示登录。
                // /api/agent/invoke 是 L1 编排入口，会触发地图 API 配额，同样必须登录后
                // 才能访问；它不在白名单里，由 .anyRequest().authenticated() 拦截。
                "/ticket-assistant.html", "/api-console.html", "/chat.html",
                "/", "/login", "/app", "/login.html", "/app.html", "/index.html", "/favicon.ico",
                // 新版行程工作台：/planner 由 PageController 转发，/planner.html 是静态页。
                // 与 chat.html 同口径：页面公开，页内读 /api/account/me 判断登录态，
                // 真正的数据与模型配额仍由 /api/** 拦截。
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
                Map.of("loggedOut", true))));

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
