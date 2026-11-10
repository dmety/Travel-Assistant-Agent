package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.place.PlaceResolution;
import com.travelassistant.server.application.place.PlaceResolver;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 地名识别接口：{@code POST /api/places/resolve}。
 *
 * <p>存在的理由：对话页原来只靠前端静态别名表 + 知识库精确反查，
 * 「黄梅」这种库内没有的县级地名会直接判成「不认识」，把问题丢回给用户。
 * 现在由后端用「知识库 → 模型判断 → 字面相近」三级来定性，
 * 前端拿到 {@code city} 就能继续规划，拿到 {@code alternatives} 就能直接给按钮。
 *
 * <p>不需要登录之外的额外权限：只读知识库，模型调用有缓存与上限。
 */
@RestController
@RequestMapping("/api/places")
public class PlaceController {

  private final PlaceResolver resolver;

  public PlaceController(PlaceResolver resolver) {
    this.resolver = resolver;
  }

  /** 请求体：整句原文 + 前端已剥离出的候选词，二者至少给一个。 */
  public record ResolveRequest(String text, List<String> candidates) {
  }

  @PostMapping("/resolve")
  public ResponseEntity<Object> resolve(@RequestBody(required = false) ResolveRequest request) {
    String text = request == null || request.text() == null ? "" : request.text().strip();
    List<String> candidates = request == null || request.candidates() == null
        ? List.of() : request.candidates();
    boolean noText = text.isEmpty();
    boolean noCandidate = candidates.stream().allMatch(c -> c == null || c.isBlank());
    if (noText && noCandidate) {
      return ResponseEntity.badRequest().body(Map.of("error", "请提供地名：text 或 candidates"));
    }
    PlaceResolution result = resolver.resolve(text, candidates);
    return ResponseEntity.ok(result);
  }
}
