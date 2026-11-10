package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient.ModelTurn;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient.ToolCall;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 对话 Agent：模型自己决定查什么、查几次，然后照着查到的数据回答。
 *
 * <p>为什么不写成「先分类意图、再按意图走一条固定分支」：那样每加一种问题就要加一个分支，
 * 用户稍微换个说法（「有没有那种很小众的馆子」「你家是哪的」「那明天呢」）就掉进兜底话术。
 * 这里改成工具调用——模型看着工具清单自己决定：先核实地名、再查景点、再排行程；
 * 或者先查天气、发现要下雨、再建议改室内。分支写在模型的推理里，不写死在 Java 里。
 *
 * <p>但边界仍然在 Java：
 * <ul>
 *   <li>工具最多调 6 次、最多 5 轮，总预算 45 秒，防止模型绕圈；</li>
 *   <li>所有数字、地名、票价、餐馆都来自工具，模型编不出来；</li>
 *   <li>回复会剥掉 Markdown 与网址（模型爱补一个并不存在的「官方链接」）；</li>
 *   <li>模型不可用时 {@code reply=null}，调用方回退到既有的意图解析 + 编排链路。</li>
 * </ul>
 */
@Service
public class ChatAgent {
  private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

  /** 模型最多来回几轮。实测正常问答 2~3 轮足够，多出来的通常是模型在重复查同一件事。 */
  private static final int MAX_STEPS = 5;
  private static final int MAX_TOOL_CALLS = 6;
  /** 带进模型的历史轮数：再多对指代消解没有帮助，只会拖慢首字。 */
  private static final int MAX_HISTORY = 10;
  private static final int REPLY_TOKENS = 900;
  private static final int MAX_REPLY = 800;
  private static final long BUDGET_MS = 45_000L;

  private static final String SYSTEM_PROMPT = """
      你是「旅行助手」的对话 Agent。用户用中文提问，你可以调用工具查真实数据，然后用自己的话回答。

      工具使用
      1. 凡是涉及具体事实的问题——某地有什么景点、门票多少、天气如何、附近有什么馆子、
         几天怎么安排——必须先调用对应工具，不许凭记忆回答。
      2. 地名归属不确定时先调 resolve_place；它是权威口径，不要自己判断「婺源属于上饶」这类归属。
      3. 用户只说「玩几天」「怎么安排」但没给天数：先问一句玩几天，不要默认成 3 天。
      4. 查完发现数据不足（工具说没查到），就如实告诉用户「这个我没查到」，并说明知识库覆盖有限；
         不要用常识补齐景点名、票价或餐厅。
      5. 同一个工具不要用同样的参数重复调用。最多 6 次工具调用。

      回答方式
      6. 用口语，称呼「你」，最多 4 句话；不要标题、表格、编号清单、emoji、Markdown 加粗。
      7. 数字必须与工具结果完全一致；票价和开放时间是知识库原文快照，提到时要说明可能过期。
      8. 天气是预报不是实况，说明时要讲清楚。
      9. 用户问「你叫什么」「你家在哪」「你有什么用」这类，直接、简短、诚实回答，不要推销功能。
      10. 用户这句话有省略（「那呢」「还有别的吗」「再玩两天」「有没有小馆子」）时，看最近对话补全意思；
          接着聊同一座城市就直接用上文城市，不要反问「哪个城市」。

      你不知道也不要假装知道的
      11. 你没有价格表页面、没有官方购票链接、不能代订或退款、没有实时余票与库存。
          被问到时直说，不要给网址。
      """;

  private final DeepSeekClient model;
  private final ChatTools tools;
  private final ObjectMapper mapper;

  public ChatAgent(DeepSeekClient model, ChatTools tools, ObjectMapper mapper) {
    this.model = model;
    this.tools = tools;
    this.mapper = mapper;
  }

