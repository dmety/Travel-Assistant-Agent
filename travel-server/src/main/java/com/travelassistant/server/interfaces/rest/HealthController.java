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

  public HealthController(@Value("${travel.data-directory}") String dataDirectory) {
    this.dataDirectory = Paths.get(dataDirectory).toAbsolutePath().normalize();
  }

  // 就绪探针：数据目录可写才 UP（JSON 持久化是唯一写依赖），不可写返回 503 供反代/监控摘流；
  // 景点知识库只读、缺失时仅搜索降级，不纳入就绪判定。
  @GetMapping("/api/health")
  public ResponseEntity<HealthResponse> health() {
    boolean ready = Files.isWritable(dataDirectory);
    HealthResponse body = new HealthResponse(ready ? "UP" : "DOWN", "travel-assistant");
    return ready ? ResponseEntity.ok(body) : ResponseEntity.status(503).body(body);
  }
}