package com.travelassistant.server.agent.spi;

import com.travelassistant.server.application.agent.spi.AgentContext;
import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.AgentResult;
import com.travelassistant.server.application.agent.spi.HttpAgentAdapter;
import com.travelassistant.server.application.agent.spi.RuleFailure;
import com.travelassistant.server.application.agent.spi.RunBudget;
import com.travelassistant.server.application.agent.spi.Usage;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** 准入闸门测试共用的本地桩。生产路径不注入桩，只在测试里替代真实 HTTP 调用。 */
final class AgentTestStubs {
  private AgentTestStubs() {
  }

  static AgentProvider answering(Function<AgentContext, AgentResult> fn) {
    return new AgentProvider() {
      @Override
      public AgentDescriptor descriptor() {
        return new AgentDescriptor("stub.provider", "1.0.0", "1.0",
            Set.of("FOOD_RECOMMEND"), "BEAN", "NONE", true, AgentDescriptor.ACTIVE, null);
      }

      @Override
      public AgentResult invoke(AgentContext context, RunBudget budget) {
        return fn.apply(context);
      }
    };
  }

  /** 能通过 GC-01 至 GC-08 的合规桩：不编造 facts、不回显注入词、不含禁用词。 */
  static AgentProvider compliant() {
    return answering(context -> {
      String runId = context.runId();
      String advice = "已按已核实事实整理建议";
      List<String> unknown = List.of();
      if ("gc03-run-0001".equals(runId)) {
        advice = "票价未知，请以现场公示为准";
      } else if ("gc02-run-0001".equals(runId)) {
        advice = "现有事实不足，请补充需求";
        unknown = List.of("destination", "budget");
      }
      return new AgentResult("OK", List.of(), advice, unknown, List.of(), new Usage(0L, 0));
    });
  }

  /** 抛契约违规异常的桩，模拟 Provider 返回被校验器拒绝的响应。 */
  static AgentProvider violating(String code) {
    return answering(context -> {
      throw new HttpAgentAdapter.ContractViolationException(
          List.of(RuleFailure.block(code, "TEST", "response", code, "合规响应")));
    });
  }

  static AgentProvider throwing() {
    return answering(context -> {
      throw new IllegalStateException("provider exploded");
    });
  }
}
