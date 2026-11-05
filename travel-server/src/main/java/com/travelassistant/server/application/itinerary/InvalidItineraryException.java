package com.travelassistant.server.application.itinerary;

/**
 * 行程数据校验失败（标题为空、费用为负、住宿日期区间倒置、邀请未注册用户等）。
 * 映射为 HTTP 400。
 */
public class InvalidItineraryException extends RuntimeException {
  public InvalidItineraryException(String message) {
    super(message);
  }
}
