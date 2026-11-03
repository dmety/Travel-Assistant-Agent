package com.travelassistant.server.application.account;

/** 用户名已被占用。 */
public class DuplicateAccountException extends RuntimeException {
  public DuplicateAccountException(String username) {
    super("用户名已存在: " + username);
  }
}
