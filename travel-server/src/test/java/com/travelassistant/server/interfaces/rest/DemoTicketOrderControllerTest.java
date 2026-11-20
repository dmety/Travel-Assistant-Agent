package com.travelassistant.server.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.travelassistant.server.application.purchase.DemoTicketOrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

@DisplayName("沙箱购票控制器")
class DemoTicketOrderControllerTest {

  private final DemoTicketOrderService service = mock(DemoTicketOrderService.class);
  private final DemoTicketOrderController controller = new DemoTicketOrderController(service);
  private final UserDetails user = User.withUsername("alice").password("pw").roles("USER").build();

  @Test
  @DisplayName("所有订单 API 都以当前登录用户名调用服务层")
  void delegatesProductAndOrderOperationsForCurrentUser() {
    DemoTicketOrderService.CreateRequest create =
        new DemoTicketOrderService.CreateRequest("attraction-1", "景点", "DEMO_ADMISSION", 2);
    DemoTicketOrderService.PaymentRequest payment =
        new DemoTicketOrderService.PaymentRequest("event-1", DemoTicketOrderService.DEMO_UNIT_AMOUNT, "CNY");

    controller.products();
    controller.list(user);
    controller.create(user, create);
    controller.get(user, "order-1");
    controller.simulatePayment(user, "order-1", payment);
    controller.simulateFailure(user, "order-1");
    controller.cancel(user, "order-1");
    controller.refund(user, "order-1");

    verify(service).products();
    verify(service).list("alice");
    verify(service).create("alice", create);
    verify(service).get("alice", "order-1");
    verify(service).confirmSandboxPayment("alice", "order-1", payment);
    verify(service).failSandboxPayment("alice", "order-1");
    verify(service).cancel("alice", "order-1");
    verify(service).refund("alice", "order-1");
  }
}
