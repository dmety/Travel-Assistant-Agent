package com.travelassistant.server.application.ticket;

/** 购票辅助的查询参数不合法（既没给景点 id 也没给名称）。 */
public class InvalidTicketQueryException extends RuntimeException {
  public InvalidTicketQueryException(String message) {
    super(message);
  }
}
