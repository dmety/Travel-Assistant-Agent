package com.travelassistant.server.application.agent;

/** 规划请求不合法（例如没有填写目的地）。 */
public class InvalidPlanningRequestException extends RuntimeException {
  public InvalidPlanningRequestException(String message) {
    super(message);
  }
}
