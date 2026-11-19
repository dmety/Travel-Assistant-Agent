package com.travelassistant.server.application.purchase;

/** 沙箱订单参数或状态转换不合法。 */
public class InvalidDemoOrderException extends RuntimeException {
  public InvalidDemoOrderException(String message) {
    super(message);
  }
}
