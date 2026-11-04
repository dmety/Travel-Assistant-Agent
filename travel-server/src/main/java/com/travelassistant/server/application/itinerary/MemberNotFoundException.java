package com.travelassistant.server.application.itinerary;

/**
 * 行程存在，但指定的成员不在成员列表中（修改/移除一个并不存在的成员）。
 * 与「行程不存在」区分开，避免把成员级 404 误报成行程级 404。映射为 HTTP 404。
 */
public class MemberNotFoundException extends RuntimeException {
  private final String itineraryId;
  private final String username;

  public MemberNotFoundException(String itineraryId, String username) {
    super("成员不存在：" + username);
    this.itineraryId = itineraryId;
    this.username = username;
  }

  public String itineraryId() {
    return itineraryId;
  }

  public String username() {
    return username;
  }
}
