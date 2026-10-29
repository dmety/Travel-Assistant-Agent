package com.travelassistant.server.interfaces.rest;
import com.travelassistant.common.api.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class HealthController {
 @GetMapping("/api/health")
 public HealthResponse health() { return new HealthResponse("UP", "travel-assistant"); }
}
