package com.travelassistant.server.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.intent.ConversationTurn;
import com.travelassistant.server.application.place.FamousPlaces;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 著名景点归属表：不让知识库里的同名冷门条目抢走常识里的那个景区。 */
@DisplayName("著名景点权威归属")
class FamousPlacesTest {

  @Test
  @DisplayName("华山/黄山/老君山：按景区原名检索，不被济南/广州/成都带走")
  void famousSpotsUseOriginalName() {
    assertThat(FamousPlaces.find("华山").orElseThrow().destination()).isEqualTo("华山");
    assertThat(FamousPlaces.find("华山").orElseThrow().city()).isEqualTo("渭南");
    assertThat(FamousPlaces.find("去黄山玩4天").orElseThrow().destination()).isEqualTo("黄山");
    assertThat(FamousPlaces.find("老君山").orElseThrow().destination()).isEqualTo("老君山");
    assertThat(FamousPlaces.find("老君山").orElseThrow().city()).isEqualTo("洛阳");
  }

  @Test
  @DisplayName("知识库里正确收录的景区按城市检索，沿用可核验条目")
  void coveredSpotsUseCity() {
    assertThat(FamousPlaces.find("故宫").orElseThrow().destination()).isEqualTo("北京");
    assertThat(FamousPlaces.find("兵马俑").orElseThrow().destination()).isEqualTo("西安");
    assertThat(FamousPlaces.find("鼓浪屿").orElseThrow().destination()).isEqualTo("厦门");
    assertThat(FamousPlaces.find("西湖").orElseThrow().destination()).isEqualTo("杭州");
  }

  @Test
  @DisplayName("用户显式点名同名冷门地或同名机构：权威表主动让位")
  void respectsExplicitChoice() {
    assertThat(FamousPlaces.find("济南华山")).isEmpty();
    assertThat(FamousPlaces.find("广州黄山鲁森林公园")).isEmpty();
    assertThat(FamousPlaces.find("成都老君山")).isEmpty();
    assertThat(FamousPlaces.find("福州西湖公园")).isEmpty();
  }

  @Test
  @DisplayName("会话旧城市冲突判定：济南/成都要纠正，渭南/原名不纠正")
  void detectsStaleCity() {
    FamousPlaces.Entry huashan = FamousPlaces.find("华山").orElseThrow();
    assertThat(FamousPlaces.conflicts("济南", huashan)).isTrue();
    assertThat(FamousPlaces.conflicts("广州", FamousPlaces.find("黄山").orElseThrow())).isTrue();
    assertThat(FamousPlaces.conflicts("成都", FamousPlaces.find("老君山").orElseThrow())).isTrue();
    assertThat(FamousPlaces.conflicts("渭南", huashan)).isFalse();
    assertThat(FamousPlaces.conflicts("华山", huashan)).isFalse();
    assertThat(FamousPlaces.conflicts(null, huashan)).isFalse();
  }

  @Test
  @DisplayName("查最近用户轮次：助手转述的错城市不作数；明确换了地方就不沿用")
  void findsActivePlaceFromHistory() {
    List<ConversationTurn> history = List.of(
        new ConversationTurn(ConversationTurn.USER, "华山"),
        new ConversationTurn(ConversationTurn.ASSISTANT, "我按济南的华山风景区来排"));

    assertThat(FamousPlaces.findInRecentUserTurns(history).orElseThrow().name())
        .isEqualTo("华山");
    assertThat(FamousPlaces.mentionsOtherPlace("4天3个人，帮我排一下")).isFalse();
    assertThat(FamousPlaces.mentionsOtherPlace("换成西安玩3天")).isTrue();
    assertThat(FamousPlaces.mentionsOtherPlace("去成都市")).isTrue();
  }
}
