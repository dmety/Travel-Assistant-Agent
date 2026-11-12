package com.travelassistant.server.application.intent;

/**
 * 一条历史对话，只保留角色与文本。
 *
 * <p>为什么记「说了什么」而不只记槽位：指代要靠语言本身才能解。
 * 「有没有那种很小众的馆子」这句话本身没有任何地名，但它接着上一轮的美食推荐说，
 * 只有把上一轮的话一起给模型看，才可能正确补出「武汉」。
 *
 * <p>历史由调用方（对话页）带进来，服务端不存：会话列表本来就存在浏览器，
 * 服务端再存一份就得处理过期、迁移和多人并发，收益却只是省几十字节的请求体。
 */
public record ConversationTurn(String role, String text) {

  public static final String USER = "user";
  public static final String ASSISTANT = "assistant";

  /** 单条历史的上限：再长就不是「上一句话」了，也防住超长请求体。 */
  public static final int MAX_TEXT = 240;

  public ConversationTurn {
    role = ASSISTANT.equalsIgnoreCase(role == null ? "" : role.strip()) ? ASSISTANT : USER;
    String t = text == null ? "" : text.strip();
    text = t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) : t;
  }

  public boolean fromUser() {
    return USER.equals(role);
  }

  /** 是否值得送给模型：空消息（打字机占位、被清空的草稿）直接跳过。 */
  public boolean usable() {
    return !text.isEmpty();
  }
}
