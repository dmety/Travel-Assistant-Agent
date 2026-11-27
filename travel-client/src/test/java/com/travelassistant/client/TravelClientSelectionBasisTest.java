package com.travelassistant.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * 回复层「筛选依据」去来源测试。
 *
 * <p>selectionBasis 是服务端返回的整段文本，回复里只保留排序/匹配理由，
 * 抹掉字段来源与信息日期；完整依据仍由 /details 承载，故只断言剥离结果。
 */
class TravelClientSelectionBasisTest {

  @Test
  void stripsFieldSourceBracketAndKeepsRankingReason() {
    String reason = "等级未返回，按可见评分「5.0」排序，字段来源「百度 [值=5.0; 信息日期=未知]」";
    String stripped = TravelClient.stripSourceDisclosure(reason);
    assertFalse(stripped.contains("来源"));
    assertFalse(stripped.contains("信息日期"));
    assertFalse(stripped.contains("百度"));
    assertEquals("等级未返回，按可见评分「5.0」排序", stripped);
  }

  @Test
  void stripsPlainSourceBracketAndTrailingDelimiter() {
    String reason = "匹配必去点和偏好后按可见评分/等级排序；等级「景区」，来源「KB-curated [值=景区; 信息日期=未知]」";
    String stripped = TravelClient.stripSourceDisclosure(reason);
    assertFalse(stripped.contains("KB-curated"));
    assertFalse(stripped.endsWith("，"));
    assertEquals("匹配必去点和偏好后按可见评分/等级排序；等级「景区」", stripped);
  }

  @Test
  void stripsEqualsFormSourceWithSquareBracket() {
    String reason = "等级未返回，按可见评分「4.8」排序；接口评价量=13，来源=百度 [值=13; 信息日期=未知]；评价量不核验";
    String stripped = TravelClient.stripSourceDisclosure(reason);
    assertFalse(stripped.contains("来源"));
    assertFalse(stripped.contains("百度"));
    assertFalse(stripped.contains("信息日期"));
    assertEquals("等级未返回，按可见评分「4.8」排序；接口评价量=13；评价量不核验", stripped);
  }

  @Test
  void keepsTextWithoutSourceDisclosureUnchanged() {
    String reason = "按可见评分从高到低排序";
    assertEquals(reason, TravelClient.stripSourceDisclosure(reason));
  }

  @Test
  void returnsEmptyForNullOrBlank() {
    assertEquals("", TravelClient.stripSourceDisclosure(null));
    assertEquals("", TravelClient.stripSourceDisclosure(""));
    assertEquals("", TravelClient.stripSourceDisclosure("来源「百度」"));
  }
}
