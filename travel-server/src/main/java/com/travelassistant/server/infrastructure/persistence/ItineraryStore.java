package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.itinerary.Itinerary;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 行程仓库：所有行程落盘到 {@code <data-directory>/itineraries.json}（数组）。
 * 读取走内存副本，写入先落临时文件再原子替换，避免并发损坏。
 */
@Repository
public class ItineraryStore {
  private static final Logger log = LoggerFactory.getLogger(ItineraryStore.class);

  private final ObjectMapper mapper;
  private final Path file;
  private volatile List<Itinerary> items = new ArrayList<>();

  public ItineraryStore(ObjectMapper mapper, @Value("${travel.data-directory}") String dataDirectory) {
    this.mapper = mapper;
    this.file = Paths.get(dataDirectory, "itineraries.json").toAbsolutePath();
  }

  @PostConstruct
  public void init() throws IOException {
    Path dir = file.getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    if (Files.exists(file)) {
      Itinerary[] loaded = mapper.readValue(file.toFile(), Itinerary[].class);
      items = new ArrayList<>(List.of(loaded));
      log.info("已从 {} 加载 {} 条行程", file, items.size());
    } else {
      write();
      log.info("行程文件不存在，已创建空文件 {}", file);
    }
  }

  public Optional<Itinerary> findById(String id) {
    return items.stream().filter(i -> i.id().equals(id)).findFirst();
  }

  public List<Itinerary> findAll() {
    return List.copyOf(items);
  }

  /** 落盘保存（按 id 存在则替换）。写入期间持有锁，保证文件最终一致。 */
  public synchronized void save(Itinerary itinerary) {
    List<Itinerary> updated = new ArrayList<>(items);
    updated.removeIf(i -> i.id().equals(itinerary.id()));
    updated.add(itinerary);
    items = updated;
    write();
  }

  public synchronized void delete(String id) {
    items = items.stream().filter(i -> !i.id().equals(id)).toList();
    write();
  }

  private void write() {
    try {
      Path dir = file.getParent();
      Path tmp = Files.createTempFile(dir, "itineraries", ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), items);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new IllegalStateException("无法写入行程文件: " + file, e);
    }
  }
}
