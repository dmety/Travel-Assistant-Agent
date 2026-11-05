package com.travelassistant.server.application.collaboration;

/** 留言内容不合法（为空或超长）。 */
public class InvalidCommentException extends RuntimeException {
  public InvalidCommentException(String message) {
    super(message);
  }
}
