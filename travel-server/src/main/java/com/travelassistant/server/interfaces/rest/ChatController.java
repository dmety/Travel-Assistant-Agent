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

  /** 单条消息上限：再长就不是「一句话」了，也挡住超长请求体打模型与地图配额。 */
  private static final int MAX_MESSAGE_CHARS = 2000;
  /** 历史轮数上限：只保留最近若干轮，既够解指代，又不让客户端提供的列表无限膨胀。 */
  private static final int MAX_HISTORY_TURNS = 50;

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
    if (message.length() > MAX_MESSAGE_CHARS) {
      return ResponseEntity.badRequest().body(Map.of("error",
          "message 过长：最多 " + MAX_MESSAGE_CHARS + " 字，请精简后重试"));
    }
    List<ConversationTurn> history = request.history() == null ? List.of() : request.history();
    if (history.size() > MAX_HISTORY_TURNS) {
      // 只截最近 MAX_HISTORY_TURNS 轮：单轮文本已由 ConversationTurn 限到 240 字，
      // 这里再限轮数，送给模型的上下文体积就有确定上界。
      history = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
    }
    ChatReply reply = agent.chat(message, history,
        request.state() == null ? ConversationState.empty() : request.state());
    return ResponseEntity.ok(reply);
  }
}
