package com.travelassistant.server.application.itinerary;

/** 行程不存在。 */
public class ItineraryNotFoundException extends RuntimeException {
  private final String id;

  public ItineraryNotFoundException(String id) {
    super("行程不存在: " + id);
    this.id = id;
  }

  public String id() {
    return id;
  }
}
