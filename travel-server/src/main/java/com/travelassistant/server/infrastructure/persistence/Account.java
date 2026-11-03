package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 账号在磁盘上的形状，与 {@code accounts.json} 一一对应。
 * 密码以 BCrypt 哈希存储，绝不保存明文。
 */
public record Account(
    String username,
    String passwordHash,
    List<String> roles
) {
  @JsonCreator
  public Account(
      @JsonProperty("username") String username,
      @JsonProperty("passwordHash") String passwordHash,
      @JsonProperty("roles") List<String> roles) {
    this.username = username;
    this.passwordHash = passwordHash;
    this.roles = roles == null ? List.of() : roles;
  }
}
