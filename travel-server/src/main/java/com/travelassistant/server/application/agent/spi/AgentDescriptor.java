package com.travelassistant.server.application.agent.spi;

import java.util.Set;

/** 注册表快照里的 Provider 描述。status 为规范状态机里的字符串状态。 */
public record AgentDescriptor(
    String id,
    String version,
    String schemaVersion,
    Set<String> capabilities,
    String kind,
    String sideEffect,
    boolean enabled,
    String status,
    String lastRejectionCode) {

  public static final String ACTIVE = "ACTIVE";
  public static final String REJECTED = "REJECTED";
  public static final String VALIDATING = "VALIDATING";
  public static final String PROBING = "PROBING";
  public static final String SHADOW = "SHADOW";
  public static final String SUSPENDED = "SUSPENDED";
  public static final String DEPRECATED = "DEPRECATED";

  public boolean active() {
    return ACTIVE.equals(status);
  }
}
