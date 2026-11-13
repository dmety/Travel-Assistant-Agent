package com.travelassistant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F8：密码参数解析——env 引用、stdin 回退、字面量兼容，让明文密码可以不进 argv。 */
@DisplayName("CLI 密码解析")
class TravelClientPasswordTest {

  @Test
  @DisplayName("env:VAR 从环境变量读取密码")
  void readsFromNamedEnvVar() {
    Map<String, String> env = Map.of("MY_PASS", "s3cret-value");
    assertEquals("s3cret-value",
        TravelClient.resolvePassword("env:MY_PASS", env::get, () -> null));
  }

  @Test
  @DisplayName("env: 空变量名回退到 TRAVEL_CLI_PASSWORD")
  void envWithoutNameFallsBackToDefault() {
    Map<String, String> env = Map.of("TRAVEL_CLI_PASSWORD", "default-pass");
    assertEquals("default-pass",
        TravelClient.resolvePassword("env:", env::get, () -> null));
  }

  @Test
  @DisplayName("env:VAR 未设置时响亮失败，不静默用空密码")
  void missingEnvVarThrows() {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
        () -> TravelClient.resolvePassword("env:MISSING", k -> null, () -> null));
    assertTrue(e.getMessage().contains("MISSING"));
  }

  @Test
  @DisplayName("- 优先取 TRAVEL_CLI_PASSWORD")
  void dashPrefersEnv() {
    Map<String, String> env = Map.of("TRAVEL_CLI_PASSWORD", "env-pass");
    assertEquals("env-pass",
        TravelClient.resolvePassword("-", env::get, () -> "typed-pass"));
  }

  @Test
  @DisplayName("- 无环境变量时回退到输入端")
  void dashFallsBackToInput() {
    assertEquals("typed-pass",
        TravelClient.resolvePassword("-", k -> null, () -> "typed-pass"));
  }

  @Test
  @DisplayName("- 既无环境变量也无输入时响亮失败")
  void dashWithoutAnySourceThrows() {
    assertThrows(IllegalArgumentException.class,
        () -> TravelClient.resolvePassword("-", k -> null, () -> null));
  }

  @Test
  @DisplayName("字面量原样返回（兼容旧脚本）")
  void literalIsReturnedAsIs() {
    assertEquals("literal-pass",
        TravelClient.resolvePassword("literal-pass", k -> null, () -> null));
  }
}