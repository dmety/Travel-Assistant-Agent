package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.ticket.DemoTicketOrder;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/** 沙箱订单的单进程 JSON 持久化；不适用于真实收款、水平扩容或并发多实例。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "travel.persistence", havingValue = "json", matchIfMissing = true)
public class DemoTicketOrderStore {
  private final ObjectMapper mapper;
  private final Path file;
  private volatile List<DemoTicketOrder> items = new ArrayList<>();

  public DemoTicketOrderStore(ObjectMapper mapper,
      @Value("${travel.data-directory}") String dataDirectory) {
    this.mapper = mapper;
    this.file = Paths.get(dataDirectory, "demo_ticket_orders.json").toAbsolutePath();
  }

  @PostConstruct
  public void init() throws IOException {
    Path parent = file.getParent();
    if (parent != null) Files.createDirectories(parent);
    if (Files.exists(file)) {
      DemoTicketOrder[] loaded = mapper.readValue(file.toFile(), DemoTicketOrder[].class);
      items = new ArrayList<>(List.of(loaded));
    } else {
      write(items);
    }
  }

  public Optional<DemoTicketOrder> find(String id) {
    return items.stream().filter(item -> item.id().equals(id)).findFirst();
  }

  public List<DemoTicketOrder> findByOwner(String owner) {
    return items.stream().filter(item -> item.owner().equals(owner)).toList();
  }

  public synchronized void save(DemoTicketOrder order) {
    List<DemoTicketOrder> updated = new ArrayList<>(items);
    updated.removeIf(item -> item.id().equals(order.id()));
    updated.add(order);
    PersistenceSupport.writeAtomically(file, mapper, updated, "演示订单");
    items = updated;
  }

  private void write(List<DemoTicketOrder> snapshot) {
    PersistenceSupport.writeAtomically(file, mapper, snapshot, "演示订单");
  }
}
