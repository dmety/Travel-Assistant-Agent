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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={
      "早餐是哪家？", "第二天具体去了哪里？", "为什么安排八大处？", "只解释，不改行程", "不要重新生成攻略",
      "用三条说明时间冲突", "故宫内餐厅是否需要先入园？", "这笔钱是人均还是全团？", "4000元包含高铁吗？",
      "住宿有实时报价吗？", "门票未知是不是免费？", "这版方案能直接执行吗？", "餐馆几点关门？",
      "第三天有哪些候选？", "首日到达时间确定了吗？", "为什么不能保证少走路？", "旧方案和新方案有什么区别？",
      "这是谁提供的信息？", "天气预报覆盖出行日期吗？", "先说怎么修改，不要生成"})
  void explanationNeverRegeneratesPlanEvenIfModelRequestsIt(String question) {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"question\"}");
    when(model.chat(anyList(),anyList(),anyInt()))
        .thenReturn(call("unexpected","draft_itinerary","{\"city\":\"北京\"}"))
        .thenReturn(say("本轮仅解释，方案没有修改。"));
    var state=new ConversationState("北京",3,2,null,List.of(),Map.of("_structured",true,"_phase","needs_review"));
    assertThat(agent.chat(question,List.of(),state).reply()).contains("没有修改");
    verify(tools,never()).execute(eq("draft_itinerary"),any(),anyList(),any());
  }

  @Test void staleProviderScheduleCannotClaimRevisionWasApplied() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"update\",\"details\":{\"restDays\":[1,3],\"onlyMustVisit\":true}}");
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(call("p","draft_itinerary","{\"city\":\"北京\"}"));
    var stale=Map.of("category","PLAN_DRAFT","guide",Map.of("days",List.of(Map.of("dayIndex",1,"slots",List.of(Map.of("kind","VISIT","title","天坛公园","id","D1-visit-a"))))));
    when(tools.execute(eq("draft_itinerary"),any(),anyList(),any())).thenReturn(new ChatTools.Outcome(true,"草案",new ChatTools.Card("plan","PLAN_DRAFT",Map.of("result",Map.of("facts",List.of(Map.of("value",stale)))))));
    var state=new ConversationState("北京",3,3,null,List.of(),Map.of("_structured",true,"_phase","generated","_scheduleSignature","old","details",Map.of("mustVisit",List.of("故宫"))));
    var result=agent.chat("首末日只休息，只保留故宫",List.of(),state);
    assertThat(result.reply()).contains("修改未落实","天坛公园").doesNotContain("已重新编排");
    assertThat(result.state().planning()).containsEntry("_phase","needs_review");
  }
  @Test void failedPlanningUsesObservedFailureInsteadOfModelInventingSourceOutage() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"preview\"}");
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(call("p","draft_itinerary","{\"city\":\"北京\"}"));
    when(tools.execute(eq("draft_itinerary"),any(),anyList(),any())).thenReturn(new ChatTools.Outcome(false,"REFUSED [BUILTIN_INPUT_MAPPING:visitDays]",null));
    assertThat(agent.chat("先出初稿",List.of(),ConversationState.empty()).reply()).contains("BUILTIN_INPUT_MAPPING:visitDays","Provider 拒绝不等于数据源故障");
    verify(model,times(1)).chat(anyList(),anyList(),anyInt());
  }
  @Test void tripCalendarUsesActualWeekdayInModelContext() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"question\"}");
    when(model.chat(anyList(),anyList(),anyInt())).thenAnswer(inv -> {
      assertThat(inv.getArgument(0).toString()).contains("2026-12-06 周日");
      return say("2026年12月6日是周六");
    });
    var state=new ConversationState("北京",3,3,null,List.of(),Map.of("departDate","2026-12-05","_structured",true,"_phase","generated"));
    assertThat(agent.chat("星期几？",List.of(),state).reply()).contains("2026年12月6日是周日");
  }
  @Test void calendarCorrectsModelWeekdayBeforeReply() {
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(say("2026年12月6日周六，2026-12-06（星期六）。"));
    assertThat(agent.chat("日期是星期几？",List.of(),ConversationState.empty()).reply())
        .contains("2026年12月6日周日", "2026-12-06（星期日").doesNotContain("周六");
  }
  @Test void systemPromptHasOnePresentationContractAndBoundedPrinciples() {
    when(model.chat(anyList(),anyList(),anyInt())).thenAnswer(inv -> {
      String system=((List<Map<String,Object>>)inv.getArgument(0)).getFirst().get("content").toString();
      assertThat(system.length()).isLessThan(1600);
      assertThat(system).contains("一、事实与来源","五、表达与呈现","Java 统一控制","正文只简述结果");
      assertThat(system).doesNotContain("必须按 D1","9a.","13a.");
      return say("我是旅行助手。");
    });
    agent.chat("你叫什么？",List.of(),ConversationState.empty());
  }
  @Test void toolDraftSnapshotSurvivesNextTurnAndReplacesOlderVersion() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"preview\"}","{\"intent\":\"update\"}","{\"intent\":\"question\"}");
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(call("d","draft_itinerary","{\"city\":\"北京\",\"days\":3}"))
        .thenReturn(call("e","draft_itinerary","{\"city\":\"北京\",\"days\":3}"))
        .thenReturn(say("按最新方案解释。"));
    var oldDraft=Map.of("category","PLAN_DRAFT","guide",Map.of("days",List.of(Map.of("dayIndex",1,"slots",List.of(Map.of("id","D1-breakfast","title","早餐","restaurants",List.of(Map.of("name","旧餐厅"))))))));
    var newDraft=Map.of("category","PLAN_DRAFT","guide",Map.of("days",List.of(Map.of("dayIndex",2,"slots",List.of(Map.of("id","D2-breakfast","title","早餐","restaurants",List.of(Map.of("name","新餐厅"))))))));
    when(tools.execute(eq("draft_itinerary"),any(),anyList(),any()))
        .thenReturn(new ChatTools.Outcome(true,"旧草案",new ChatTools.Card("plan","PLAN_DRAFT",Map.of("result",Map.of("facts",List.of(Map.of("value",oldDraft)))))))
        .thenReturn(new ChatTools.Outcome(true,"新草案",new ChatTools.Card("plan","PLAN_DRAFT",Map.of("result",Map.of("facts",List.of(Map.of("value",newDraft)))))));
    var first=agent.chat("先出初稿",List.of(),ConversationState.empty());
    var next=agent.chat("替换早餐",List.of(),first.state());
    assertThat(next.state().planning().get("_planContext").toString()).contains("新餐厅","version=2").doesNotContain("旧餐厅");
    agent.chat("早餐是哪家？",List.of(),next.state());
    @SuppressWarnings("unchecked") ArgumentCaptor<List<Map<String,Object>>> messages=ArgumentCaptor.forClass(List.class);
    verify(model,times(3)).chat(messages.capture(),anyList(),anyInt());
    assertThat(messages.getAllValues().get(2).getFirst().get("content").toString()).contains("新餐厅","version=2").doesNotContain("旧餐厅");
  }

  @Test void followUpReceivesStructuredPlanEvenWithoutChatHistory() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"question\"}");
    when(model.chat(anyList(),anyList(),anyInt())).thenAnswer(inv -> {
      String context=inv.getArgument(0).toString();
      assertThat(context).contains("坤宁宫东院餐厅", "08:00", "version=2");
      return say("早餐候选是坤宁宫东院餐厅，时间存在冲突。");
    });
    var state=new ConversationState("北京",3,2,null,List.of(),Map.of("_structured",true,"_phase","needs_review",
        "_planContext",Map.of("version",2,"days",List.of(Map.of("restaurant","坤宁宫东院餐厅","period","08:00")))));
    assertThat(agent.chat("早餐是哪家？",List.of(),state).reply()).contains("坤宁宫");
  }
  @Test void structuredGeneratedPhaseOverridesOldIntakeText() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"question\"}");
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(say("可以解释上一份方案。"));
    var state=new ConversationState("北京",3,2,null,List.of(),Map.of("_structured",true,"_phase","generated"));
    assertThat(agent.chat("为什么这样安排？",List.of(new ConversationTurn("assistant","【规划条件待确认】")),state).reply())
        .contains("解释").doesNotContain("回复‘确认");
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
  @DisplayName("预算口径未确认时，用户确认也不能按全团预算生成攻略")
  void confirmationDoesNotGuessBudgetScope() {
    when(model.complete(any(), any())).thenReturn("{\"intent\":\"confirm\"}");
    when(model.chat(anyList(), anyList(), anyInt()))
        .thenReturn(call("d", "draft_itinerary", "{\"city\":\"北京\",\"days\":2,\"departDate\":\"2026-11-28\"}"));
    ConversationState state = new ConversationState("北京", 2, 2, new java.math.BigDecimal("1000"), List.of(),
        Map.of("_structured", true, "_phase", "collecting", "departDate", "2026-11-28",
            "details", Map.of("origin", "上海", "transportMode", "TRANSIT", "seniors", 0, "children", 0)));

    ChatReply reply = agent.chat("确认开始规划", List.of(), state);

    assertThat(reply.reply()).contains("预算1000元（口径待确认）", "预算口径（全团总额还是人均）");
    verify(tools, never()).execute(eq("draft_itinerary"), any(), anyList(), any());
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
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"update\",\"departDate\":\"2026-11-20\"}");

    ChatReply reply = agent.chat("2026年11月20日出发吧",
        List.of(new ConversationTurn("assistant", "【旅行需求收集】请补充日期和人数。")),
        ConversationState.empty());

    assertThat(reply.reply().split("先出初稿", -1)).hasSize(2);
    verify(model, never()).chat(anyList(), anyList(), anyInt());
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

    assertThat(reply.reply()).contains("【规划条件待确认】");
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
  @Test
  void unchangedCorrectionIsNotReportedAsSuccess() {
    when(model.complete(any(),any())).thenReturn("{\"intent\":\"preview\"}","{\"intent\":\"update\"}");
    when(model.chat(anyList(),anyList(),anyInt())).thenReturn(call("c", "draft_itinerary", "{\"city\":\"北京\"}"));
    when(tools.execute(eq("draft_itinerary"),any(),anyList(),any())).thenReturn(new ChatTools.Outcome(true,"草案",null));
    var first=agent.chat("北京三天先出初稿",List.of(),ConversationState.empty());
    var next=agent.chat("你漏掉了必去景点，请纠正",List.of(),first.state());
    assertThat(next.reply()).contains("方案未变化", "不能视为已完成纠错");
  }

}
