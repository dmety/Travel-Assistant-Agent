package com.travelassistant.server.application.decision;

/** 候选或投票参数不合法（名称为空、重复添加、选票不存在等），映射为 400。 */
public class InvalidCandidateException extends RuntimeException {
  public InvalidCandidateException(String message) {
    super(message);
  }
}
