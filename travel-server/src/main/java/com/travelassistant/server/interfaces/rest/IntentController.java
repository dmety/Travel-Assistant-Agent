package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.intent.IntentParser;
import com.travelassistant.server.application.intent.IntentResolution;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自然语言解析接口：{@code POST /api/intent/parse}。
 *
 * <p>存在的理由：对话页原来靠前端正则抠天数、人数、预算、偏好，再分几次请求反查地名。
 * 用户说「下周六带爸妈去武当山玩四天，预算六千」这种正常说法时，前端只能认出「四天」。
 * 现在一次请求拿到意图、全部槽位与已核实的目的地，前端只负责渲染。
 *
 * <p>语义理解本身由 {@link IntentParser} 完成（模型优先、规则降级），本类只做入参校验。
 */
@RestController
@RequestMapping("/api/intent")
public class IntentController {

  private final IntentParser parser;

  public IntentController(IntentParser parser) {
    this.parser = parser;
  }

  /** 请求体：一句话原文。 */
  public record ParseRequest(String text) {
  }

  @PostMapping("/parse")
  public ResponseEntity<Object> parse(@RequestBody(required = false) ParseRequest request) {
    String text = request == null || request.text() == null ? "" : request.text().strip();
    if (text.isEmpty()) {
      return ResponseEntity.badRequest()
          .body(Map.of("error", "请提供 text：一句话说明想去哪、玩几天"));
    }
    IntentResolution resolution = parser.parse(text);
    return ResponseEntity.ok(resolution);
  }
}
