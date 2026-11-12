package com.travelassistant.server.application.agent.spi;

import java.time.Instant;

/**
 * 事实证据。字段取值由 {@code tools/agent/provider-contract.json} 的 enums 段定义，
 * Java 侧不另立枚举，避免契约单一源被硬编码复制出第二份。
 */
public record Evidence(
    String sourceKind,
    String sourceId,
    String verificationStatus,
    Instant fetchedAt,
    String excerpt) {
  public static final String VERIFIED = "verified";
  public static final String UNVERIFIED = "unverified";
  public static final String PENDING = "pending";
}
