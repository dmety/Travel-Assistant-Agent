package com.travelassistant.server.application.agent.spi;

import java.util.List;
import java.util.function.Function;

/**
 * 内置能力 Provider：把既有应用服务的调用包成 SPI 形状，既有 REST 接口行为完全不变。
 *
 * <p>异常统一降级成「只有事实、没有建议」的 REFUSED，且不把异常文本或栈追踪写进
 * notices —— 对应 GC-05「不得 500、不得外泄栈追踪」。具体映射由
 * {@link BuiltinProviderConfiguration} 提供，这里只负责边界与降级。
 */
public class BuiltinServiceProvider implements AgentProvider {
  private final AgentDescriptor descriptor;
  private final Function<AgentContext, AgentResult> delegate;

  public BuiltinServiceProvider(AgentDescriptor descriptor,
      Function<AgentContext, AgentResult> delegate) {
    this.descriptor = descriptor;
    this.delegate = delegate;
  }

  @Override
  public AgentDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public AgentResult invoke(AgentContext context, RunBudget budget) {
    try {
      return delegate.apply(context);
    } catch (RuntimeException e) {
      List<Fact> facts = context.facts() == null ? List.of() : context.facts();
      return AgentResult.refused(facts, List.of("内置能力建议"),
          "内置能力调用失败，本次只返回已核实事实");
    }
  }
}
