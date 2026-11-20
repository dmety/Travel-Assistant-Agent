package com.travelassistant.server.application.purchase.spi;

/** 出票适配边界；本项目仅注入返回显式演示票号的实现。 */
public interface TicketIssuer {
  String issue(String orderId, String attractionName, String productName, int quantity);

  boolean simulated();
}
