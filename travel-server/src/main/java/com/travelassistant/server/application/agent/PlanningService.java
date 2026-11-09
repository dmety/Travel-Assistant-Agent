package com.travelassistant.server.application.agent;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.domain.location.WeatherResult;
import com.travelassistant.server.domain.ticket.TicketAdvice;
import com.travelassistant.server.infrastructure.ai.DeepSeekClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 规划编排（V2 第 2 节）：由 Java 决定调用哪些工具，模型只负责组织语言。
 *
 * <p>为什么不把「调哪个工具」交给模型：本项目要求每条建议都能追溯到来源。
 * 如果由模型自由选择工具，就会出现「没查就答」；由 Java 按需求固定走
 * 景点 → 天气 → 票务 三条链路，每一条的输入输出都经过校验，模型只拿到这些事实。
 *
 * <p>降级策略是显式的：
 * <ul>
 *   <li>景点库不可用 → 结果里说明原因，候选为空，但请求本身仍是 200；</li>
 *   <li>高德未配置 → 不查天气，notices 里说明「未接入实时天气」；</li>
 *   <li>模型未配置或调用失败 → {@code advice} 为空，notices 里说明原因，
 *       用户仍然拿到带来源的候选清单。</li>
 * </ul>
 * 换句话说，最差的情况也不是「什么都给不了」。
 *
 * <p>本服务**不保存任何东西**。需求要求用户确认后才落到正式方案，
 * 保存必须由用户另行调用行程接口完成。
 */
@Service
public class PlanningService {
  private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

  /** 为几个候选查购票信息：太多了没有意义，用户也不会一个个点。 */
  private static final int TICKET_LOOKUPS = 3;

  private final PlanningTools tools;
  private final DeepSeekClient model;

  public PlanningService(PlanningTools tools, DeepSeekClient model) {
    this.tools = tools;
    this.model = model;
  }

  /** 生成规划草案（不落库）。 */
  public PlanDraft plan(PlanningRequest request) {
    return plan(request, true);
  }

  /**
   * 只装配事实，不调用模型。
   *
   * <p>「确认保存」要按同一套规则重新装配一遍载荷，此时调模型润色既浪费又有失败风险，
   * 因此单独留一个无模型入口。两种入口生成的 questions / dailySchedules / checks 完全一致。
   */
  public PlanDraft planFacts(PlanningRequest request) {
    return plan(request, false);
  }

  private PlanDraft plan(PlanningRequest request, boolean withAdvice) {
    if (request == null || request.destination() == null || request.destination().isBlank()) {
      throw new InvalidPlanningRequestException("请填写目的地");
    }
    String city = request.destination().strip();

    List<String> notices = new ArrayList<>();
    Set<String> sources = new LinkedHashSet<>();

    // 1. 候选景点
    List<Attraction> candidates = new ArrayList<>();
    ToolOutcome<List<Attraction>> byCity = tools.searchByCity(city, PlanningTools.MAX_CANDIDATES);
    if (byCity.ok()) {
      candidates.addAll(byCity.value());
      if (!candidates.isEmpty()) {
        sources.add("本地景点知识库");
      }
    } else {
      notices.add(byCity.error());
    }

    // 城市查不到时，用偏好关键词兜底检索，避免「换个说法就完全没结果」
    if (candidates.isEmpty() && !request.preferences().isEmpty()) {
      for (String preference : request.preferences()) {
        if (preference == null || preference.isBlank()) {
          continue;
        }
        ToolOutcome<List<Attraction>> byName = tools.searchByName(preference, 5);
        if (byName.ok()) {
          for (Attraction a : byName.value()) {
            if (!candidates.contains(a)) {
              candidates.add(a);
            }
          }
        }
        if (candidates.size() >= PlanningTools.MAX_CANDIDATES) {
          break;
        }
      }
      if (!candidates.isEmpty()) {
        sources.add("本地景点知识库");
        notices.add("本地库中没有「" + city + "」的景点，以下候选是按偏好关键词检索的结果，请确认目的地");
      }
    }

    if (candidates.isEmpty() && byCity.ok()) {
      notices.add("本地知识库未收录「" + city + "」的景点。可以换用省份名、城市名全称，或补充偏好关键词再试");
    }

    // 2. 天气（仅在高德可用时）
    WeatherResult weather = null;
    if (tools.locationEnabled()) {
      ToolOutcome<WeatherResult> outcome = tools.weather(city);
      if (outcome.ok()) {
        weather = outcome.value();
        sources.add(outcome.value().source());
      } else {
        notices.add(outcome.error());
      }
    } else {
      notices.add("未接入实时天气（未配置高德密钥），本草案不含天气信息");
    }

    // 3. 候选景点的购票入口
    List<TicketAdvice> ticketAdvice = new ArrayList<>();
    for (Attraction candidate : candidates.subList(0, Math.min(TICKET_LOOKUPS, candidates.size()))) {
      ToolOutcome<TicketAdvice> outcome = tools.ticket(candidate.name(), candidate.city());
      if (outcome.ok()) {
        ticketAdvice.add(outcome.value());
        if (!outcome.value().channels().isEmpty()) {
          sources.add("本系统官方购票入口配置");
        }
      }
    }

    // 4. 需求完整度提示
    if (request.days() == null || request.days() <= 0) {
      notices.add("未提供游玩天数，暂不生成按天安排");
    }
    if (request.budget() == null) {
      notices.add("未提供预算上限，无法核对是否超支");
    }
    if (request.travelers() == null) {
      notices.add("未提供同行人数，人均费用无法计算");
    }

    // 4b. 结构化补问、逐日草案、日期与预算检查（纯规则，与模型无关）
    DraftAssembler.Draft assembled = DraftAssembler.assemble(request, candidates);
    notices.addAll(assembled.notices());

    // 5. 模型建议（可失败）
    String advice = null;
    if (withAdvice && model.configured()) {
      try {
        advice = model.complete(systemPrompt(), userPrompt(request, candidates, weather));
        sources.add("DeepSeek " + model.model());
      } catch (AgentUnavailableException e) {
        log.warn("模型建议生成失败：{}", e.getMessage());
        notices.add(e.getMessage() + "，以下仅列出检索到的景点");
      }
    } else if (withAdvice) {
      notices.add("未配置模型密钥（DEEPSEEK_API_KEY），本次仅提供检索到的景点，未生成文字建议");
    }

    return new PlanDraft(request, candidates, weather, ticketAdvice, advice,
        notices, List.copyOf(sources), assembled.questions(), assembled.dailySchedules(),
        assembled.checks(), assembled.budgetEstimate(), Instant.now());
  }

