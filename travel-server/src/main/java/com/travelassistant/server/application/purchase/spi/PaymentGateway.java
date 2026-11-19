package com.travelassistant.server.application.purchase.spi;

import java.math.BigDecimal;

/** 支付适配边界。真实适配器必须自行验签、核对商户/订单/币种/金额并提供退款核实。 */
public interface PaymentGateway {
  String createPaymentUri(String orderId, BigDecimal amount);

  boolean sandbox();

  void verifySandboxConfirmation(String orderId, String eventId, BigDecimal amount, String currency);
}
