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
                // 测试前端单页：/、/login、/app 都由 PageController 转发到 index.html，
                // /login.html、/app.html 是旧链接，302 到 /。页面本身必须在未登录时可达
                // （登录卡就在这一页里），数据仍由 /api/** 拦截。
                "/ticket-assistant.html", "/api/agent/tickets/advice",
                "/", "/login", "/app", "/login.html", "/app.html", "/index.html", "/favicon.ico")
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
