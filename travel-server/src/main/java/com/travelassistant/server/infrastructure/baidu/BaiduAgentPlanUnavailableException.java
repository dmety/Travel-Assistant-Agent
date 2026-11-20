package com.travelassistant.server.infrastructure.baidu;

/** Agent Plan 未配置或不可用时的安全错误信息。 */
public class BaiduAgentPlanUnavailableException extends RuntimeException {

  public BaiduAgentPlanUnavailableException(String message) {
    super(message);
  }
}
