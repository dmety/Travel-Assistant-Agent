package com.travelassistant.server.agent.vote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.agent.vote.PlanVoteTally;
import com.travelassistant.server.application.agent.vote.PlanVoteTally.VoteOutcome;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator.VoteRequest;
import com.travelassistant.server.application.agent.vote.VoteOrchestrator.VoteRun;
import com.travelassistant.server.interfaces.rest.AgentVoteController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** 投票 REST 入口的控制器层测试：信封结构、参数校验与异常映射。401 由 acceptance.py 在真实服务上覆盖。 */
class AgentVoteControllerTest {

  private final VoteOrchestrator orchestrator = mock(VoteOrchestrator.class);
  private final AgentVoteController controller = new AgentVoteController(orchestrator);

  private static VoteOutcome outcome(String winner) {
    return new VoteOutcome("1.0", "DECISION_ADVICE", winner, List.of(), List.of(),
        List.of(), List.of(), false);
  }

  /** 带非空 ranking / excluded / rejections 的结果，覆盖 result() 里的循环体行。 */
  private static VoteOutcome richOutcome(String winner) {
    PlanVoteTally.Breakdown breakdown = new PlanVoteTally.Breakdown("judge.ticket", "4.0000",
        "1.2", Map.of("ticketCost", "5", "reservationRisk", "4"),
        List.of("judge.ticket:context"));
    PlanVoteTally.Ranking ranking = new PlanVoteTally.Ranking("A", "3.7750", 3, "12.0",
        List.of(), List.of(breakdown));
    PlanVoteTally.Excluded excluded = new PlanVoteTally.Excluded("B", "VETO",
        List.of("judge.ticket"), List.of("judge.ticket:context"));
    PlanVoteTally.Rejection rejection = new PlanVoteTally.Rejection("judge.food",
        "score:judge.food:A", "E_VOTE_RUBRIC", "出现该 rubric 未声明的评分项");
    return new VoteOutcome("1.0", "DECISION_ADVICE", winner, List.of(ranking),
        List.of(excluded), List.of(), List.of(rejection), false);
  }

  private static ResponseEntity<Map<String, Object>> cast(ResponseEntity<?> raw) {
    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> out = (ResponseEntity<Map<String, Object>>) raw;
    return out;
  }

  @Test
  void nullBodyIsRejectedWith400() {
    ResponseEntity<Map<String, Object>> response = cast(controller.vote(null));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsKey("error");
  }

  @Test
  void missingIntentIsRejectedWith400() {
    ResponseEntity<Map<String, Object>> response =
        cast(controller.vote(new VoteRequest(null, Map.of(), Map.of(), List.of())));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<Map<String, Object>> blank = cast(
        controller.vote(new VoteRequest("  ", Map.of(), Map.of(), List.of())));
    assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void illegalIntentIsRejectedWith400() {
    when(orchestrator.vote(any())).thenThrow(new IllegalArgumentException("只支持 DECISION_ADVICE"));
    ResponseEntity<Map<String, Object>> response = cast(controller.vote(
        new VoteRequest("FOOD_RECOMMEND", Map.of(), Map.of(), List.of())));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("error", "只支持 DECISION_ADVICE");
  }

  @Test
  void happyPathReturnsEnvelopeWithResultAndNoCredentials() {
    VoteRun run = new VoteRun("OK", "DECISION_ADVICE", "reg-1", "RULES",
        List.of("judge.ticket", "judge.food", "judge.parking"),
        List.of(Map.of("planId", "A", "title", "控制花费", "focus", "COST")),
        richOutcome("A"), List.of());
    when(orchestrator.vote(any())).thenReturn(run);

    ResponseEntity<Map<String, Object>> response = cast(controller.vote(
        new VoteRequest("DECISION_ADVICE", Map.of("city", "北京"), Map.of(), List.of())));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = response.getBody();
    assertThat(body).containsKeys("intent", "status", "registryVersion", "candidateSource",
        "judges", "plans", "result", "notices");
    assertThat(body.get("status")).isEqualTo("OK");
    assertThat(body.get("candidateSource")).isEqualTo("RULES");
    assertThat(body.get("judges")).isEqualTo(List.of("judge.ticket", "judge.food", "judge.parking"));

    // 信封与结果都不含 endpoint / authRef / 任何凭据串。
    String serialized = body.toString();
    assertThat(serialized).doesNotContain("endpoint").doesNotContain("authRef");
    assertThat(serialized.toLowerCase()).doesNotContain("api_key").doesNotContain("secret");
  }

  @Test
  void resultEnvelopeExposesTallyFields() {
    VoteRun run = new VoteRun("OK", "DECISION_ADVICE", "reg-1", "RULES", List.of(),
        List.of(), outcome("A"), List.of());
    when(orchestrator.vote(any())).thenReturn(run);

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) cast(controller.vote(
        new VoteRequest("DECISION_ADVICE", Map.of(), Map.of(), List.of()))).getBody()
        .get("result");
    assertThat(result).containsKeys("schemaVersion", "intent", "winner", "ranking",
        "excluded", "notices", "rejections", "homogeneous");
    assertThat(result.get("winner")).isEqualTo("A");
  }
}
