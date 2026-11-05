package com.travelassistant.server.application.itinerary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.travelassistant.server.application.collaboration.ChangeLogRecorder;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.domain.itinerary.Member;
import com.travelassistant.server.domain.itinerary.Plan;
import com.travelassistant.server.domain.itinerary.Section;
import com.travelassistant.server.infrastructure.persistence.AccountStore;
import com.travelassistant.server.infrastructure.persistence.DecisionStore;
import com.travelassistant.server.infrastructure.persistence.ItineraryStore;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 权限 × 归档 × 版本 的决策表（V3 第 4 节）。
 *
 * <p>这三条规则在服务层交织在一起：一次写入可能同时「无权限」「已归档」「版本过期」。
 * 集成测试也能覆盖，但每个组合都要走一遍注册、建行程、加成员、归档，一个组合约 200 ms，
 * 而且失败时只能看到 HTTP 状态码。这里把持久化全部 mock 掉，直接断言**抛出的异常类型**，
 * 一个组合不到 1 ms，失败信息直接指向「哪一维判断错了」。
 *
 * <p>刻意不重复断言 HTTP 映射：403/409/404 与响应体形状由 {@code ErrorPriorityTest}
 * 在集成层负责。同一规则只在一层断言，否则改一处文案要修两处测试。
 */
@DisplayName("行程服务：权限 × 归档 × 版本")
class ItineraryServiceTest {

  private static final String ID = "itin-decision-1";
  private static final String OWNER = "alice";
  private static final String MATE_PLAN = "bob";
  private static final String MATE_DAILY = "cara";
  private static final String STRANGER = "dave";

  private ItineraryStore store;
  private ChangeLogRecorder changeLog;
  private ItineraryService service;

  @BeforeEach
  void setUp() {
    store = mock(ItineraryStore.class);
    changeLog = mock(ChangeLogRecorder.class);
    service = new ItineraryService(store, mock(AccountStore.class), changeLog,
        mock(DecisionStore.class));
  }

  /** 基准行程：创建者 + 一个只有 PLAN 权、一个只有 DAILY 权的成员；归档与否决定版本号。 */
  private static Itinerary fixture(boolean archived) {
    Itinerary it = Itinerary.create(OWNER, "杭州三日", Plan.empty());
    it = it.withMembers(List.of(
        new Member(MATE_PLAN, Set.of(Section.PLAN)),
        new Member(MATE_DAILY, Set.of(Section.DAILY))));
    return archived ? it.withArchived(true) : it;
  }

  private static Stream<Arguments> decisions() {
    return Stream.of(
        // 身份, 板块, 已归档, 版本偏移, 期望异常（null = 应当成功）
        Arguments.of(OWNER, Section.PLAN, false, 0, null, "创建者改方案"),
        Arguments.of(OWNER, Section.PLAN, false, -1, VersionConflictException.class,
            "创建者版本过期"),
        Arguments.of(OWNER, Section.PLAN, true, 0, ItineraryArchivedException.class,
            "创建者改已归档"),
        Arguments.of(OWNER, Section.PLAN, true, -1, ItineraryArchivedException.class,
            "归档优先于版本冲突"),
        Arguments.of(MATE_PLAN, Section.PLAN, false, 0, null, "有 PLAN 权的成员改方案"),
        Arguments.of(MATE_PLAN, Section.PLAN, true, 0, ItineraryArchivedException.class,
            "有权限但行程已归档"),
        Arguments.of(MATE_DAILY, Section.DAILY, false, 0, null, "有 DAILY 权的成员改日程"),
        Arguments.of(MATE_DAILY, Section.PLAN, false, 0, ItineraryForbiddenException.class,
            "只有 DAILY 权却改方案"),
        Arguments.of(MATE_DAILY, Section.PLAN, true, 0, ItineraryForbiddenException.class,
            "无该板块权限时，归档状态不得抢先"),
        Arguments.of(STRANGER, Section.PLAN, false, 0, ItineraryForbiddenException.class,
            "非成员改未归档"),
        Arguments.of(STRANGER, Section.PLAN, true, 0, ItineraryForbiddenException.class,
            "非成员改已归档——不得暴露 archived"),
        Arguments.of(STRANGER, Section.EXPENSE, true, 0, ItineraryForbiddenException.class,
            "非成员改已归档的费用"));
  }

  @ParameterizedTest(name = "[{index}] {5}")
  @MethodSource("decisions")
  void decisionTable(String user, Section section, boolean archived, int versionOffset,
      Class<? extends Throwable> expected, String scenario) {
    Itinerary it = fixture(archived);
    when(store.findById(ID)).thenReturn(Optional.of(it));
    long version = it.version() + versionOffset;

    if (expected == null) {
      Itinerary updated = write(section, user, version);
      assertThat(updated.version()).as("成功的写入必须把版本推进一步").isEqualTo(it.version() + 1);
      verify(store).save(updated);
    } else {
      assertThatThrownBy(() -> write(section, user, version)).isInstanceOf(expected);
      verify(store, never()).save(any());
    }
  }

  @Test
  @DisplayName("不存在的行程：先 404，连权限判断都不进")
  void unknownItineraryIsNotFound() {
    when(store.findById("missing")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updatePlan("missing", OWNER, Plan.empty(), 1))
        .isInstanceOf(ItineraryNotFoundException.class);
    verify(store, never()).save(any());
  }

  @Test
  @DisplayName("五个板块共用同一套校验：非成员改已归档行程，五条路径全部拒绝")
  void everySectionRejectsStrangerOnArchived() {
    Itinerary it = fixture(true);
    when(store.findById(ID)).thenReturn(Optional.of(it));

    for (Section section : Section.values()) {
      assertThatThrownBy(() -> write(section, STRANGER, it.version()))
          .as("板块 %s 漏掉了权限校验", section)
          .isInstanceOf(ItineraryForbiddenException.class);
    }
    verify(store, never()).save(any());
  }

  @Test
  @DisplayName("写入成功后记一条修改记录，板块名与版本号都取自本次写入")
  void successfulWriteIsRecorded() {
    Itinerary it = fixture(false);
    when(store.findById(ID)).thenReturn(Optional.of(it));

    Itinerary updated = service.updateExpenses(ID, OWNER, List.of(), it.version());

    verify(changeLog).record(ID, OWNER, "EXPENSE", "更新费用：0 笔", updated.version());
  }

  private Itinerary write(Section section, String user, long version) {
    return switch (section) {
      case PLAN -> service.updatePlan(ID, user, Plan.empty(), version);
      case DAILY -> service.updateDaily(ID, user, List.of(), version);
      case TRANSPORT -> service.updateTransports(ID, user, List.of(), version);
      case LODGING -> service.updateLodgings(ID, user, List.of(), version);
      case EXPENSE -> service.updateExpenses(ID, user, List.of(), version);
    };
  }
}
