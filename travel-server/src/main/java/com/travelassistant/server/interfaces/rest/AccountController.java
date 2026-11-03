package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.account.AccountService;
import com.travelassistant.server.application.account.DuplicateAccountException;
import com.travelassistant.server.application.account.InvalidAccountException;
import com.travelassistant.server.interfaces.rest.dto.RegisterRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/account")
@Validated
public class AccountController {
  private final AccountService accountService;

  public AccountController(AccountService accountService) {
    this.accountService = accountService;
  }

  /** 公开注册：写入服务端 JSON。用户名重复返回 409，格式不合法返回 400。 */
  @PostMapping("/register")
  public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request) {
    try {
      accountService.register(request.username(), request.password());
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(Map.of("username", request.username(), "created", true));
    } catch (DuplicateAccountException e) {
      return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    } catch (InvalidAccountException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
  }

  /** 已登录用户查询自身信息；未登录由安全层拦截返回 401。 */
  @GetMapping("/me")
  public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal UserDetails user) {
    if (user == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", user.getUsername());
    body.put("roles", user.getAuthorities().stream()
        .map(g -> g.getAuthority()).collect(Collectors.toList()));
    return ResponseEntity.ok(body);
  }
}
