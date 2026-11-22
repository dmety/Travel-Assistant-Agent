package com.travelassistant.server.application.purchase;

import com.travelassistant.server.application.purchase.spi.TicketIssuer;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 自动签发不具备景区入园效力的演示票编号。 */
@Component("demo")
public class DemoTicketIssuer implements TicketIssuer {
  @Override
  public String issue(String orderId, String attractionName, String productName, int quantity) {
    return "DEMO-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
  }

  @Override
  public boolean simulated() {
    return true;
  }
}