  /**
   * 跑一轮对话。
   *
   * @param message 用户这句话
   * @param history 最近几轮对话（越靠后越近）
   * @param state   本轮已确认的槽位，作为「接着聊」的依据
   */
  public ChatReply chat(String message, List<ConversationTurn> history, ConversationState state) {
    String text = message == null ? "" : message.strip();
    ConversationState known = state == null ? ConversationState.empty() : state;
    if (text.isEmpty()) {
      return new ChatReply("想聊点什么？说个城市和天数，我来排行程。", List.of(), List.of(),
          known, "agent", null);
    }
    if (model == null || !model.configured()) {
      return new ChatReply(null, List.of(), List.of(), known, "unavailable",
          "未配置模型密钥（DEEPSEEK_API_KEY），已回退到规则解析");
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt(known)));
    for (ConversationTurn turn : trim(history)) {
      messages.add(Map.of("role", turn.role(), "content", turn.text()));
    }
    messages.add(Map.of("role", "user", "content", text));

    List<ChatTools.Card> cards = new ArrayList<>();
    List<ChatReply.ToolTrace> trace = new ArrayList<>();
    ConversationState carried = known;
    String lastContent = null;
    int calls = 0;
    long deadline = System.currentTimeMillis() + BUDGET_MS;

    for (int step = 0; step < MAX_STEPS; step++) {
      ModelTurn turn;
      try {
        turn = model.chat(messages, ChatTools.SPECS, REPLY_TOKENS);
      } catch (AgentUnavailableException e) {
        log.warn("对话 Agent 调用失败：{}", e.getMessage());
        return new ChatReply(null, cards, trace, carried, "unavailable", e.getMessage());
      } catch (RuntimeException e) {
        log.warn("对话 Agent 调用异常：{}", e.getMessage());
        return new ChatReply(null, cards, trace, carried, "unavailable", "模型调用异常");
      }
      if (turn.content() != null && !turn.content().isBlank()) {
        lastContent = turn.content();
      }
      if (!turn.wantsTools()) {
        return reply(lastContent, cards, trace, carried, null);
      }
      messages.add(assistantMessage(turn));

      for (ToolCall call : turn.toolCalls()) {
        if (calls >= MAX_TOOL_CALLS) {
          messages.add(Map.of("role", "tool", "tool_call_id", call.id(),
              "content", "工具调用次数已达上限，请直接用已有信息回答。"));
          continue;
        }
        calls++;
        ChatTools.Outcome outcome = tools.execute(call.name(), call.arguments());
        messages.add(Map.of("role", "tool", "tool_call_id", call.id(),
            "content", outcome.summary()));
        trace.add(new ChatReply.ToolTrace(call.name(), label(call.name()), outcome.ok()));
        if (outcome.card() != null) {
          cards.add(outcome.card());
        }
        carried = track(carried, call.name(), call.arguments());
      }
      if (System.currentTimeMillis() > deadline) {
        messages.add(Map.of("role", "user",
            "content", "时间到了，请直接用已经查到的信息回答，不要再调用工具。"));
        break;
      }
    }

