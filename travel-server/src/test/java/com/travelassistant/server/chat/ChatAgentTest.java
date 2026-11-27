package com.travelassistant.server.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
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
  void confirmationDoesNotTurnUndatedIntakeIntoExecutableGuide() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("d", "draft_itinerary", "{\"city\":\"大同\",\"days\":5}"));
    ChatReply reply = agent.chat("确认开始规划",
        List.of(new ConversationTurn("assistant", "【规划条件待确认】日期需连续三天无雨窗口。")),
        ConversationState.empty());
    assertThat(reply.reply()).contains("出发日期尚未确定").doesNotContain("【攻略草案已生成】");
    verify(tools, never()).execute(eq("draft_itinerary"), any(), anyList(), any());
  }

  @Test
  @DisplayName("出现地名直接出草案卡：华山不进需求收集、不等确认")
  void placeNameProducesDraftWithoutIntake() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "draft_itinerary", "{\"city\":\"华山\",\"days\":4}"));
    when(tools.execute(eq("draft_itinerary"), any(), anyList(), any()))
        .thenReturn(new ChatTools.Outcome(true, "华山草案", new ChatTools.Card(
            "envelope", "PLAN_DRAFT", Map.of("status", "OK"))));

    // collecting 阶段 + 无日期：旧逻辑会被两道门禁拦下要求确认
    var collecting = new ConversationState("华山", null, null, null, List.of(),
        Map.of("_structured", true, "_intent", "start", "_phase", "collecting"));

    ChatReply reply = agent.chat("华山", List.of(), collecting);

    assertThat(reply.reply()).contains("【攻略草案已生成】").doesNotContain("待确认");
    assertThat(reply.cards()).hasSize(1);
    verify(tools).execute(eq("draft_itinerary"), any(), anyList(), any());
  }

  @Test
  @DisplayName("省略句承接著名景点也算地名，直接出草案不等确认")
  void ellipsisWithFamousPlaceProducesDraft() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "draft_itinerary", "{\"city\":\"华山\",\"days\":4}"));
    when(tools.execute(eq("draft_itinerary"), any(), anyList(), any()))
        .thenReturn(new ChatTools.Outcome(true, "华山草案", null));

    var collecting = new ConversationState("华山", null, null, null, List.of(),
        Map.of("_structured", true, "_intent", "start", "_phase", "collecting"));

    ChatReply reply = agent.chat("那华山呢",
        List.of(new ConversationTurn("user", "之前聊过行程")), collecting);

    assertThat(reply.reply()).contains("【攻略草案已生成】").doesNotContain("待确认");
    verify(tools).execute(eq("draft_itinerary"), any(), anyList(), any());
  }

  @Test
  @DisplayName("纯问答提到地名也补一版草案卡：天气卡保留，草案卡附加")
  void pureQuestionWithPlaceStillGetsDraftCard() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("w1", "get_weather", "{\"city\":\"大同\"}"))
        .thenReturn(say("大同这两天晴。"));
    when(tools.execute(eq("get_weather"), any()))
        .thenReturn(new ChatTools.Outcome(true, "大同天气预报", new ChatTools.Card("weather", null, Map.of())));
    when(tools.execute(eq("draft_itinerary"), any(), anyList(), any()))
        .thenReturn(new ChatTools.Outcome(true, "大同草案", new ChatTools.Card(
            "envelope", "PLAN_DRAFT", Map.of("status", "OK"))));

    // 本轮只问天气、模型没调 draft_itinerary；state 里承接了地名
    var state = new ConversationState("大同", null, null, null, List.of());
    ChatReply reply = agent.chat("大同天气怎么样", List.of(), state);

    assertThat(reply.reply()).contains("大同");
    assertThat(reply.cards()).hasSize(2);
    assertThat(reply.cards()).anySatisfy(c -> assertThat(c.type()).isEqualTo("weather"));
    assertThat(reply.cards()).anySatisfy(c -> assertThat(c.intent()).isEqualTo("PLAN_DRAFT"));
    verify(tools).execute(eq("draft_itinerary"), any(), anyList(), any());
  }

  @Test
  @DisplayName("明确否定规划时不因地名强行出草案")
  void explicitRejectionSkipsDraft() {
    when(model.chat(anyList(), anyList(), anyInt())).thenReturn(say("好的，那先不排行程。"));

    ChatReply reply = agent.chat("华山门票多少，不用排行程", List.of(), ConversationState.empty());

    assertThat(reply.reply()).doesNotContain("【攻略草案已生成】");
    verify(tools, never()).execute(eq("draft_itinerary"), any(), any(), any());
  }

  @Test
  void explicitlyRequestedPreviewCanKeepDateUndecided() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("d", "draft_itinerary", "{\"city\":\"大同\",\"days\":5}"));
    when(tools.execute(eq("draft_itinerary"), any(), anyList(), any()))
        .thenReturn(new ChatTools.Outcome(true, "日期待定的预览", null));
    ChatReply reply = agent.chat("先出初稿",
        List.of(new ConversationTurn("assistant", "【规划条件待确认】日期待定。")),
        ConversationState.empty());
    assertThat(reply.reply()).contains("【攻略草案已生成】");
    verify(tools).execute(eq("draft_itinerary"), any(), anyList(), any());
  }

  @Test
  @DisplayName("收集旅行条件时仍可查询天气")
  void canCheckWeatherWhilePlanningIntakeIsPending() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("w1", "get_weather", "{\"city\":\"大同\"}"))
        .thenReturn(say("大同天气预报已返回。"));
    when(tools.execute("get_weather", "{\"city\":\"大同\"}"))
        .thenReturn(new ChatTools.Outcome(true, "大同天气预报", null));

    ChatReply reply = agent.chat("我从武汉出发，先查一下最近大同会不会下雨",
        List.of(new ConversationTurn("assistant", "【旅行需求收集】请补充日期和人数。")),
        ConversationState.empty());

    verify(tools).execute("get_weather", "{\"city\":\"大同\"}");
    assertThat(reply.reply()).contains("天气预报");
  }

  @Test
  @DisplayName("条件复述只输出一次确认提示")
  void printsPlanningConfirmationOnce() {
    when(model.chat(anyList(), eq(List.of()), eq(280))).thenReturn(say(
        "【规划条件待确认】已知：武汉出发，大同5天。可补充条件，或回复‘确认开始规划’开始；"
            + "回复‘确认开始规划’按现有信息生成。"));

    ChatReply reply = agent.chat("2026年11月20日出发吧",
        List.of(new ConversationTurn("assistant", "【旅行需求收集】请补充日期和人数。")),
        ConversationState.empty());

    assertThat(reply.reply().split("回复‘确认开始规划’", -1)).hasSize(2);
    assertThat(reply.reply()).doesNotContain("可补充条件");
  }

  @Test
  @DisplayName("上一份攻略完成后，后续问题不再被需求收集拦截")
  void allowsFollowUpAfterDraftWasGenerated() {
    when(model.chat(anyList(), anyList(), anyInt())).thenReturn(say("大同特色美食信息已查询。"));

    ChatReply reply = agent.chat("大同有什么特色美食",
        List.of(
            new ConversationTurn("assistant", "【旅行需求收集】请补充日期和人数。"),
            new ConversationTurn("assistant", "【攻略草案已生成】大同5天攻略已生成。")),
        new ConversationState("大同", 5, 2, null, List.of(), Map.of()));

    assertThat(reply.reply()).contains("特色美食");
    verify(model).chat(anyList(), anyList(), anyInt());
  }

  @Test
  @DisplayName("旧攻略不会阻止用户开启新目的地规划")
  void startsNewIntakeAfterPreviousDraft() {
    ChatReply reply = agent.chat("我想去太原玩3天，帮我安排",
        List.of(new ConversationTurn("assistant", "【攻略草案已生成】大同5天攻略已生成。")),
        ConversationState.empty());

    assertThat(reply.reply()).contains("【旅行需求收集】");
    verify(model, never()).chat(anyList(), anyList(), anyInt());
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
  @DisplayName("同一轮里重复触发投票：只投一次，第二次直接引用结果")
  void voteRunsOncePerTurn() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "vote_plan", "{\"city\":\"武汉\",\"days\":2}"))
        .thenReturn(call("c2", "vote_plan", "{\"city\":\"武汉\",\"days\":2}"))
        .thenReturn(say("三套方案里 A 最省钱，胜出。"));
    when(tools.execute(eq("vote_plan"), any(), anyList())).thenReturn(
        new ChatTools.Outcome(true, "两轮投票完成，胜出方案 A", null));

    ChatReply reply = agent.chat("武汉两天，哪个方案更省钱", List.of(), ConversationState.empty());

    // 投票是这条链路上最贵的动作，每多一次就是一次模型调用加六个评委 invoke
    verify(tools, times(1)).execute(eq("vote_plan"), any(), anyList());
    assertThat(reply.trace()).hasSize(1);
    assertThat(reply.reply()).contains("胜出");
  }

  @Test
  @DisplayName("旧会话存着错城市：第二轮给天数人数也按景点原名排，不跨省")
  void staleCityDoesNotLeakIntoToolCalls() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "draft_itinerary",
            "{\"city\":\"济南\",\"days\":4,\"travelers\":3}"))
        .thenReturn(say("华山4天3人的草案排好了。"));
    when(tools.execute(any(), any(), anyList(), any())).thenReturn(
        new ChatTools.Outcome(true, "华山 的景点：华山风景名胜区", null));
    ConversationState stale = new ConversationState("济南", null, null, null, List.of());

    ChatReply reply = agent.chat("4天3个人，帮我排一下",
        List.of(new ConversationTurn(ConversationTurn.USER, "华山")), stale);

    // 系统提示里不再有「城市=济南」，且明确指定按华山查
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> captor =
        (ArgumentCaptor<List<Map<String, Object>>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
    verify(model, times(1)).chat(captor.capture(), anyList(), anyInt());
    String system = String.valueOf(captor.getAllValues().get(0).get(0).get("content"));
    assertThat(system).doesNotContain("城市=济南").contains("本次聊的是「华山」");
    // 模型即便仍写 city=济南，工具参数也被改回华山；状态同步纠正
    verify(tools).execute(eq("draft_itinerary"),
        argThat(args -> args.contains("华山") && !args.contains("济南")), anyList(), any());
    assertThat(reply.state().city()).isEqualTo("华山");
  }

  @Test
  @DisplayName("工具失败也照样回答：把「没查到」如实交给模型，而不是抛异常")
  void toolFailureStillAnswers() {
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("c1", "list_attractions", "{\"city\":\"婺源\"}"))
        .thenReturn(say("婺源还没收录，我说不了有哪些景点。"));
    when(tools.execute(any(), any(), anyList(), any())).thenReturn(
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
    verify(model, times(5)).chat(anyList(), anyList(), anyInt());
    verify(model, never()).chat(anyList(), eq(List.of()), anyInt());
    assertThat(reply.reply()).isNotNull();
  }

  @Test
  @DisplayName("空消息：直接给一句引导，不浪费一次模型调用")
  void blankMessage() {
    ChatReply reply = agent.chat("   ", List.of(), ConversationState.empty());

    assertThat(reply.reply()).contains("城市");
    verify(model, never()).chat(anyList(), anyList(), anyInt());
  }
  @Test
  void inheritsHotelAndInvalidatesAmountsWhenPartyChanges() throws Exception {
    when(model.chat(anyList(), anyList(), anyInt())).thenReturn(call("c1", "draft_itinerary", "{\"city\":\"大同\",\"travelers\":3}"));
    when(tools.execute(eq("draft_itinerary"), any(), anyList(), any())).thenReturn(new ChatTools.Outcome(true,"结构化行程与账本",null));
    var state = new ConversationState("大同",5,2,null,List.of(),Map.of("departDate","2026-11-23","details",Map.of("hotel","大同某酒店","confirmedGroupCosts",Map.of("intercity-out",80))));
    var reply = agent.chat("改为3个人",List.of(),state);
    ArgumentCaptor<String> args = ArgumentCaptor.forClass(String.class);
    verify(tools).execute(eq("draft_itinerary"),args.capture(),anyList(),any());
    var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(args.getValue());
    assertThat(node.path("days").asInt()).isEqualTo(5);
    assertThat(node.path("details").path("hotel").asText()).isEqualTo("大同某酒店");
    assertThat(node.path("details").path("confirmedGroupCosts").size()).isZero();
    assertThat(reply.state().planning()).containsKey("details");
    assertThat(reply.reply()).contains("【攻略草案已生成】");
    assertThat(reply.reply()).doesNotContain("结构化行程与账本");
  }
  @Test
  void updatesHotelAndCostsAcrossTwoActualTurns() throws Exception {
    when(model.chat(anyList(),anyList(),anyInt()))
        .thenReturn(call("c1","draft_itinerary","{\"city\":\"大同\",\"days\":5,\"travelers\":2,\"details\":{\"hotel\":\"用户指定酒店\",\"confirmedGroupCosts\":{\"intercity-out\":80}}}"))
        .thenReturn(call("c2","draft_itinerary","{\"city\":\"大同\",\"travelers\":3}"));
    when(tools.execute(eq("draft_itinerary"),any(),anyList(),any())).thenReturn(new ChatTools.Outcome(true,"同源攻略",null));
    var first=agent.chat("大同5天2人住用户指定酒店，去程全团80",List.of(),ConversationState.empty());
    var second=agent.chat("改3个人",List.of(),first.state());
    assertThat(second.state().days()).isEqualTo(5);
    assertThat(second.state().travelers()).isEqualTo(3);
    var details=(Map<?,?>)second.state().planning().get("details");
    assertThat(details.get("hotel")).isEqualTo("用户指定酒店");
    assertThat((Map<?,?>)details.get("confirmedGroupCosts")).isEmpty();
  }
  @Test void switchingModeInvalidatesTransportConfirmationButKeepsMeal() {
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(call("c","draft_itinerary","{\"city\":\"大同\",\"details\":{\"transportMode\":\"DRIVING\",\"vehicles\":2}}"));
    when(tools.execute(eq("draft_itinerary"),any(),anyList(),any())).thenReturn(new ChatTools.Outcome(true,"攻略",null));
    var state=new ConversationState("大同",2,3,null,List.of(),Map.of("details",Map.of("transportMode","TRANSIT","confirmedGroupCosts",Map.of("D1-transfer-1",20,"D1-lunch",120,"intercity-out",200))));
    var reply=agent.chat("改成自驾两辆车",List.of(),state);
    Map<?,?> details=(Map<?,?>)reply.state().planning().get("details"); Map<?,?> amounts=(Map<?,?>)details.get("confirmedGroupCosts");
    assertThat(amounts).hasSize(1); assertThat(amounts.get("D1-lunch")).isEqualTo(120);
  }
}
