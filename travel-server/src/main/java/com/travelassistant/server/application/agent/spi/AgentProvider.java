package com.travelassistant.server.application.agent.spi;


/** 唯一扩展点：编排层只认识这个接口，不 import 任何具体实现。 */
public interface AgentProvider {
  AgentDescriptor descriptor();
  AgentResult invoke(AgentContext context, RunBudget budget);
}
