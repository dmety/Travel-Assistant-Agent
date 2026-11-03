package com.travelassistant.server.application.account;

/** 注册请求不合法（用户名格式、密码强度等）。 */
public class InvalidAccountException extends RuntimeException {
  public InvalidAccountException(String message) {
    super(message);
  }
}
