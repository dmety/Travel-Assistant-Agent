package com.travelassistant.server.application.agent.spi;

import java.util.List;
import org.springframework.stereotype.Component;

/** 纯规则兜底：所有 Provider 都失败时给出「只有事实、没有建议」的响应。 */
@Component
public class BuiltinRulesProvider implements AgentProvider {
  private final AgentDescriptor descriptor;

  public BuiltinRulesProvider(ContractRules rules) {
    this.descriptor = new AgentDescriptor("builtin.rules", "1.0.0",
        rules.contractVersion(), rules.enums("capability"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null);
  }

  @Override
  public AgentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public AgentResult invoke(AgentContext context, RunBudget budget) {
    return AgentResult.refused(context.facts(), List.of("行程建议"),
        "全部 Provider 不可用，本次只返回已核实事实，不生成建议");
  }
}
