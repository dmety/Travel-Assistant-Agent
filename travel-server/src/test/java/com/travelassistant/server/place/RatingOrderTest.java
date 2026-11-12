package com.travelassistant.server.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.place.RatingOrder;
import com.travelassistant.server.domain.attraction.Attraction;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「好评高的放前面」的口径：只按真实评分排序，缺评分不补分、不当差评，
 * 高评分的非景点科教条目先剔除，人文场馆保留。
 */
@DisplayName("景点评分排序 RatingOrder")
class RatingOrderTest {

  @Test
  @DisplayName("评分解析：空值、脏值、越界值都当作没有评分")
  void parsesOnlyRealScores() {
    assertThat(RatingOrder.parse("4.8")).isEqualByComparingTo("4.8");
    assertThat(RatingOrder.parse(" 4.5 ")).isEqualByComparingTo("4.5");
    assertThat(RatingOrder.parse(null)).isNull();
    assertThat(RatingOrder.parse("")).isNull();
    assertThat(RatingOrder.parse("[]")).isNull();
    assertThat(RatingOrder.parse("暂无")).isNull();
    assertThat(RatingOrder.parse("0")).isNull();
    assertThat(RatingOrder.parse("9.9")).isNull();
  }

  @Test
  @DisplayName("有评分优先并按评分降序，缺评分排后但不丢")
  void ranksByRealRating() {
    List<Attraction> ranked = RatingOrder.ranked(List.of(
        attraction("灵隐寺", null, null),
        attraction("某小吃街", null, "3.9"),
        attraction("西湖", "5A", "4.9"),
        attraction("千岛湖", "5A", "4.5")), 8);

    assertThat(ranked).extracting(Attraction::name)
        .containsExactly("西湖", "千岛湖", "某小吃街", "灵隐寺");
  }

  @Test
  @DisplayName("高评分的大学/图书馆不算景点；大学博物馆、旧址、观景台保留")
  void filtersNonTourismButKeepsHeritage() {
    assertThat(RatingOrder.isNonTourism(attraction("某大学", null, "5.0"))).isTrue();
    assertThat(RatingOrder.isNonTourism(attraction("滨海图书馆中心馆", null, "4.9"))).isTrue();
    assertThat(RatingOrder.isNonTourism(attraction("中国地质大学逸夫博物馆", null, "4.7"))).isFalse();
    assertThat(RatingOrder.isNonTourism(attraction("某中学旧址", null, "4.6"))).isFalse();
    assertThat(RatingOrder.isNonTourism(attraction("盐田高级中学观景台", null, "4.7"))).isFalse();
    assertThat(RatingOrder.ranked(List.of(attraction("某大学", null, "5.0"),
        attraction("西湖", "5A", "4.9")), 8)).extracting(Attraction::name)
        .containsExactly("西湖");
  }

  @Test
  @DisplayName("站前广场、停车场这类通行空间不算景点；火车站、机场本体不能一起排除")
  void filtersTransitFacilitiesButKeepsRealSpots() {
    // 真实案例：知识库里有「丽江站站前广场」，评分 5.0，不拦就排到最前
    assertThat(RatingOrder.isNonTourismName("丽江站站前广场")).isTrue();
    assertThat(RatingOrder.isNonTourism(attraction("丽江站站前广场", "其他", "5.0"))).isTrue();
    // 其他通行空间与配套设施
    assertThat(RatingOrder.isNonTourismName("某某站候车室")).isTrue();
    assertThat(RatingOrder.isNonTourismName("某某景区停车场")).isTrue();
    assertThat(RatingOrder.isNonTourismName("某某地铁站")).isTrue();
    assertThat(RatingOrder.isNonTourismName("某某收费站")).isTrue();
    // 不能误伤：浦口火车站、骆岗机场航站楼都是真景点
    assertThat(RatingOrder.isNonTourismName("浦口火车站")).isFalse();
    assertThat(RatingOrder.isNonTourismName("浦口火车站文化旅游区")).isFalse();
    assertThat(RatingOrder.isNonTourismName("原合肥骆岗机场航站楼")).isFalse();
    assertThat(RatingOrder.isNonTourismName("白塔火车站旧址")).isFalse();
    // 不排除宽泛的「广场」：「天安门广场」这类是真景点
    assertThat(RatingOrder.isNonTourismName("天安门广场")).isFalse();
    // 排序结果里站前广场必须消失
    assertThat(RatingOrder.ranked(List.of(attraction("丽江站站前广场", "其他", "5.0"),
        attraction("丽江古城", "5A", "4.8")), 8)).extracting(Attraction::name)
        .containsExactly("丽江古城");
  }

  @Test
  @DisplayName("ranked 不改变入参、按 limit 截断；空列表返回空")
  void capsAndDoesNotMutate() {
    List<Attraction> source = List.of(
        attraction("西湖", "5A", "4.9"),
        attraction("千岛湖", "5A", "4.5"),
        attraction("灵隐寺", null, "4.2"));
    assertThat(RatingOrder.ranked(source, 2)).extracting(Attraction::name)
        .containsExactly("西湖", "千岛湖");
    assertThat(source).extracting(Attraction::name)
        .containsExactly("西湖", "千岛湖", "灵隐寺");
    assertThat(RatingOrder.ranked(List.of(), 8)).isEmpty();
    assertThat(RatingOrder.ranked(null, 8)).isEmpty();
  }

  private static Attraction attraction(String name, String level, String rating) {
    return new Attraction("kb-" + name, name, "杭州", "浙江省", "attraction", level, rating,
        null, null, null, null, null, null, null, null, null,
        "KB", List.of("KB"), null, null, "GCJ-02");
  }
}
