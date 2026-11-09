package com.travelassistant.server.application.agent.spi;

import java.util.List;
import java.util.Optional;

/** 注册表：只暴露 ACTIVE Provider 给路由，其余状态仅在能力协商接口可见。 */
public interface AgentRegistry {
  Optional<AgentProvider> find(String providerId);
  List<AgentDescriptor> descriptors();
  Resolution resolve(String intent, String userIdHash);
  List<AdmissionReport> reports();
  AdmissionReport admission(String providerId);
  String version();
  String note();
  void reload();
}
