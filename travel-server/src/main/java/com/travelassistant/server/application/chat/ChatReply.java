package com.travelassistant.server.application.chat;

import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import java.util.List;

/**
 * 一次对话的结果。
 *
 * @param reply  自然语言回答；{@code null} 表示模型不可用，调用方应回退到规则链路
 * @param cards  要渲染的结构化卡片（天气、景点、行程、餐馆），顺序即展示顺序
 * @param trace  这次查了哪些工具，供界面显示「查了天气」这类可核验的痕迹
 * @param state  本轮结束后的已知槽位，供下一轮沿用
 * @param source {@code agent} 模型驱动 / {@code unavailable} 模型不可用
 * @param note   给人看的说明（模型不可用的原因、被截断的工具调用等）
 * @param disclaimers 本轮工具与 Provider 逐层上报的结构化核验声明 code，由界面去重后渲染一次
 */
public record ChatReply(
    String reply,
    List<ChatTools.Card> cards,
    List<ToolTrace> trace,
    ConversationState state,
    String source,
    String note,
    List<String> disclaimers) {

  /** 契约 1.1 前的 6 参构造：核验声明缺省时从 trace 汇总，所有既有 return 不用改。 */
  public ChatReply(String reply, List<ChatTools.Card> cards, List<ToolTrace> trace,
      ConversationState state, String source, String note) {
    this(reply, cards, trace, state, source, note, null);
  }

  /** 一次工具调用的人可读记录，携带该次返回的核验声明 code。 */
  public record ToolTrace(String tool, String label, boolean ok, String summary,
      List<String> disclaimers) {
    public ToolTrace(String tool, String label, boolean ok) {
      this(tool, label, ok, null, List.of());
    }

    public ToolTrace(String tool, String label, boolean ok, String summary) {
      this(tool, label, ok, summary, List.of());
    }

    public ToolTrace {
      disclaimers = disclaimers == null ? List.of() : List.copyOf(disclaimers);
    }
  }

  public ChatReply {
    cards = cards == null ? List.of() : List.copyOf(cards);
    trace = trace == null ? List.of() : List.copyOf(trace);
    state = state == null ? ConversationState.empty() : state;
    if (disclaimers == null) {
      // 逐层透传的最后一环：按工具调用顺序汇总，只取成功的调用，同类 code 只留一次。
      java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>();
      for (ToolTrace t : trace) {
        if (t != null && t.ok()) codes.addAll(t.disclaimers());
      }
      disclaimers = List.copyOf(codes);
    } else {
      disclaimers = List.copyOf(disclaimers);
    }
  }
}
