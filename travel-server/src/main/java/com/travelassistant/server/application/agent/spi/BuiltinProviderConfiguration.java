package com.travelassistant.server.application.agent.spi;

import com.travelassistant.server.application.agent.DecisionReport;
import com.travelassistant.server.application.agent.DecisionRequest;
import com.travelassistant.server.application.agent.DecisionService;
import com.travelassistant.server.application.agent.DraftQuestion;
import com.travelassistant.server.application.agent.PlanDraft;
import com.travelassistant.server.application.agent.PlanningRequest;
import com.travelassistant.server.application.agent.PlanningService;
import com.travelassistant.server.application.ticket.TicketAssistantService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 三个内置能力的委托映射：把 {@link AgentContext} 转成既有服务的请求，再把结果转回
 * {@link AgentResult}。既有 REST 端点仍直接调这三个服务，行为不变；SPI 只是新增一条调用路径。
 *
 * <p>不可用的分支一律返回 DEGRADED 或 REFUSED，不编造建议。facts 只回传输入里已带证据的事实，
 * 不把服务结果伪装成新事实；唯一新增的 fact 是票务匹配的结构化计数，证据标 unverified。
 *
 * <p>身份边界：{@link DecisionService#review} 需要真实用户名，而 {@link AgentContext} 只带
 * {@code userIdHash}。拿 hash 冒充 user 会越权查到别人的行程，因此缺少显式 user 与
 * itineraryId 时直接 REFUSED；编排层 P2 接入真实 principal 后再开放这条路径。
 */
@Configuration
public class BuiltinProviderConfiguration {

  @Bean
  public AgentProvider builtinPlanning(ContractRules rules, PlanningService planning) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.planning", "PLAN_DRAFT"),
        context -> planning(planning, context));
  }

  @Bean
  public AgentProvider builtinDecision(ContractRules rules, DecisionService decisions) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.decision", "DECISION_ADVICE"),
        context -> decision(decisions, context));
  }

  @Bean
  public AgentProvider builtinTicket(ContractRules rules, TicketAssistantService tickets) {
    return new BuiltinServiceProvider(descriptor(rules, "builtin.ticket", "TICKET_ADVICE"),
        context -> ticket(tickets, context));
  }

  private static AgentDescriptor descriptor(ContractRules rules, String id, String capability) {
    return new AgentDescriptor(id, "1.0.0", rules.contractVersion(), Set.of(capability),
        "BEAN", "NONE", true, AgentDescriptor.ACTIVE, null);
  }

  // ---------------------------------------------------------------- 映射

  private static AgentResult planning(PlanningService service, AgentContext context) {
    Map<String, Object> input = values(context);
    PlanningRequest request = new PlanningRequest(
        text(input.get("destination")), integer(input.get("days")),
        strings(input.get("preferences")), decimal(input.get("budget")),
        text(input.get("currency")), integer(input.get("travelers")));
    PlanDraft draft = service.plan(request);
    List<String> unknown = new ArrayList<>();
    for (DraftQuestion question : draft.questions()) {
      unknown.add(question.field());
    }
    return result(context, draft.advice(), unknown, draft.notices(), List.of());
  }

  private static AgentResult decision(DecisionService service, AgentContext context) {
    Map<String, Object> input = values(context);
    String itineraryId = text(input.get("itineraryId"));
    String user = text(input.get("user"));
    if (itineraryId == null || itineraryId.isBlank() || user == null || user.isBlank()) {
      return AgentResult.refused(facts(context), List.of("itineraryId", "user"),
          "决策建议需要真实用户身份与行程 id，AgentContext 的 userIdHash 不能冒充 user");
    }
    Boolean includeWeather = input.get("includeWeather") instanceof Boolean b ? b : null;
    DecisionRequest request = new DecisionRequest(integer(input.get("travelers")), includeWeather);
    DecisionReport report = service.review(itineraryId, user, request);
    List<String> unknown = new ArrayList<>();
    for (DecisionReport.Risk risk : report.risks()) {
      if ("HIGH".equals(risk.severity())) {
        unknown.add(risk.code());
      }
    }
    return result(context, report.advice(), unknown, report.notices(), List.of());
  }

  private static AgentResult ticket(TicketAssistantService service, AgentContext context) {
    Map<String, Object> input = values(context);
    TicketAssistantService.Request request = new TicketAssistantService.Request(
        text(input.get("message")), null, text(input.get("itineraryId")),
        text(input.get("activityId")));
    TicketAssistantService.Result outcome = service.advise(request, text(input.get("user")));
    List<String> questions = outcome.questions() == null ? List.of() : outcome.questions();
    List<String> notices = new ArrayList<>(
        outcome.notices() == null ? List.of() : outcome.notices());
    notices.add("票价明细与渠道请使用既有购票接口，本响应只带结构化计数");
    int matches = outcome.matches() == null ? 0 : outcome.matches().size();
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("matchCount", matches);
    value.put("questionCount", questions.size());
    value.put("stockVerified", outcome.stockVerified());
    Fact fact = new Fact("builtin.ticket:match-summary", "CONTENT", value,
        new Evidence("TICKET_RULE", "builtin.ticket", Evidence.UNVERIFIED,
            Instant.now(), "购票规则匹配结果计数"));
    String advice = questions.isEmpty()
        ? "已按当前条件整理购票建议，请核对结构化计数与既有购票接口"
        : null;
    return result(context, advice, questions, notices, List.of(fact));
  }

  private static AgentResult result(AgentContext context, String advice,
      List<String> unknown, List<String> notices, List<Fact> extraFacts) {
    List<Fact> facts = new ArrayList<>(facts(context));
    facts.addAll(extraFacts);
    String status = advice == null || advice.isBlank() ? "DEGRADED" : "OK";
    return new AgentResult(status, facts, advice, unknown, notices, new Usage(0L, 0));
  }

  // ---------------------------------------------------------------- 取值

  private static Map<String, Object> values(AgentContext context) {
    return context.context() == null ? Map.of() : context.context();
  }

  private static List<Fact> facts(AgentContext context) {
    return context.facts() == null ? List.of() : context.facts();
  }

  private static String text(Object value) {
    return value instanceof String s ? s : null;
  }

  private static Integer integer(Object value) {
    return value instanceof Number n ? n.intValue() : null;
  }

  private static BigDecimal decimal(Object value) {
    if (value instanceof Number n) {
      return BigDecimal.valueOf(n.doubleValue());
    }
    if (value instanceof String s && !s.isBlank()) {
      try {
        return new BigDecimal(s);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (Object item : list) {
      if (item != null) {
        result.add(String.valueOf(item));
      }
    }
    return result;
  }
}