    // 轮数或时间用尽：再给模型一次不带工具的机会，把已有数据收个尾
    try {
      ModelTurn last = model.chat(messages, List.of(), REPLY_TOKENS);
      if (last.content() != null && !last.content().isBlank()) {
        lastContent = last.content();
      }
    } catch (RuntimeException e) {
      log.warn("对话 Agent 收尾失败：{}", e.getMessage());
    }
    return reply(lastContent, cards, trace, carried, "这次查得比较久，先给你已经查到的结果。");
  }

  private ChatReply reply(String content, List<ChatTools.Card> cards,
      List<ChatReply.ToolTrace> trace, ConversationState state, String note) {
    String cleaned = clean(content);
    if (cleaned == null) {
      cleaned = cards.isEmpty()
          ? "这个我没查到。我的知识库只覆盖部分城市，可以换个说法或换个城市再问。"
          : "查到的东西都在下面了，没能组织成一句话。";
    }
    return new ChatReply(cleaned, cards, trace, state, "agent", note);
  }

  /** 系统提示里附上「本轮已确认」，让「那呢」「再玩两天」有落点。 */
  private String systemPrompt(ConversationState state) {
    if (state.isEmpty()) {
      return SYSTEM_PROMPT;
    }
    List<String> parts = new ArrayList<>();
    if (state.city() != null) {
      parts.add("城市=" + state.city());
    }
    if (state.days() != null) {
      parts.add("天数=" + state.days());
    }
    if (state.travelers() != null) {
      parts.add("人数=" + state.travelers());
    }
    if (state.budget() != null) {
      parts.add("预算=" + state.budget().stripTrailingZeros().toPlainString());
    }
    if (!state.preferences().isEmpty()) {
      parts.add("偏好=" + String.join("、", state.preferences()));
    }
    return SYSTEM_PROMPT + "\n本轮已确认（只在这句话明显指代上文时才沿用）："
        + String.join("；", parts) + "\n";
  }

  private Map<String, Object> assistantMessage(ModelTurn turn) {
    List<Map<String, Object>> calls = new ArrayList<>();
    for (ToolCall call : turn.toolCalls()) {
      Map<String, Object> function = new LinkedHashMap<>();
      function.put("name", call.name());
      function.put("arguments", call.arguments());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", call.id());
      item.put("type", "function");
      item.put("function", function);
      calls.add(item);
    }
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("role", "assistant");
    message.put("content", turn.content() == null ? "" : turn.content());
    message.put("tool_calls", calls);
    return message;
  }

  /** 从工具参数里记下这一轮确认了什么，供下一轮沿用。参数不合法就原样返回。 */
  private ConversationState track(ConversationState state, String tool, String rawArgs) {
    JsonNode args;
    try {
      args = mapper.readTree(rawArgs == null || rawArgs.isBlank() ? "{}" : rawArgs);
    } catch (Exception e) {
      return state;
    }
    if (args == null || !args.isObject()) {
      return state;
    }
    String city = args.path("city").asText("");
    if (city.isBlank()) {
      city = args.path("name").asText("");
    }
    if (!city.isBlank()) {
      state = new ConversationState(city, state.days(), state.travelers(), state.budget(),
          state.preferences());
    }
    if (!"draft_itinerary".equals(tool)) {
      return state;
    }
    Integer days = args.path("days").asInt(0) > 0 ? args.path("days").asInt() : null;
    Integer travelers = args.path("travelers").asInt(0) > 0 ? args.path("travelers").asInt() : null;
    BigDecimal budget = args.path("budget").isNumber()
        ? args.path("budget").decimalValue() : null;
    Set<String> prefs = new LinkedHashSet<>(state.preferences());
    JsonNode rawPrefs = args.path("preferences");
    if (rawPrefs.isArray()) {
      rawPrefs.forEach(item -> {
        String value = item.asText("").strip();
        if (!value.isEmpty()) {
          prefs.add(value);
        }
      });
    }
    return new ConversationState(state.city(),
        days == null ? state.days() : days,
        travelers == null ? state.travelers() : travelers,
        budget == null ? state.budget() : budget,
        List.copyOf(prefs));
  }

  private List<ConversationTurn> trim(List<ConversationTurn> history) {
    if (history == null || history.isEmpty()) {
      return List.of();
    }
    List<ConversationTurn> usable = new ArrayList<>();
    for (ConversationTurn turn : history) {
      if (turn != null && turn.usable()) {
        usable.add(turn);
      }
    }
    return usable.size() <= MAX_HISTORY ? usable
        : usable.subList(usable.size() - MAX_HISTORY, usable.size());
  }

  /**
   * 清理模型回答：去掉 Markdown 记号与网址，压成一段话。
   *
   * <p>网址一律剥掉——被问「官方链接」时模型最容易编一个看起来很像的 URL，
   * 而这个项目根本没有那种页面，放出去就是把幻觉当事实。
   */
  private String clean(String content) {
    if (content == null || content.isBlank()) {
      return null;
    }
    String s = content.replace("**", "").replace("`", "")
        .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
        .replaceAll("(?m)^\\s*[-*]\\s+", "· ")
        .replaceAll("https?://\\S+", "")
        .replaceAll("www\\.\\S+", "")
        .replaceAll("[ \\t]+", " ")
        .strip();
    if (s.length() < 2) {
      return null;
    }
    return s.length() > MAX_REPLY ? s.substring(0, MAX_REPLY) + "…" : s;
  }

  private String label(String tool) {
    return switch (tool == null ? "" : tool) {
      case "resolve_place" -> "核实地名";
      case "list_attractions" -> "查景点";
      case "get_weather" -> "查天气";
      case "find_food" -> "找餐馆";
      case "draft_itinerary" -> "排行程";
      default -> tool;
    };
  }
}
