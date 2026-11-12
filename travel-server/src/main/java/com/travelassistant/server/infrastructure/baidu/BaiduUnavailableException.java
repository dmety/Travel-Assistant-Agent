package com.travelassistant.server.infrastructure.baidu;

/** 百度第二数据源不可用：未配置 AK、请求失败或接口返回错误。由调用方降级，不外抛给用户。 */
public class BaiduUnavailableException extends RuntimeException {
  public BaiduUnavailableException(String message) {
    super(message);
  }

  public BaiduUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
