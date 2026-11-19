package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.audit.AdminAuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理监控端点：模型用量与操作审计。
 *
 * <p>这两个端点都含运维敏感信息（累计调用、审计时间线），不放给任意登录用户，
 * 与 Provider 管理端点一致只认 {@code X-Admin-Token}（见 {@code AdminTokenFilter}），
 * 在 {@code SecurityConfiguration} 中 permitAll 后交由令牌过滤器 fail-closed。
 */
@RestController
@RequestMapping("/api/agent")
public class AdminMetricsController {

  private final DeepSeekClient model;
  private final AdminAuditLog audit;

  public AdminMetricsController(DeepSeekClient model, AdminAuditLog audit) {
    this.model = model;
    this.audit = audit;
  }

  /**
   * 本进程的模型调用累计用量：次数、错误数、输入/输出/缓存 token、平均时延、失败率。
   * 余额接口已下线，程序侧拿不到账户余量，故只给用量不给余量（{@code balanceAvailable=false}）。
   */
  @GetMapping("/metrics/model-usage")
  public Map<String, Object> modelUsage() {
    return model == null ? Map.of() : model.usageSnapshot();
  }

  /** 最近的管理操作审计（倒序）。 */
  @GetMapping("/audit-log")
  public Map<String, Object> auditLog(@RequestParam(defaultValue = "200") int limit) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("entries", audit.recent(Math.max(1, Math.min(limit, 500))));
    body.put("total", audit.size());
    return body;
  }
}
