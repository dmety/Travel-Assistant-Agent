package com.travelassistant.server.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelassistant.server.application.agent.AgentUnavailableException;
import com.travelassistant.server.application.intent.ConversationState;
import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.application.place.FamousPlaces;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient.ModelTurn;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient.ToolCall;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
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
  /**
   * 带进模型的历史轮数。每一轮历史都会在每一次模型调用里重发，所以这个数字直接
   * 乘上调用次数：10 轮改成 6 轮，一轮对话最多少发约 3K 字符。
   */
  private static final int MAX_HISTORY = 6;
  /** 行程答案要逐日转述工具结果；普通问答仍由提示词保持简短。 */
  private static final int REPLY_TOKENS = 1400;
  private static final int MAX_REPLY = 1800;
  private static final long BUDGET_MS = 45_000L;

  private static final String SYSTEM_PROMPT = """
      你是「旅行助手」的对话 Agent。用户用中文提问，你可以调用工具查真实数据，然后用自己的话回答。

      完整攻略的收集流程
      A. 第一次听到「去某地玩 N 天，帮我安排/做攻略」，默认这是初步意向，不代表用户已准备好接受完整攻略。
         若用户没有明确说「先出初稿」「按现有信息直接规划」等快捷要求，先不要调用 draft_itinerary。
         用一条简短问题集中收集：出发地与日期/月份、人数及老人儿童/步行限制、全团预算、交通方式、兴趣与必去点；
         忌口和住宿偏好可选。未定项允许回答「待定」，不要反复逐项追问。
         收集期间用户另问天气等即时信息时，仍应调用对应工具回答；不要把用户赶去自行查询，也不要因此生成攻略。
      B. 用户补充后，先用不超过 120 字复述已提供条件，指出尚缺的关键项，询问是否按这些条件开始规划。
         用户明确回复「确认开始规划」后才调用 draft_itinerary。若用户明确说先出初稿，可跳过确认，缺项标为未提供。
         条件未确认时不得先查一轮、生成整份攻略再让用户补资料。
      C. draft_itinerary 成功后，只用一句话说明已生成草案及重要限制；完整内容交给结构化卡片展示。
         不要把 guideText 或整份攻略复制进聊天正文。不得把模型工具调用前的客套话当作最终答复。

      工具使用
      1. 凡是涉及具体事实的问题——某地有什么景点、门票多少、天气如何、附近有什么馆子、
         几天怎么安排——必须先调用对应工具，不许凭记忆回答。
      2. 用户已明确给出城市且点名景点或类别时，直接用 search_attractions，所有点名内容放进 keywords；
         不要为每个景点重复 resolve_place，也不要调用全市列表来代替精确查询。只有城市归属不明时才调
         resolve_place；它是权威口径，不要自己判断「婺源属于上饶」这类归属。
         resolve_place 返回 level=province 时，必须保留省级范围并按全省查询；不得擅自缩成太原等单一城市。
         但 resolve_place 没认出的地名（比如婺源）不要让用户重说一遍：直接拿原名调
         get_inspiration 或 draft_itinerary，它们会自动实时检索本地景点。
         著名景点（华山、黄山、老君山等）一律按 resolve_place 给的检索名/用户原名查，
         不要因为库里有同名景点就改成别的城市。
      3. search_attractions 只用于用户明确点名的景点或类别；list_attractions 和 get_inspiration
         只用于用户问全市推荐、有什么好玩的或不知道去哪。点名查询未命中时如实说没查到，
         不得用同城其他景点补位；用户没有要求推荐时，不要额外扩展候选。
      4. 用户只说「玩几天」「怎么安排」但没给天数：先问一句玩几天，不要默认成 3 天。
         用户同时问行程和最近天气时，先用 draft_itinerary 已返回的预报；若草案没返回天气，再调用一次 get_weather。无天气数据时明确说未查到，不能判断适不适合出行。
      5. 查完发现数据不足（工具说没查到），就如实告诉用户「这个我没查到」，不要解释内部数据来源；
         不要用常识补齐景点名、票价或餐厅。没查到的地名，get_inspiration 会自动改用
         地图实时检索——实时结果没有票价等级，按工具原文说，缺就说没查到。
         景点卡按真实评分排序，票价只引用卡里原文；标「未核验」的就说没查到票价、
         让用户到景区官方公众号/官网或现场窗口查询，不要补数字、不要把人均消费当门票、
         不要编网址。地图接口不返回评论正文，用户想看具体评价就让他点卡片里的评价数，
         系统会跳到地图官方页面；不要编造「游客说」「有人评价」这类评论内容。
      6. 候选里如果混进了站前广场、候车室、停车场、收费站、地铁站这类通行空间，
         或者学校、图书馆这类非游览地点，一律不要推荐，也不要在回答里提。它们只是地图
         顺带返回的地点，不是景点。用户问「有什么好玩的」时也别拿商场、写字楼凑数。
      7. 同一个工具不要用同样的参数重复调用。最多 6 次工具调用。
      8. vote_plan 会让三个评委 Agent 两轮投票、比较费时：只在用户明确想比较不同侧重
         （省钱/美食/少走路哪个好）或问「哪个方案更好」时用；排一份普通行程用 draft_itinerary。
      9. book_ticket 只给购票建议与经核验的官方入口、方法，不代下单、不查库存余票、不退款，
         不要说「已经买好」；工具给出的说明照实说一次，不要额外补免责话术或编网址；缺条件就照
         工具说的问，不要替用户猜年龄、学生资格。
      9a. 用户附图时描述可辨认内容、读取菜单/票面文字并结合工具核验，看不清的字段标注未核验，
          不能仅凭截图推断实时价格或库存。问「有什么好吃的」「吃什么」先调 find_food：先列当地
          特色菜与代表小吃（菜名属通用资料），再把工具返回的餐馆挂到菜品上；餐馆名、人均、评分、
          距离只引用 find_food 结果，没查到餐馆就只给菜品线索并如实说明。

      回答方式
      10. 用口语，称呼「你」。普通问答最多 4 句话；用户要行程时必须按 D1、D2 等逐日分段列出工具给出的安排，不能漏天、重复景点或补空天；可适度展开。不要表格、emoji、Markdown 加粗。
          核验提醒由界面统一尾注展示，正文一律不写「未核验 / 以官方为准 / 余票未核验 / 预报非实况」这类话；数据缺就直接说没查到，不要补免责。
      11. 景点事实默认注明返回来源与查询时间；查询时间不等于景区信息更新时间。行程类未被问及天气时，不要主动报告天气缺失。数字必须与工具结果完全一致；票价与开放时间按工具原文说，缺就说没查到。
      12. 提到门票时把价格念出来（例如「成人票 60 元、淡季 40 元」），不要只说「有门票」
          或「需要买票」这种等于没说的概括；工具结果里没有票价就直说没查到，不要估算。
      13. 天气是预报不是实况，说明时要讲清楚。
      13a. 只按工具实际返回的数据下结论：连续无雨选日须用工具给出的日期筛选结果，覆盖不足就明说
          无法据此选日；用户问热门景点时按卡里的等级与评分说明筛选依据，没有热度/评论量数据就不作
          热门排名；路线距离与耗时是查询时估算，预算按交通、住宿、餐饮、门票分列并区分已确认与
          未知金额，未知费用不按零计。
      14. 用户问「你叫什么」「你家在哪」「你有什么用」这类，直接、简短、诚实回答，不要推销功能。
      15. 用户这句话有省略（「那呢」「还有别的吗」「再玩两天」「有没有小馆子」）时，看最近对话补全意思；
          接着聊同一座城市就直接用上文城市，不要反问「哪个城市」。
      16. 用户询问信息来源时，按工具结果说明来源；Agent Plan 结果可标注「百度地图 Agent Plan」。
          不要把服务来源说成门票/开放时间等字段的官方核验来源，除非响应明确给出对应来源证据。

      你不知道也不要假装知道的
      17. 你没有价格表页面、没有官方购票链接、不能代订或退款、没有实时余票与库存。
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
    return run(message, history, state, List.of(), null);
  }

  public ChatReply chat(String message, List<ConversationTurn> history, ConversationState state,
      List<String> images) {
    return run(message, history, state, images, null);
  }

  /** 与 {@link #chat} 使用同一工具流程，并将模型公开输出增量实时交给调用方。 */
  public ChatReply chatStream(String message, List<ConversationTurn> history,
      ConversationState state, Consumer<String> onDelta) {
    return run(message, history, state, List.of(), onDelta);
  }

  public ChatReply chatStream(String message, List<ConversationTurn> history,
      ConversationState state, List<String> images, Consumer<String> onDelta) {
    return run(message, history, state, images, onDelta);
  }

  private ChatReply run(String message, List<ConversationTurn> history, ConversationState state,
      List<String> images, Consumer<String> onDelta) {
    String text = message == null ? "" : message.strip();
    List<String> attachedImages = images == null ? List.of() : List.copyOf(images);
    ConversationState known = state == null ? ConversationState.empty() : state;
    // 旧会话可能存着「华山→济南」这种错城市：著名景点以权威归属为准，
    // 否则第二轮「4天3人」会照着济南/成都排一版跨省草案
    FamousPlaces.Entry activePlace = activePlace(text, history);
    known = reconcile(known, activePlace);
    if (attachedImages.isEmpty()) known = TripRequirements.update(model, mapper, known, text);
    boolean structured = Boolean.TRUE.equals(known.planning().get("_structured"));
    String intent = String.valueOf(known.planning().getOrDefault("_intent", ""));
    boolean collecting = structured ? "collecting".equals(known.planning().get("_phase")) : planningIntakePending(history);
    boolean confirmed = structured ? intent.equals("confirm") || intent.equals("preview") : explicitPlanningConfirmation(text) || explicitQuickDraft(text);
    if (structured && attachedImages.isEmpty() && (intent.equals("start") || collecting && intent.equals("update"))) {
      return new ChatReply(TripRequirements.summary(known), List.of(), List.of(), known, "agent", null);
    }
    if (text.isEmpty() && attachedImages.isEmpty()) {
      return new ChatReply("想聊点什么？说个城市和天数，我来排行程。", List.of(), List.of(),
          known, "agent", null);
    }
    if (!structured && attachedImages.isEmpty() && planningIntakePending(history) && !explicitPlanningConfirmation(text)
        && !explicitQuickDraft(text) && !isWeatherLookup(text)) {
      return summarizePlanningIntake(text, history, known);
    }
    if (!structured && attachedImages.isEmpty() && shouldStartPlanningIntake(text, history) && !explicitQuickDraft(text)) {
      String question = TripRequirements.summary(known);
      return new ChatReply(question, List.of(new ChatTools.Card("intake", "PLANNING_INTAKE", Map.of())),
          List.of(), known, "agent", null);
    }
    if (model == null || !model.configured()) {
      return new ChatReply(null, List.of(), List.of(), known, "unavailable",
          "未配置模型密钥（DEEPSEEK_API_KEY），已回退到规则解析");
    }

    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", systemPrompt(known, activePlace)));
    for (ConversationTurn turn : trim(history)) {
      messages.add(Map.of("role", turn.role(), "content", turn.text()));
    }
    messages.add(Map.of("role", "user", "content", userContent(text, attachedImages)));

    List<ChatTools.Card> cards = new ArrayList<>();
    List<ChatReply.ToolTrace> trace = new ArrayList<>();
    ConversationState carried = known;
    String lastContent = null;
    int calls = 0;
    boolean voted = false;
    long deadline = System.currentTimeMillis() + BUDGET_MS;

    for (int step = 0; step < MAX_STEPS; step++) {
      ModelTurn turn;
      try {
        // 模型常在工具调用前先写「我来帮你安排」。先缓存这一轮的 token，确认它不是
        // 工具调用前言后再显示，避免前言、工具长摘要和结构化攻略卡连着重复三遍。
        StringBuilder streamedTurn = onDelta == null ? null : new StringBuilder();
        if (onDelta == null) {
          turn = attachedImages.isEmpty()
              ? model.chat(messages, ChatTools.SPECS, REPLY_TOKENS)
              : model.chat(messages, ChatTools.SPECS, REPLY_TOKENS, model.visionModel());
        } else {
          turn = attachedImages.isEmpty()
              ? model.chatStream(messages, ChatTools.SPECS, REPLY_TOKENS, streamedTurn::append)
              : model.chatStream(messages, ChatTools.SPECS, REPLY_TOKENS, model.visionModel(), streamedTurn::append);
        }
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
        ChatReply completed = reply(lastContent, cards, trace, carried, null);
        if (onDelta != null && completed.reply() != null) {
          onDelta.accept(completed.reply());
        }
        return completed;
      }
      if (onDelta != null) {
        if (turn.content().isBlank()) {
          String planned = turn.toolCalls().stream().map(call -> label(call.name()))
              .distinct().limit(4).reduce((left, right) -> left + "、" + right).orElse("资料");
          onDelta.accept("我先查询：" + planned + "。\n\n");
        } else {
          onDelta.accept("\n\n");
        }
      }
      messages.add(assistantMessage(turn));

      for (ToolCall call : turn.toolCalls()) {
        if (calls >= MAX_TOOL_CALLS) {
          messages.add(Map.of("role", "tool", "tool_call_id", call.id(),
              "content", "工具调用次数已达上限，请直接用已有信息回答。"));
          continue;
        }
        // 一轮只投一次票。模型偶尔会在同一轮里反复触发 vote_plan（换城市名、补参数），
        // 每多一次就是一次额外模型调用加六个评委 invoke，是这条链路上最贵的重复开销。
        if ("vote_plan".equals(call.name()) && voted) {
          messages.add(Map.of("role", "tool", "tool_call_id", call.id(),
              "content", "本轮已经投过一次票，直接引用上面的投票结果回答，不要再投。"));
          continue;
        }
        calls++;
        String args = reconcileArgs(call.arguments(), activePlace);
        if ("draft_itinerary".equals(call.name())) args = inheritPlanning(args, carried);
        if ("draft_itinerary".equals(call.name()) && collecting && !confirmed) {
          String pending = structured ? TripRequirements.summary(carried) : "【规划条件待确认】请确认条件后再生成攻略。";
          if(onDelta!=null)onDelta.accept(pending);
          return new ChatReply(pending,cards,trace,carried,"agent",null);
        }
        if ("draft_itinerary".equals(call.name()) && collecting
            && !explicitQuickDraft(text) && !intent.equals("preview") && !hasDepartureDate(args)) {
          String missing = "【规划条件待确认】出发日期尚未确定，暂不能生成可执行的逐日攻略。"
              + "按天气选日的条件还未完成，目的地预报和满足条件的日期仍需核验；"
              + "预报不足时只能保留为待定。确定日期后再规划；如只想预览，可回复‘先出初稿’。";
          if (onDelta != null) onDelta.accept(missing);
          return new ChatReply(missing, cards, trace, carried, "agent", null);
        }
        if ("draft_itinerary".equals(call.name()) && collecting && !explicitQuickDraft(text)
            && !TripRequirements.budgetScopeConfirmed(carried)) {
          String pending = TripRequirements.summary(carried);
          if (onDelta != null) onDelta.accept(pending);
          return new ChatReply(pending, cards, trace, carried, "agent", null);
        }
        // 用户定案：所有子 Agent 收集到的信息全量交给主 Agent 整理草案，再由评委投票。
        // vote_plan 因此会收到此前工具产生的全部卡片；其他工具不感知这个内部字段。
        ChatTools.Outcome outcome = switch (call.name() == null ? "" : call.name()) {
          case "vote_plan" -> tools.execute(call.name(), args, new ArrayList<>(cards));
          case "search_attractions", "list_attractions", "get_inspiration", "draft_itinerary" ->
              tools.execute(call.name(), args, List.of(), text + "\n" + carried.planning().getOrDefault("weatherRequest", ""));
          default -> tools.execute(call.name(), args);
        };
        if ("vote_plan".equals(call.name())) {
          voted = true;
        }
        messages.add(Map.of("role", "tool", "tool_call_id", call.id(),
            "content", outcome.summary()));
        String toolSummary = outcome.summary() == null ? "" : outcome.summary();
        if (toolSummary.length() > 4000) toolSummary = toolSummary.substring(0, 4000) + "…（输出已截断）";
        trace.add(new ChatReply.ToolTrace(call.name(), label(call.name()), outcome.ok(),
            toolSummary, outcome.disclaimers()));
        if (outcome.card() != null) {
          cards.add(outcome.card());
        }
        carried = track(carried, call.name(), args);
        if ("draft_itinerary".equals(call.name()) && outcome.ok()) {
          Map<String,Object> phase = new LinkedHashMap<>(carried.planning());
          phase.put("_phase", "generated");
          carried = new ConversationState(carried.city(),carried.days(),carried.travelers(),carried.budget(),carried.preferences(),phase);
          String concise = "【攻略草案已生成】已按「" + (carried.city() == null ? "目的地" : carried.city())
              + "」" + (carried.days() == null ? "" : carried.days() + " 天")
              + "整理。每日安排、交通、餐饮、费用和待核验项见下方攻略卡。";
          // 费用未知与预算结论已在攻略卡的 budgetEstimate / draftMini 里给出，
          // 这里不再追加第二遍免责；concise 保持「已生成 + 去哪看」一句话。
          if (onDelta != null) onDelta.accept(concise);
          return new ChatReply(concise, cards, trace, carried, "agent", null);
        }
      }
      if (System.currentTimeMillis() > deadline) {
        messages.add(Map.of("role", "user",
            "content", "时间到了，请直接用已经查到的信息回答，不要再调用工具。"));
        break;
      }
    }

    // 轮数或时间用尽时直接返回已流式输出的规划文本和已查结果，不再追加一次模型收尾调用。
    return reply(lastContent, cards, trace, carried, "这次查得比较久，先给你已经查到的结果。");
  }

  private static Object userContent(String text, List<String> images) {
    if (images == null || images.isEmpty()) return text;
    List<Map<String, Object>> parts = new ArrayList<>();
    parts.add(Map.of("type", "text", "text", text));
    for (String image : images) parts.add(Map.of("type", "image_url",
        "image_url", Map.of("url", image)));
    return parts;
  }

  private ChatReply summarizePlanningIntake(String current, List<ConversationTurn> history,
      ConversationState state) {
    String system = "你是旅行需求收集助手。只总结用户在对话中明确说出的信息，不推断人数、日期、预算、老人儿童、交通或偏好。"
        + "用不超过100个汉字列出已知条件和仍缺的关键项；用户已经给出的项目不要重复问。不要给下一步建议，不要重复确认话术。"
        + "住宿有当地特色已经是类型偏好，不得当作未填写。具体酒店、住宿地址、饮食忌口是可选项，不作为规划前置条件。"
        + "不要调用工具，不要生成行程或景点推荐，不要寒暄。";
    List<Map<String, Object>> messages = new ArrayList<>();
    messages.add(Map.of("role", "system", "content", system));
    for (ConversationTurn turn : trim(history)) {
      messages.add(Map.of("role", turn.role(), "content", turn.text()));
    }
    messages.add(Map.of("role", "user", "content", current));
    String summary = null;
    if (model != null && model.configured()) {
      try {
        ModelTurn turn = model.chat(messages, List.of(), 280);
        summary = clean(turn.content());
      } catch (RuntimeException e) {
        log.info("旅行需求复述暂不可用，使用固定确认提示");
      }
    }
    if (summary != null) summary = stripIntakeCallToAction(summary);
    String suffix = "\n回复‘确认开始规划’按现有信息生成；也可继续补充，或说‘先出初稿’跳过未定项。";
    if (summary == null || summary.isBlank()) {
      summary = "【规划条件待确认】已收到补充。未提供的出发地、日期、人数/老人儿童、预算、交通和偏好会保持未确定。";
    } else if (!summary.startsWith("【规划条件待确认】")) {
      summary = "【规划条件待确认】" + summary;
    }
    int max = Math.max(0, 220 - suffix.length());
    if (summary.length() > max) summary = summary.substring(0, max) + "…";
    return new ChatReply(summary + suffix, List.of(), List.of(), state, "agent", null);
  }

  private static boolean planningIntakePending(List<ConversationTurn> history) {
    return latestPlanningPhase(history) == 1;
  }

  private boolean hasDepartureDate(String args) {
    try {
      String date = mapper.readTree(args).path("departDate").asText("");
      java.time.LocalDate.parse(date);
      return true;
    } catch (Exception invalid) {
      return false;
    }
  }

  private static boolean shouldStartPlanningIntake(String text, List<ConversationTurn> history) {
    if (text == null || explicitPlanningConfirmation(text)) return false;
    if (text.matches("(?s).*(?:不要|不用|别|无需).{0,6}(?:规划|行程|攻略|安排).*")) return false;
    boolean planning = List.of("排行程", "行程", "旅行攻略", "旅游攻略", "安排行程", "帮我安排",
        "规划", "怎么玩", "游玩计划").stream().anyMatch(text::contains);
    if (!planning) return false;
    return latestPlanningPhase(history) != -1 || !isPlanRevision(text);
  }

  private static boolean isPlanRevision(String text) {
    return text != null && List.of("调整", "修改", "改为", "改成", "改一下", "替换", "增减", "换一下")
        .stream().anyMatch(text::contains);
  }

  /** 最新一条规划状态优先；旧攻略不能让新旅行请求跳过需求收集。 */
  private static int latestPlanningPhase(List<ConversationTurn> history) {
    if (history == null) return 0;
    for (int i = history.size() - 1; i >= 0; i--) {
      ConversationTurn turn = history.get(i);
      if (!"assistant".equals(turn.role()) || turn.text() == null) continue;
      if (turn.text().contains("【攻略草案已生成】")) return -1;
      if (turn.text().contains("【旅行需求收集】")
          || turn.text().contains("【规划条件待确认】")) return 1;
    }
    return 0;
  }

  private static boolean explicitPlanningConfirmation(String text) {
    return text != null && List.of("确认开始规划", "确认，开始", "开始规划", "确认生成攻略",
        "就按这些条件", "可以开始规划").stream().anyMatch(text::contains);
  }

  private static boolean explicitQuickDraft(String text) {
    return text != null && List.of("先出初稿", "先出个初稿", "直接出初稿", "按现有信息直接规划",
        "不用问，直接", "先按默认", "随便排一版").stream().anyMatch(text::contains);
  }

  private static boolean isWeatherLookup(String text) {
    return text != null && List.of("天气", "下雨", "降雨", "气温", "温度", "天气预报", "不下雨")
        .stream().anyMatch(text::contains);
  }

  private static String stripIntakeCallToAction(String text) {
    int end = text.length();
    for (String marker : List.of("可补充条件", "可补充", "可继续补充", "回复‘确认开始规划’",
        "回复“确认开始规划”", "可回复‘确认开始规划’", "可回复“确认开始规划”")) {
      int index = text.indexOf(marker);
      if (index >= 0) end = Math.min(end, index);
    }
    return text.substring(0, end).stripTrailing();
  }

  private ChatReply reply(String content, List<ChatTools.Card> cards,
      List<ChatReply.ToolTrace> trace, ConversationState state, String note) {
    String cleaned = clean(content);
    if (cleaned == null) {
      cleaned = cards.isEmpty()
          ? "这个我没查到，可以换个地名再问。"
          : "查到的东西都在下面了，没能组织成一句话。";
    }
    return new ChatReply(cleaned, cards, trace, state, "agent", note);
  }

  /** 系统提示里附上「本轮已确认」，让「那呢」「再玩两天」有落点。 */
  private String systemPrompt(ConversationState state, FamousPlaces.Entry activePlace) {
    if (state.isEmpty() && activePlace == null) {
      return SYSTEM_PROMPT;
    }
    List<String> parts = new ArrayList<>();
    if (!state.planning().isEmpty()) parts.add("结构化旅行需求=" + state.planning());
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
        + String.join("；", parts)
        + (activePlace == null ? "" : "\n本次聊的是「" + activePlace.name() + "」，查景点与排行程"
            + "按「" + activePlace.destination() + "」这个名字，不要换成别的城市")
        + "\n";
  }

  /** 这句话里的著名景点优先；没写地名时才从最近的用户轮次里找（只看用户说的话）。 */
  private static FamousPlaces.Entry activePlace(String message,
      List<ConversationTurn> history) {
    Optional<FamousPlaces.Entry> inMessage = FamousPlaces.find(message);
    if (inMessage.isPresent()) {
      return inMessage.get();
    }
    if (FamousPlaces.mentionsOtherPlace(message)) {
      return null;
    }
    return FamousPlaces.findInRecentUserTurns(history).orElse(null);
  }

  /** 旧 state 里的错城市不再跨轮沿用，换成景点的检索名。 */
  private ConversationState reconcile(ConversationState state, FamousPlaces.Entry place) {
    if (place == null || !FamousPlaces.conflicts(state.city(), place)) {
      return state;
    }
    log.info("会话城市「{}」与景点「{}」冲突，按「{}」检索",
        state.city(), place.name(), place.destination());
    return new ConversationState(place.destination(), state.days(), state.travelers(),
        state.budget(), state.preferences(), state.planning());
  }

  /** 模型偶尔仍会拿旧城市当 city 参数：在这种冲突下改回景点检索名。 */
  private String reconcileArgs(String rawArgs, FamousPlaces.Entry place) {
    if (place == null || rawArgs == null || rawArgs.isBlank()) {
      return rawArgs;
    }
    try {
      JsonNode node = mapper.readTree(rawArgs);
      if (!node.isObject() || !node.path("city").isTextual()) {
        return rawArgs;
      }
      if (!FamousPlaces.conflicts(node.path("city").asText("").strip(), place)) {
        return rawArgs;
      }
      ((ObjectNode) node).put("city", place.destination());
      return mapper.writeValueAsString(node);
    } catch (Exception e) {
      return rawArgs;
    }
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
          state.preferences(), state.planning());
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
        List.copyOf(prefs), mergePlanning(state.planning(), args));
  }

  private Map<String, Object> mergePlanning(Map<String, Object> old, JsonNode args) {
    Map<String, Object> merged = new LinkedHashMap<>(old);
    for (String key : List.of("departDate", "returnDate")) {
      if (args.has(key)) merged.put(key, args.get(key).asText());
    }
    if (args.path("details").isObject()) {
      Map<String, Object> details = new LinkedHashMap<>();
      if (old.get("details") instanceof Map<?, ?> prior) prior.forEach((k, v) -> details.put(String.valueOf(k), v));
      args.get("details").fields().forEachRemaining(e -> details.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
      merged.put("details", details);
    }
    return merged;
  }

  private String inheritPlanning(String raw, ConversationState state) {
    try {
      ObjectNode args = (ObjectNode) mapper.readTree(raw);
      if(Boolean.TRUE.equals(state.planning().get("_structured")) && state.city()!=null)args.put("city",state.city());
      boolean same = !args.hasNonNull("city") || args.path("city").asText().equals(state.city());
      if (!args.hasNonNull("city") && state.city() != null) args.put("city", state.city());
      if (!args.hasNonNull("days") && state.days() != null) args.put("days", state.days());
      if (!args.hasNonNull("travelers") && state.travelers() != null) args.put("travelers", state.travelers());
      if (!args.hasNonNull("budget") && state.budget() != null) args.put("budget", state.budget());
      if (!args.has("preferences")) args.set("preferences", mapper.valueToTree(state.preferences()));
      if (same) {
        Map<String, Object> merged = mergePlanning(state.planning(), args);
        boolean changed = args.has("travelers") && state.travelers() != null && args.path("travelers").asInt() != state.travelers()
            || args.has("days") && state.days() != null && args.path("days").asInt() != state.days();
        for (String key : List.of("departDate", "returnDate")) if (args.has(key) && state.planning().containsKey(key)
            && !args.path(key).asText().equals(state.planning().get(key))) changed = true;
        if (args.path("details").has("hotel")) changed = true;
        if (changed && merged.get("details") instanceof Map<?, ?> previous) {
          Map<String, Object> fresh = new LinkedHashMap<>(); previous.forEach((k,v) -> fresh.put(String.valueOf(k),v));
          if (!args.path("details").has("confirmedGroupCosts")) fresh.put("confirmedGroupCosts", Map.of());
          merged.put("details", fresh);
        }
        boolean transportChanged=List.of("transportMode","vehicles","origin","originCity","arrivalCity").stream().anyMatch(key -> args.path("details").has(key));
        if(transportChanged && !args.path("details").has("confirmedGroupCosts") && merged.get("details") instanceof Map<?,?> previous) {
          Map<String,Object> fresh=new LinkedHashMap<>(); previous.forEach((k,v) -> fresh.put(String.valueOf(k),v));
          if(fresh.get("confirmedGroupCosts") instanceof Map<?,?> amounts) {
            Map<String,Object> retained=new LinkedHashMap<>(); amounts.forEach((k,v) -> { String id=String.valueOf(k); if(!id.startsWith("intercity-") && !id.matches("D[0-9]+-(?:transfer-.*|hotel-out.*|hotel-back.*|local.*)")) retained.put(id,v); });
            fresh.put("confirmedGroupCosts",retained);
          }
          merged.put("details",fresh);
        }
        merged.forEach((k, v) -> { if(!k.startsWith("_") && !k.equals("weatherRequest")) args.set(k, mapper.valueToTree(v)); });
        if(Boolean.TRUE.equals(state.planning().get("_structured"))) {
          // Extracted user requirements take precedence over incomplete tool arguments.
          if(state.days()!=null)args.put("days",state.days());
          if(state.travelers()!=null)args.put("travelers",state.travelers());
          if(state.budget()!=null)args.put("budget",state.budget());
          args.set("preferences",mapper.valueToTree(state.preferences()));
          for(String key:List.of("departDate","returnDate")) if(state.planning().containsKey(key)) args.set(key,mapper.valueToTree(state.planning().get(key)));
          if(state.planning().get("details") instanceof Map<?,?> saved) {
            ObjectNode detail=mapper.createObjectNode();
            saved.forEach((k,v)->detail.set(k.toString(),mapper.valueToTree(v))); args.set("details",detail);
          }
        }
      }
      return mapper.writeValueAsString(args);
    } catch (Exception ignored) { return raw; }
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
      case "search_attractions" -> "查点名景点";
      case "get_weather" -> "查天气";
      case "find_food" -> "找餐馆";
      case "route_plan" -> "查路线";
      case "draft_itinerary" -> "排行程";
      case "book_ticket" -> "问票务";
      case "get_inspiration" -> "找灵感";
      case "vote_plan" -> "评委投票";
      default -> tool;
    };
  }
}
