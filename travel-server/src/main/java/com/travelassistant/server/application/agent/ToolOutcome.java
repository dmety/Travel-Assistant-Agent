package com.travelassistant.server.application.agent;

/**
 * 一次工具调用的结果。
 *
 * <p>把「查到空结果」和「工具本身不可用」分开：前者是数据事实（本库未收录），
 * 后者是环境问题（未配置密钥、库损坏）。混成一个空列表会让用户以为当地真的什么都没有。
 *
 * @param value 成功时的结果，失败时为 {@code null}
 * @param error 失败原因（面向用户的中文），成功时为 {@code null}
 */
public record ToolOutcome<T>(T value, String error) {

  public static <T> ToolOutcome<T> ok(T value) {
    return new ToolOutcome<>(value, null);
  }

  public static <T> ToolOutcome<T> failed(String error) {
    return new ToolOutcome<>(null, error);
  }

  public boolean ok() {
    return error == null;
  }
}
