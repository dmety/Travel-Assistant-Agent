package com.travelassistant.server.interfaces.rest;

import com.travelassistant.common.api.HealthResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

  private final Path dataDirectory;
  private final String persistence;
  private final java.util.Optional<org.springframework.jdbc.core.JdbcTemplate> jdbc;

  public HealthController(String dataDirectory) {
    this(dataDirectory, "json", java.util.Optional.empty());
  }

  @org.springframework.beans.factory.annotation.Autowired
  public HealthController(@Value("${travel.data-directory}") String dataDirectory,
      @Value("${travel.persistence:json}") String persistence,
      java.util.Optional<org.springframework.jdbc.core.JdbcTemplate> jdbc) {
    this.dataDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize();
    this.persistence = persistence;
    this.jdbc = jdbc;
  }

  // 按实际存储模式检查依赖；MySQL 不依赖业务 JSON 目录。
  // SELECT 1 检查连接可用性，不替代建表及写权限验证；景点检索降级不阻止就绪。
  @GetMapping("/api/health")
  public ResponseEntity<HealthResponse> health() {
    boolean ready;
    if ("mysql".equals(persistence)) {
      try {
        ready = jdbc.isPresent() && Integer.valueOf(1).equals(jdbc.get().queryForObject("SELECT 1", Integer.class));
      } catch (org.springframework.dao.DataAccessException e) {
        ready = false;
      }
    } else {
      ready = Files.isWritable(dataDirectory);
    }
    HealthResponse body = new HealthResponse(ready ? "UP" : "DOWN", "travel-assistant");
    return ready ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
  }
}