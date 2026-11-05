package com.travelassistant.server.domain.location;

/** 路线方式：与高德 Web 服务接口一一对应。 */
public enum RouteMode {
  /** 步行。 */
  WALKING,
  /** 驾车。 */
  DRIVING,
  /** 公交（含地铁）。 */
  TRANSIT;

  /** 中文名称，用于展示与错误提示。 */
  public String label() {
    return switch (this) {
      case WALKING -> "步行";
      case DRIVING -> "驾车";
      case TRANSIT -> "公交";
    };
  }
}
