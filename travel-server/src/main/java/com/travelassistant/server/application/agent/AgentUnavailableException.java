package com.travelassistant.server.application.agent;

/**
 * 模型服务不可用（未配置密钥、调用失败、返回格式异常）。
 *
 * <p>这不会让规划整体失败：{@code PlanningService} 会降级为「只有检索结果、没有模型建议」，
 * 把原因写进结果的 {@code notices}，而不是把整次请求变成错误。
 */
public class AgentUnavailableException extends RuntimeException {
  public AgentUnavailableException(String message) {
    super(message);
  }

  public AgentUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
