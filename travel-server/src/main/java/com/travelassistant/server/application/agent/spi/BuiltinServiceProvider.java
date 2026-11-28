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
  private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(BuiltinServiceProvider.class);
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
      String reason=failureCode(e);
      String location=java.util.Arrays.stream(e.getStackTrace()).filter(frame -> frame.getClassName().startsWith("com.travelassistant."))
          .findFirst().map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.')+1)+"."+frame.getMethodName()+".L"+frame.getLineNumber()).orElse("unknown").replaceAll("[^A-Za-z0-9_.]","_");
      log.warn("内置 Provider 拒绝 provider={} run={} reason={} location={}",descriptor.id(),context.runId(),reason,location);
      List<Fact> facts = context.facts() == null ? List.of() : context.facts();
      return AgentResult.refused(facts, List.of("内置能力建议"),
          "内置能力调用失败，本次只返回已核实事实 ["+reason+(reason.contains(":")?".":":")+location+"]");
    }
  }
  static String failureCode(RuntimeException error) {
    Throwable cause=error;
    while(!(cause instanceof com.fasterxml.jackson.databind.JsonMappingException) && cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
    if(cause instanceof com.fasterxml.jackson.databind.JsonMappingException mapping) {
      String path=mapping.getPath().stream().map(ref -> ref.getFieldName()==null?"[]":ref.getFieldName())
          .filter(name -> name.matches("[A-Za-z_][A-Za-z_0-9]*|\\[\\]")).collect(java.util.stream.Collectors.joining("."));
      return "BUILTIN_INPUT_MAPPING"+(path.isEmpty()?"":":"+path);
    }
    if(error instanceof IllegalArgumentException) return "BUILTIN_INVALID_ARGUMENT";
    if(error instanceof NullPointerException) return "BUILTIN_NULL_VALUE";
    if(error instanceof java.util.concurrent.RejectedExecutionException) return "BUILTIN_QUERY_BUSY";
    return "BUILTIN_EXECUTION_FAILED";
  }

}
