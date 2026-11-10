package com.travelassistant.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.chat.ChatAgent;
import com.travelassistant.server.application.chat.ChatReply;
import com.travelassistant.server.application.chat.ChatTools;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient.ModelTurn;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 对话 Agent：模型决定查什么，Java 决定「最多查几次、拿到什么算什么、不许编」。
 */
@DisplayName("对话 Agent")
class ChatAgentTest {

  private DeepSeekClient model;
  private ChatTools tools;
  private ChatAgent agent;

  @BeforeEach
  void setUp() {
    model = mock(DeepSeekClient.class);
    tools = mock(ChatTools.class);
    when(model.configured()).thenReturn(true);
    agent = new ChatAgent(model, tools, new ObjectMapper());
  }

  private static ModelTurn say(String text) {
    return new ModelTurn(text, List.of());
  }

  private static ModelTurn call(String id, String name, String args) {
    return new ModelTurn("", List.of(new ToolCall(id, name, args)));
  }

  @Test
  @DisplayName("不需要工具时直接回答：不查任何数据，也不给卡片")
  void answersWithoutTools() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(say("我是这个旅行助手的对话入口，还没起名字。"));

    ChatReply reply = agent.chat("你叫什么名字", List.of(), ConversationState.empty());

    assertThat(reply.reply()).contains("旅行助手");
    assertThat(reply.source()).isEqualTo("agent");
    assertThat(reply.trace()).isEmpty();
    assertThat(reply.cards()).isEmpty();
    verify(tools, never()).execute(any(), any());
  }

  @Test
  @DisplayName("要查数据：先执行工具、把结果回灌，再让模型照着结果说话")
  void toolThenAnswer() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "find_food", "{\"city\":\"武汉\"}"))
        .thenReturn(say("找到 8 家，先试试热干面和豆皮。"));
    when(tools.execute("find_food", "{\"city\":\"武汉\"}")).thenReturn(new ChatTools.Outcome(
        true, "武汉 的餐馆：热干面、豆皮", new ChatTools.Card("envelope", "FOOD_RECOMMEND",
        Map.of("status", "OK"))));

    ChatReply reply = agent.chat("武汉有什么好吃的", List.of(), ConversationState.empty());

    assertThat(reply.reply()).contains("热干面");
    assertThat(reply.trace()).hasSize(1);
    assertThat(reply.trace().get(0).label()).isEqualTo("找餐馆");
    assertThat(reply.cards()).hasSize(1);
    assertThat(reply.state().city()).isEqualTo("武汉");
  }

  @Test
  @DisplayName("工具失败也照样回答：把「没查到」如实交给模型，而不是抛异常")
  void toolFailureStillAnswers() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "list_attractions", "{\"city\":\"婺源\"}"))
        .thenReturn(say("婺源还没收录，我说不了有哪些景点。"));
    when(tools.execute(any(), any())).thenReturn(
        new ChatTools.Outcome(false, "知识库里没有该城市的景点", null));

    ChatReply reply = agent.chat("婺源有什么好玩的", List.of(), ConversationState.empty());

    assertThat(reply.reply()).contains("还没收录");
    assertThat(reply.trace().get(0).ok()).isFalse();
    assertThat(reply.cards()).isEmpty();
  }

  @Test
  @DisplayName("模型不可用：reply 为空，交给调用方回退，不是报错")
  void unavailableModel() {
    when(model.configured()).thenReturn(false);

    ChatReply reply = agent.chat("武汉三天", List.of(), ConversationState.empty());

    assertThat(reply.reply()).isNull();
    assertThat(reply.source()).isEqualTo("unavailable");
    assertThat(reply.note()).contains("DEEPSEEK_API_KEY");
    verify(model, never()).chat(anyList(), anyList(), anyInt());
  }

  @Test
  @DisplayName("模型调用抛错：同样回退，不把异常抛给界面")
  void modelThrows() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenThrow(new AgentUnavailableException("模型账户余额不足"));

    ChatReply reply = agent.chat("武汉三天", List.of(), ConversationState.empty());

    assertThat(reply.reply()).isNull();
    assertThat(reply.note()).contains("余额");
  }

  @Test
  @DisplayName("回复里的网址一律剥掉：本项目没有那种页面，链接就是幻觉")
  void stripsLinks() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(say("我没有价格表，详见 https://fake.example.com/p 这个页面"));

    ChatReply reply = agent.chat("你的价格表在哪查的", List.of(), ConversationState.empty());

    assertThat(reply.reply()).doesNotContain("http").doesNotContain("fake.example.com");
    assertThat(reply.reply()).contains("价格表");
  }

  @Test
  @DisplayName("历史与已确认槽位一起进提示词：「那呢」才有落点")
  void historyReachesModel() {
    when(model.chat(anyList(), anyList(), anyInt())).thenReturn(say("明天天津晴，8 到 18 度。"));

    agent.chat("那明天呢",
        List.of(new ConversationTurn("user", "今天天津天气怎么样"),
            new ConversationTurn("assistant", "今天晴，8 到 17 度。")),
        new ConversationState("天津", null, null, null, List.of()));

    ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.captor();
    verify(model).chat(messages.capture(), anyList(), anyInt());
    String joined = messages.getValue().toString();
    assertThat(joined).contains("今天天津天气怎么样").contains("那明天呢").contains("天津");
    assertThat(messages.getValue().get(0).get("role")).isEqualTo("system");
  }

  @Test
  @DisplayName("工具一直要调：次数到上限就收尾，不让模型无限绕圈")
  void stopsAfterToolBudget() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "get_weather", "{\"city\":\"天津\"}"));
    when(tools.execute(any(), any())).thenReturn(new ChatTools.Outcome(true, "天津 晴 18℃", null));

    ChatReply reply = agent.chat("天津天气", List.of(), ConversationState.empty());

    // 5 轮 × 每次 1 个调用，上限 6 次：第 7 个调用被拦下，最后再要一次纯文本收尾
    verify(model, times(6)).chat(anyList(), anyList(), anyInt());
    verify(model, times(1)).chat(anyList(), eq(List.of()), anyInt());
    assertThat(reply.reply()).isNotNull();
  }

  @Test
  @DisplayName("空消息：直接给一句引导，不浪费一次模型调用")
  void blankMessage() {
    ChatReply reply = agent.chat("   ", List.of(), ConversationState.empty());

    assertThat(reply.reply()).contains("城市");
    verify(model, never()).chat(anyList(), anyList(), anyInt());
  }
}
