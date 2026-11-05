package com.travelassistant.server.application.itinerary;

/**
 * 行程已归档：所有写操作被拒绝（V3 第 4 节）。
 *
 * <p>映射为 **409** 而不是 403：这不是权限问题，创建者自己也改不了。
 * 冲突的语义是「当前资源状态不允许这个操作」，用户看到后知道该先取消归档。
 */
public class ItineraryArchivedException extends RuntimeException {
  private final String itineraryId;

  public ItineraryArchivedException(String itineraryId) {
    super("行程已归档，当前为只读状态；如需修改请先取消归档");
    this.itineraryId = itineraryId;
  }

  public String itineraryId() {
    return itineraryId;
  }
}
