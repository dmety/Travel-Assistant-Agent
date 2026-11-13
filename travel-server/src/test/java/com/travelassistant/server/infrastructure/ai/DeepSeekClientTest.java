package com.travelassistant.server.infrastructure.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.travelassistant.server.application.agent.AgentUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * 模型客户端成功路径与失败路径。
 *
 * <p>此前规划接口的测试全部在无密钥状态下跑降级分支，{@code DeepSeekClient.complete()}
 * 的成功分支覆盖率为 0。这里用 MockRestServiceServer 拦截，不发真实请求，
 * 保证「模型返回正常文本时确实能解析」这条路径有自动化回归。
 */
@DisplayName("模型客户端成功与失败路径")
class DeepSeekClientTest {

  private final RestTemplate restTemplate = new RestTemplate();
  private final MockRestServiceServer server =
      MockRestServiceServer.bindTo(restTemplate).build();

  private DeepSeekClient client(String key) {
    return new DeepSeekClient(restTemplate, key, "http://mock.local", "deepseek-chat");
  }

  @Test
  @DisplayName("成功路径：解析 choices[0].message.content 并去除首尾空白")
  void parsesSuccessfulCompletion() {
    server.expect(requestTo("http://mock.local/chat/completions"))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andRespond(withSuccess("""
            {"choices":[{"message":{"role":"assistant","content":"  已核验的建议文本  "}}]}
            """, MediaType.APPLICATION_JSON));

    assertThat(client("test-key").complete("system", "user")).isEqualTo("已核验的建议文本");
    server.verify();
  }

  @Test
  @DisplayName("成功路径：把响应里的 usage 累加进用量快照，含缓存命中数")
  void recordsUsageCounters() {
    server.expect(requestTo("http://mock.local/chat/completions"))
        .andRespond(withSuccess("""
            {"choices":[{"message":{"role":"assistant","content":"ok"}}],
             "usage":{"prompt_tokens":1200,"completion_tokens":80,
                      "prompt_cache_hit_tokens":960}}
            """, MediaType.APPLICATION_JSON));

    DeepSeekClient client = client("test-key");
    assertThat(client.usageSnapshot()).containsEntry("calls", 0L);
    client.complete("system", "user");
    // 只看 prompt_tokens 会高估成本，缓存命中部分单价约为五十分之一，必须单独记
    assertThat(client.usageSnapshot())
        .containsEntry("calls", 1L).containsEntry("promptTokens", 1200L).containsEntry("completionTokens", 80L)
        .containsEntry("cachedPromptTokens", 960L);
    server.verify();
  }

  @Test
  @DisplayName("响应没有 usage 字段：照常返回文本，记录调用并保持用量为零")
  void missingUsageIsTolerated() {
    server.expect(requestTo("http://mock.local/chat/completions"))
        .andRespond(withSuccess("""
            {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
            """, MediaType.APPLICATION_JSON));

    DeepSeekClient client = client("test-key");
    assertThat(client.complete("system", "user")).isEqualTo("ok");
    assertThat(client.usageSnapshot()).containsEntry("calls", 1L).containsEntry("promptTokens", 0L);
    server.verify();
  }

  @Test
  @DisplayName("未配置密钥：不发出请求并说明原因")
  void unconfiguredDoesNotCallOut() {
    assertThat(client("").configured()).isFalse();
    assertThatThrownBy(() -> client("").complete("s", "u"))
        .isInstanceOf(AgentUnavailableException.class)
        .hasMessageContaining("DEEPSEEK_API_KEY");
    server.verify();
  }

  @Test
  @DisplayName("401：翻成可操作提示，密钥原文不进异常")
  void authFailureIsExplainedWithoutLeakingKey() {
    server.expect(requestTo(containsString("/chat/completions")))
        .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"error\":\"invalid\"}"));

    assertThatThrownBy(() -> client("super-secret-key").complete("s", "u"))
        .isInstanceOf(AgentUnavailableException.class)
        .hasMessageContaining("密钥无效")
        .hasMessageNotContaining("super-secret-key");
    server.verify();
  }

  @Test
  @DisplayName("响应缺少 content：明确拒绝而不是返回空串")
  void emptyContentIsRejected() {
    server.expect(requestTo(containsString("/chat/completions")))
        .andRespond(withSuccess(
            "{\"choices\":[{\"message\":{}}]}", MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client("test-key").complete("s", "u"))
        .isInstanceOf(AgentUnavailableException.class)
        .hasMessageContaining("内容为空");
    server.verify();
  }
}
