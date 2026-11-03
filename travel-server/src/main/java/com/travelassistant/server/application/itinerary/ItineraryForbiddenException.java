package com.travelassistant.server.application.itinerary;

/** 当前用户对该行程/板块无权限。 */
public class ItineraryForbiddenException extends RuntimeException {
  public ItineraryForbiddenException() {
    super("无权限");
  }
}