  /** 模型是否可用，供状态接口展示。 */
  public boolean modelConfigured() {
    return model.configured();
  }

  private String systemPrompt() {
    return """
        你是旅行助手的行程规划角色。规则：
        1. 只能使用用户消息中提供的景点、天气和购票数据，不得补充任何未提供的景点、价格或开放时间。
        2. 信息缺失就说缺失。票价、开放时间未知时写「未知」，不要估算。
        3. 不输出任何网址。购票只需说明「以官方页面为准」。
        4. 不承诺余票、不承诺具体到达时刻，路线耗时只是预估。
        5. 用中文，分点回答，控制在 400 字以内。先给结论，再说依据。
        6. 结尾用一句话提醒用户：确认后再保存到正式方案。
        """;
  }

  private String userPrompt(PlanningRequest request, List<Attraction> candidates,
      WeatherResult weather) {
    StringBuilder sb = new StringBuilder();
    sb.append("需求：\n");
    sb.append("- 目的地：").append(request.destination().strip()).append('\n');
    sb.append("- 天数：").append(request.days() == null ? "未提供" : request.days() + " 天").append('\n');
    sb.append("- 出发日期：").append(request.departDate() == null
        ? "未提供" : request.departDate()).append('\n');
    sb.append("- 返回日期：").append(request.returnDate() == null
        ? "未提供" : request.returnDate()).append('\n');
    sb.append("- 人数：").append(request.travelers() == null ? "未提供" : request.travelers()).append('\n');
    sb.append("- 预算：").append(request.budget() == null
        ? "未提供" : request.budget().toPlainString() + " " + request.currencyOrDefault()).append('\n');
    sb.append("- 偏好：").append(request.preferences().isEmpty()
        ? "未提供" : String.join("、", request.preferences())).append('\n');

    sb.append("\n候选景点（来自本地知识库，只能从这里选）：\n");
    if (candidates.isEmpty()) {
      sb.append("- 无（本地库未收录该城市）\n");
    } else {
      for (Attraction a : candidates) {
        sb.append("- ").append(a.name());
        if (a.city() != null) {
          sb.append("（").append(a.city()).append("）");
        }
        if (a.type() != null) {
          sb.append(" 类型：").append(a.type());
        }
        sb.append(" 票价：").append(a.ticket() == null ? "未知" : a.ticket());
        sb.append(" 开放时间：").append(a.openTime() == null ? "未知" : a.openTime());
        sb.append('\n');
      }
    }

    sb.append("\n天气预报：");
    if (weather == null) {
      sb.append("未获取\n");
    } else {
      sb.append(weather.city()).append("（").append(weather.reportTime()).append(" 发布）\n");
      weather.days().forEach(d -> sb.append("- ").append(d.date())
          .append(' ').append(d.dayWeather())
          .append(' ').append(d.dayTemp()).append("°C")
          .append(' ').append(d.dayWind()).append(d.dayPower()).append('\n'));
    }

    sb.append("\n请基于以上数据给出一份可执行的安排建议。");
    return sb.toString();
  }
}
