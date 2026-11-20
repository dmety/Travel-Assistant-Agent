package com.travelassistant.server.interfaces.rest;

import com.travelassistant.server.application.chat.ChatAgent;
import com.travelassistant.server.application.chat.ChatReply;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
   * @param images  当前问题附带的图片 data URL（可选；不会进入会话历史或日志）
   */
  public record ChatRequest(String message, List<ConversationTurn> history,
      ConversationState state, List<String> images) {
    public ChatRequest(String message, List<ConversationTurn> history, ConversationState state) {
      this(message, history, state, List.of());
    }
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
    List<String> images;
    try { images = validateImages(request.images()); }
    catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    List<ConversationTurn> history = request.history() == null ? List.of() : request.history();
    if (history.size() > MAX_HISTORY_TURNS) {
      // 只截最近 MAX_HISTORY_TURNS 轮：单轮文本已由 ConversationTurn 限到 240 字，
      // 这里再限轮数，送给模型的上下文体积就有确定上界。
      history = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
    }
    ConversationState state = request.state() == null ? ConversationState.empty() : request.state();
    ChatReply reply = images.isEmpty() ? agent.chat(message, history, state)
        : agent.chat(message, history, state, images);
    return ResponseEntity.ok(reply);
  }

  /** 面向 CLI 的 SSE 接口：先发送简短状态，再逐段发送最终答复。 */
  @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter chatStream(@RequestBody(required = false) ChatRequest request,
      HttpServletResponse response) {
    String message = request == null || request.message() == null ? "" : request.message().strip();
    if (message.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "请提供 message：你想问的那句话");
    }
    if (message.length() > MAX_MESSAGE_CHARS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "message 过长：最多 " + MAX_MESSAGE_CHARS + " 字，请精简后重试");
    }

    response.setHeader("Cache-Control", "no-cache");
    List<ConversationTurn> history = request.history() == null ? List.of() : request.history();
    if (history.size() > MAX_HISTORY_TURNS) {
      history = history.subList(history.size() - MAX_HISTORY_TURNS, history.size());
    }
    ConversationState state = request.state() == null ? ConversationState.empty() : request.state();
    List<String> images;
    try { images = validateImages(request.images()); }
    catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    SseEmitter emitter = new SseEmitter(130_000L);
    emitter.onTimeout(emitter::complete);
    CompletableFuture.runAsync(() -> {
      try {
        send(emitter, "progress", Map.of("message", "正在分析并查询资料…"));
        ChatReply reply = images.isEmpty()
            ? agent.chatStream(message, history, state, delta -> {
                try { send(emitter, "token", Map.of("text", delta)); }
                catch (IOException e) { throw new UncheckedIOException(e); }
              })
            : agent.chatStream(message, history, state, images, delta -> {
          try {
            send(emitter, "token", Map.of("text", delta));
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reply", reply.reply());
        result.put("cards", reply.cards());
        result.put("trace", reply.trace());
        result.put("state", reply.state());
        result.put("source", reply.source());
        result.put("note", reply.note());
        send(emitter, "done", result);
        emitter.complete();
      } catch (Exception e) {
        String messageText = e.getMessage() == null || e.getMessage().isBlank()
            ? "回复生成中断，请重试。" : e.getMessage();
        if (messageText.length() > 180) {
          messageText = messageText.substring(0, 180) + "…";
        }
        try {
          send(emitter, "error", Map.of("message", messageText));
        } catch (Exception ignored) {
          // 客户端可能已取消连接；不再对已关闭的 SSE 响应写入。
        }
        emitter.complete();
      }
    });
    return emitter;
  }

  private static void send(SseEmitter emitter, String event, Object data) throws IOException {
    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
  }

  private static List<String> validateImages(List<String> images) {
    if (images == null || images.isEmpty()) return List.of();
    if (images.size() > 2) throw new IllegalArgumentException("每条问题最多附带 2 张图片");
    List<String> accepted = new ArrayList<>();
    int totalBytes = 0;
    for (String image : images) {
      if (image == null || image.length() > 3_000_000) throw new IllegalArgumentException("单张图片过大，最多 2 MB");
      java.util.regex.Matcher matcher = java.util.regex.Pattern
          .compile("^data:image/(png|jpeg|jpg|gif|webp);base64,([A-Za-z0-9+/]*={0,2})$")
          .matcher(image);
      if (!matcher.matches()) throw new IllegalArgumentException("图片必须是 PNG、JPEG、GIF 或 WebP data URL");
      byte[] decoded;
      try { decoded = Base64.getDecoder().decode(matcher.group(2)); }
      catch (IllegalArgumentException e) { throw new IllegalArgumentException("图片编码无效"); }
      totalBytes += decoded.length;
      if (decoded.length == 0 || decoded.length > 2 * 1024 * 1024 || totalBytes > 4 * 1024 * 1024)
        throw new IllegalArgumentException("图片总大小不能超过 4 MB，单张不能超过 2 MB");
      accepted.add(image);
    }
    return List.copyOf(accepted);
  }
}
