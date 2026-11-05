package com.travelassistant.server.application.location;

/**
 * 高德查询不可用（未配置密钥、服务不可达、配额不足或返回错误）。
 *
 * <p>统一映射为 503 并带原因：这类失败是外部依赖问题，不是用户输入错误，
 * 也不能退化成「查无结果」——那会让用户以为当地真的没有这个地方。
 */
public class AmapUnavailableException extends RuntimeException {
  public AmapUnavailableException(String message) {
    super(message);
  }

  public AmapUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
