package com.travelassistant.server.application.purchase;

import com.travelassistant.server.application.purchase.spi.PaymentGateway;
import com.travelassistant.server.application.purchase.InvalidDemoOrderException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/** 不连接支付网络的沙箱适配器。生成的 URI 仅是演示二维码载荷。 */
@Component("sandbox")
public class SandboxPaymentGateway implements PaymentGateway {
  @Override
  public String createPaymentUri(String orderId, BigDecimal amount) {
    return "travel-demo://sandbox/pay/" + orderId + "?amount=" + amount.toPlainString();
  }

  @Override
  public boolean sandbox() {
    return true;
  }

  @Override
  public void verifySandboxConfirmation(String orderId, String eventId,
      BigDecimal amount, String currency) {
    if (eventId == null || eventId.isBlank() || eventId.length() > 128) {
      throw new InvalidDemoOrderException("沙箱确认需要 1 至 128 字符的 eventId");
    }
    if (amount == null || currency == null || !"CNY".equals(currency)) {
      throw new InvalidDemoOrderException("演示支付金额或币种无效");
    }
  }
}
