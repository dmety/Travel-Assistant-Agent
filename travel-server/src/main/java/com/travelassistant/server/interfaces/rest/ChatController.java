package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.chat.ChatAgent;
import com.travelassistant.server.application.chat.ChatReply;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话入口：{@code POST /api/chat}。
 *
 * <p>与 {@code /api/intent/parse} 的分工：那个把一句话拆成结构化槽位，本身不回答任何问题；
 * 这个真正回答问题——模型看着工具清单自己决定查什么，查完再讲。
 * 两者不是替代关系：模型不可用时对话页回退到「解析 + 编排」那条确定性链路。
 *
 * <p>需要登录：每次对话都会触发模型与地图配额，匿名放行等于把成本摊给所有人。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

  private final ChatAgent agent;

  public ChatController(ChatAgent agent) {
    this.agent = agent;
  }

  /**
   * 请求体。
   *
   * @param message 用户这句话
   * @param history 最近几轮对话（可选），用于理解省略与指代
   * @param state   本轮已确认的槽位（可选），用于补这一句没说的事情
   */
  public record ChatRequest(String message, List<ConversationTurn> history,
      ConversationState state) {
  }

  @PostMapping
  public ResponseEntity<Object> chat(@RequestBody(required = false) ChatRequest request) {
    String message = request == null || request.message() == null ? "" : request.message().strip();
    if (message.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("error", "请提供 message：你想问的那句话"));
    }
    ChatReply reply = agent.chat(message,
        request.history() == null ? List.of() : request.history(),
        request.state() == null ? ConversationState.empty() : request.state());
    return ResponseEntity.ok(reply);
  }
}
