package com.travelassistant.server.application.agent.spi;

import java.util.List;

/** 所有 Provider 的统一返回。字符串枚举值由规范源校验，不在这里另立枚举。 */
public record AgentResult(
    String status,
    List<Fact> facts,
    String advice,
    List<String> unknownFields,
    List<String> notices,
    Usage usage) {

  /** 全部 Provider 都失败时的纯规则兜底：只有事实、没有建议。 */
  public static AgentResult refused(List<Fact> facts, List<String> unknownFields, String notice) {
    return new AgentResult("REFUSED", facts, null, unknownFields, List.of(notice),
        new Usage(0L, 0));
  }
}
