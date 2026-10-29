package com.travelassistant.client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.common.api.HealthResponse;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
/** Connectivity entry point. Business menus will be added during V1. */
public class TravelClient {
 public static void main(String[] args) {
  String base = args.length > 0 ? args[0] : "http://localhost:8080";
  try {
   var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
   var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
   var request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/api/health"))
    .timeout(Duration.ofSeconds(10)).GET().build();
   var response = client.send(request, HttpResponse.BodyHandlers.ofString());
   if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
   var health = new ObjectMapper().readValue(response.body(), HealthResponse.class);
   System.out.println("旅行助手：已连接服务 " + health.application() + "，状态 " + health.status());
   System.out.println("当前是工程骨架，注册、行程与 Agent 业务尚未实现。");
  } catch (InterruptedException e) {
   Thread.currentThread().interrupt(); System.err.println("连接已中断"); System.exit(1);
  } catch (IOException | IllegalArgumentException e) {
   System.err.println("无法连接旅行服务：" + e.getMessage()); System.exit(1);
  }
 }
}
