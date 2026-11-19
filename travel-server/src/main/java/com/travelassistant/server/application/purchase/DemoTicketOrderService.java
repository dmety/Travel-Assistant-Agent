package com.travelassistant.server.application.purchase;

import com.travelassistant.server.application.purchase.spi.PaymentGateway;
import com.travelassistant.server.application.purchase.spi.TicketIssuer;
import com.travelassistant.server.domain.ticket.DemoTicketOrder;
import com.travelassistant.server.infrastructure.persistence.DemoTicketOrderStore;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 沙箱交易闭环。演示金额为固定 CNY 1.00/件，不是景区票价或供应商报价。 */
@Service
public class DemoTicketOrderService {
  public static final String PRODUCT_CODE = "DEMO_ADMISSION";
  public static final BigDecimal DEMO_UNIT_AMOUNT = new BigDecimal("1.00");
  private static final String NOTICE = "沙箱演示订单；支付状态为模拟确认；演示票不代表景区正式出票或可入园。";

  public record CreateRequest(String attractionId, String attractionName,
      String productCode, Integer quantity) {}
  public record PaymentRequest(String eventId, BigDecimal amount, String currency) {}
  public record Product(String code, String name, BigDecimal demoUnitAmount,
      String currency, String mode, String notice) {}

  private final DemoTicketOrderStore store;
  private final PaymentGateway paymentGateway;
  private final TicketIssuer ticketIssuer;
  private final String paymentMode;

  public DemoTicketOrderService(DemoTicketOrderStore store,
      Map<String, PaymentGateway> paymentGateways, Map<String, TicketIssuer> ticketIssuers,
      @Value("${travel.payment.mode:sandbox}") String paymentMode,
      @Value("${travel.payment.provider:sandbox}") String paymentProvider,
      @Value("${travel.ticketing.provider:demo}") String ticketProvider) {
    this.store = store;
    this.paymentGateway = requiredAdapter(paymentGateways, paymentProvider, "支付");
    this.ticketIssuer = requiredAdapter(ticketIssuers, ticketProvider, "出票");
    this.paymentMode = paymentMode;
  }

  private static <T> T requiredAdapter(Map<String, T> adapters, String name, String label) {
    T adapter = adapters.get(name);
    if (adapter == null) throw new IllegalStateException("未找到" + label + "适配器：" + name);
    return adapter;
  }

  @PostConstruct
  void requireSandboxUntilRealAdaptersExist() {
    if (!"sandbox".equalsIgnoreCase(paymentMode) || !paymentGateway.sandbox()
        || !ticketIssuer.simulated()) {
      throw new IllegalStateException("当前构建只实现了沙箱支付和演示出票，TRAVEL_PAYMENT_MODE 必须为 sandbox");
    }
  }

  public List<Product> products() {
    return List.of(new Product(PRODUCT_CODE, "景点门票模拟商品",
        DEMO_UNIT_AMOUNT, "CNY", "SANDBOX",
        "固定演示金额，不对应任何景区票价、库存或有效门票。"));
  }

  public synchronized DemoTicketOrder create(String owner, CreateRequest request) {
    if (request == null || request.attractionName() == null
        || request.attractionName().isBlank() || request.attractionName().length() > 120) {
      throw new InvalidDemoOrderException("请提供不超过 120 字的景点名称");
    }
    if (!PRODUCT_CODE.equals(request.productCode())) {
      throw new InvalidDemoOrderException("请选择 DEMO_ADMISSION 演示商品");
    }
    int quantity = request.quantity() == null ? 1 : request.quantity();
    if (quantity < 1 || quantity > 10) throw new InvalidDemoOrderException("数量须为 1 至 10");
    String attractionId = request.attractionId() == null || request.attractionId().isBlank()
        ? null : request.attractionId().strip();
    BigDecimal amount = DEMO_UNIT_AMOUNT.multiply(BigDecimal.valueOf(quantity));
    String id = UUID.randomUUID().toString();
    Instant now = Instant.now();
    DemoTicketOrder order = new DemoTicketOrder(id, owner, attractionId,
        request.attractionName().strip(), PRODUCT_CODE, "景点门票模拟商品", quantity,
        amount, "PAYMENT_PENDING", "SIMULATED_PENDING", "NOT_ISSUED", null,
        paymentGateway.createPaymentUri(id, amount), null, now, now.plus(Duration.ofMinutes(15)), now, NOTICE);
    store.save(order);
    return order;
  }

  public synchronized List<DemoTicketOrder> list(String owner) {
    return store.findByOwner(owner).stream().map(this::expireIfNeeded).toList();
  }

