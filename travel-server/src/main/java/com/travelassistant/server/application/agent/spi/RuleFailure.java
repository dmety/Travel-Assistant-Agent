package com.travelassistant.server.application.agent.spi;

/** 一次规则违规：原因码、规则 id、字段路径、实际值、期望值与修复提示。 */
public record RuleFailure(
    String code,
    String rule,
    String level,
    String field,
    String actual,
    String expected,
    String hint) {

  public static final String BLOCK = "BLOCK";
  public static final String WARN = "WARN";

  /** 按规范源查到的原因码构造一条 BLOCK 失败。 */
  public static RuleFailure block(String code, String rule, String field,
      String actual, String expected) {
    return new RuleFailure(code, rule, BLOCK, field, actual, expected, "");
  }
}
