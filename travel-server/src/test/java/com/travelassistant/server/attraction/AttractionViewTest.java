package com.travelassistant.server.attraction;

import com.travelassistant.server.domain.attraction.Attraction;
import com.travelassistant.server.interfaces.rest.dto.AttractionView;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 核验状态归一：正式库用 verified，早期测试库用 confirmed，接口只输出统一词表。 */
@DisplayName("景点视图核验状态")
class AttractionViewTest {

  private static Attraction withStatus(String ticketStatus, String openTimeStatus) {
    return new Attraction("t-1", "测试景点", "杭州", "浙江省",
        null, null, null, null, null, null, null, null, null, null,
        null, null, null, List.of(), ticketStatus, openTimeStatus, "WGS-84");
  }

  @Test
  @DisplayName("正式库词表与历史词表都归一到统一取值")
  void normalizesStatusVocabulary() {
    AttractionView view = AttractionView.from(withStatus("verified", "confirmed"));
    assertThat(view.ticketStatus()).isEqualTo("verified");
    assertThat(view.openTimeStatus()).isEqualTo("verified");

    AttractionView mixed = AttractionView.from(withStatus("unverified", "pending"));
    assertThat(mixed.ticketStatus()).isEqualTo("unverified");
    assertThat(mixed.openTimeStatus()).isEqualTo("pending");
  }

  @Test
  @DisplayName("空状态按 pending，未知取值保守按 unverified")
  void unknownStatusIsConservative() {
    AttractionView blank = AttractionView.from(withStatus(null, "  "));
    assertThat(blank.ticketStatus()).isEqualTo("pending");
    assertThat(blank.openTimeStatus()).isEqualTo("pending");
    assertThat(AttractionView.from(withStatus("some-new-value", null)).ticketStatus())
        .isEqualTo("unverified");
  }

  @Test
  @DisplayName("标为已核验也不能把缺失票价解释成免费")
  void statusDoesNotChangeMissingValueSemantics() {
    AttractionView view = AttractionView.from(withStatus("verified", "verified"));
    assertThat(view.ticket()).isNull();
    assertThat(view.ticketText()).isNotBlank();
  }
}
