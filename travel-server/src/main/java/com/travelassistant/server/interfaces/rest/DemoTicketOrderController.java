package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.purchase.DemoTicketOrderService;
import com.travelassistant.server.domain.ticket.DemoTicketOrder;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录用户可访问的沙箱购票 API；没有真实支付回调、真实退款或正式景区出票。 */
@RestController
@RequestMapping("/api/demo-orders")
public class DemoTicketOrderController {
  private final DemoTicketOrderService service;

  public DemoTicketOrderController(DemoTicketOrderService service) {
    this.service = service;
  }

  @GetMapping("/products")
  public List<DemoTicketOrderService.Product> products() {
    return service.products();
  }

  @GetMapping
  public List<DemoTicketOrder> list(@AuthenticationPrincipal UserDetails user) {
    return service.list(user.getUsername());
  }

  @PostMapping
  public DemoTicketOrder create(@AuthenticationPrincipal UserDetails user,
      @RequestBody DemoTicketOrderService.CreateRequest request) {
    return service.create(user.getUsername(), request);
  }

  @GetMapping("/{id}")
  public DemoTicketOrder get(@AuthenticationPrincipal UserDetails user, @PathVariable String id) {
    return service.get(user.getUsername(), id);
  }

  @PostMapping("/{id}/simulate-payment")
  public DemoTicketOrder simulatePayment(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id, @RequestBody DemoTicketOrderService.PaymentRequest payment) {
    return service.confirmSandboxPayment(user.getUsername(), id, payment);
  }

  @PostMapping("/{id}/simulate-failure")
  public DemoTicketOrder simulateFailure(@AuthenticationPrincipal UserDetails user,
      @PathVariable String id) {
    return service.failSandboxPayment(user.getUsername(), id);
  }

  @PostMapping("/{id}/cancel")
  public DemoTicketOrder cancel(@AuthenticationPrincipal UserDetails user, @PathVariable String id) {
    return service.cancel(user.getUsername(), id);
  }

  @PostMapping("/{id}/refund")
  public DemoTicketOrder refund(@AuthenticationPrincipal UserDetails user, @PathVariable String id) {
    return service.refund(user.getUsername(), id);
  }
}
