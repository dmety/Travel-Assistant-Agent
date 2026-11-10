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
 */
public record ChatReply(
    String reply,
    List<ChatTools.Card> cards,
    List<ToolTrace> trace,
    ConversationState state,
    String source,
    String note) {

  /** 一次工具调用的人可读记录。只记做了什么，不记内部数据。 */
  public record ToolTrace(String tool, String label, boolean ok) {
  }

  public ChatReply {
    cards = cards == null ? List.of() : List.copyOf(cards);
    trace = trace == null ? List.of() : List.copyOf(trace);
    state = state == null ? ConversationState.empty() : state;
  }
}
