package com.travelassistant.server.application.agent.spi;

import java.util.List;

/**
 * 只带 descriptor 的轻量 Provider，供注册表集成测试构造内置 Bean 桩，也是外部注册的最小骨架。
 *
 * <p>三个内置能力已由 {@link BuiltinProviderConfiguration} 委托到真实的
 * PlanningService / DecisionService / TicketAssistantService，不再使用本类。
 * 这里仍返回 REFUSED 而不是编造建议，保持「占位不等于已实现」的语义。
 */
public class DescriptorOnlyProvider implements AgentProvider {
  private final AgentDescriptor descriptor;

  public DescriptorOnlyProvider(AgentDescriptor descriptor) {
    this.descriptor = descriptor;
  }

  @Override
  public AgentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public AgentResult invoke(AgentContext context, RunBudget budget) {
    return AgentResult.refused(context.facts(), List.of("结构化建议"),
        "该内置能力在 P0 阶段仍由既有接口提供，编排层委托待 P1 接入");
  }
}
