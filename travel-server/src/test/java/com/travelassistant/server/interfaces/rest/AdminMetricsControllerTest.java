package com.travelassistant.server.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.audit.AdminAuditLog;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

/** 管理监控端点：模型用量快照与审计日志的形状，及空模型 / limit 夹取分支。 */
@DisplayName("管理监控端点 AdminMetricsController")
class AdminMetricsControllerTest {

  private static DeepSeekClient model() {
    // 无密钥也能构造；usageSnapshot 只读累计计数，不触发真实请求
    return new DeepSeekClient(new RestTemplate(), "", "http://mock.local", "deepseek-chat");
  }

  @Test
  @DisplayName("modelUsage 透传用量快照，含失败率与时延字段")
  void modelUsageReturnsSnapshot() {
    Map<String, Object> body = new AdminMetricsController(model(), new AdminAuditLog()).modelUsage();
    assertThat(body)
        .containsKeys("model", "calls", "errors", "totalRequests", "failureRate",
            "promptTokens", "completionTokens", "totalTokens", "avgLatencyMs",
            "lastLatencyMs", "lastError", "since", "balanceAvailable")
        .containsEntry("calls", 0L)
        .containsEntry("failureRate", 0d);
  }

  @Test
  @DisplayName("模型 Bean 缺失时返回空对象而不是抛异常")
  void modelUsageToleratesMissingModel() {
    assertThat(new AdminMetricsController(null, new AdminAuditLog()).modelUsage()).isEmpty();
  }

  @Test
  @DisplayName("auditLog 返回倒序条目与总数，limit 被夹取到 1..500")
  void auditLogReturnsEntriesAndClampsLimit() {
    AdminAuditLog audit = new AdminAuditLog();
    audit.record("reload", "-", "127.0.0.1", true, "registry v1");
    audit.record("probe", "food.recommender", "127.0.0.1", true, "status=PROBING");
    AdminMetricsController controller = new AdminMetricsController(model(), audit);

    Map<String, Object> body = controller.auditLog(200);
    assertThat(body).containsEntry("total", 2);
    @SuppressWarnings("unchecked")
    List<AdminAuditLog.Entry> entries = (List<AdminAuditLog.Entry>) body.get("entries");
    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).action()).isEqualTo("probe");

    assertThat(controller.auditLog(1).get("entries")).asList().hasSize(1);
    assertThat(controller.auditLog(0).get("entries")).asList().hasSize(1);
    assertThat(controller.auditLog(9999).get("entries")).asList().hasSize(2);
  }
}