  public synchronized DemoTicketOrder get(String owner, String id) {
    DemoTicketOrder order = store.find(id)
        .orElseThrow(() -> new OrderNotFoundException("演示订单不存在"));
    requireOwner(order, owner);
    return expireIfNeeded(order);
  }

  /** 沙箱确认与出票在一次持久化更新中完成；同一通知重复投递不会重复出票。 */
  public synchronized DemoTicketOrder confirmSandboxPayment(String owner, String id,
      PaymentRequest payment) {
    DemoTicketOrder order = get(owner, id);
    if (!paymentGateway.sandbox()) throw new IllegalStateException("当前未启用沙箱适配器");
    if (payment == null) throw new InvalidDemoOrderException("缺少沙箱支付确认内容");
    paymentGateway.verifySandboxConfirmation(id, payment.eventId(), payment.amount(), payment.currency());
    if (order.amount().compareTo(payment.amount()) != 0) {
      throw new InvalidDemoOrderException("演示确认金额与订单金额不一致");
    }
    if ("SIMULATED_PAID".equals(order.paymentStatus())) return order;
    if (!"PAYMENT_PENDING".equals(order.orderStatus())) {
      throw new InvalidDemoOrderException("当前订单状态不能确认支付：" + order.orderStatus());
    }
    String ticketId = ticketIssuer.issue(order.id(), order.attractionName(),
        order.productName(), order.quantity());
    Instant now = Instant.now();
    DemoTicketOrder paid = new DemoTicketOrder(order.id(), order.owner(), order.attractionId(),
        order.attractionName(), order.productCode(), order.productName(), order.quantity(),
        order.amount(), "DEMO_TICKET_ISSUED", "SIMULATED_PAID", "DEMO_ISSUED", ticketId,
        order.paymentUri(), payment.eventId(), order.createdAt(), order.expiresAt(), now, NOTICE);
    store.save(paid);
    return paid;
  }

  public synchronized DemoTicketOrder cancel(String owner, String id) {
    DemoTicketOrder order = get(owner, id);
    if ("CANCELLED".equals(order.orderStatus())) return order;
    if (!List.of("PAYMENT_PENDING", "PAYMENT_FAILED", "PAYMENT_EXPIRED").contains(order.orderStatus())) {
      throw new InvalidDemoOrderException("已确认支付的订单请走退款流程");
    }
    return replace(order, "CANCELLED", "SIMULATED_CANCELLED", order.ticketStatus(),
        order.ticketId(), order.paymentEventId());
  }

  public synchronized DemoTicketOrder failSandboxPayment(String owner, String id) {
    DemoTicketOrder order = get(owner, id);
    if (!"PAYMENT_PENDING".equals(order.orderStatus())) {
      throw new InvalidDemoOrderException("仅待支付订单可模拟支付失败");
    }
    return replace(order, "PAYMENT_FAILED", "SIMULATED_FAILED", order.ticketStatus(),
        order.ticketId(), order.paymentEventId());
  }

  public synchronized DemoTicketOrder refund(String owner, String id) {
    DemoTicketOrder order = get(owner, id);
    if ("REFUNDED".equals(order.orderStatus())) return order;
    if (!"DEMO_TICKET_ISSUED".equals(order.orderStatus())) {
      throw new InvalidDemoOrderException("仅已模拟支付并出票的订单可在沙箱申请退款");
    }
    return replace(order, "REFUNDED", "SIMULATED_REFUNDED", "DEMO_VOIDED",
        order.ticketId(), order.paymentEventId());
  }

  private DemoTicketOrder replace(DemoTicketOrder order, String status, String payment,
      String ticketStatus, String ticketId, String eventId) {
    DemoTicketOrder updated = new DemoTicketOrder(order.id(), order.owner(), order.attractionId(),
        order.attractionName(), order.productCode(), order.productName(), order.quantity(),
        order.amount(), status, payment, ticketStatus, ticketId, order.paymentUri(), eventId,
        order.createdAt(), order.expiresAt(), Instant.now(), NOTICE);
    store.save(updated);
    return updated;
  }

  private DemoTicketOrder expireIfNeeded(DemoTicketOrder order) {
    if ("PAYMENT_PENDING".equals(order.orderStatus()) && order.expiresAt() != null
        && !Instant.now().isBefore(order.expiresAt())) {
      return replace(order, "PAYMENT_EXPIRED", "SIMULATED_EXPIRED", order.ticketStatus(),
          order.ticketId(), order.paymentEventId());
    }
    return order;
  }

  private static void requireOwner(DemoTicketOrder order, String owner) {
    if (!order.owner().equals(owner)) throw new OrderForbiddenException("无权查看该演示订单");
  }

  public static final class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String message) { super(message); }
  }

  public static final class OrderForbiddenException extends RuntimeException {
    public OrderForbiddenException(String message) { super(message); }
  }
}
