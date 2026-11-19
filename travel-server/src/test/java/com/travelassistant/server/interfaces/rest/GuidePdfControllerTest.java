package com.travelassistant.server.interfaces.rest;

import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class GuidePdfControllerTest {
  @Test void rejectsEmptyAndOversizedBodyBeforeLoadingFont() {
    var api=new GuidePdfController("missing.ttf");
    assertThatThrownBy(()->api.export(new GuidePdfController.Request("攻略",""))).isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(()->api.export(new GuidePdfController.Request("攻略","字".repeat(120001)))).isInstanceOf(ResponseStatusException.class);
  }
  @Test void missingFontIsExplicitServiceError() {
    assertThatThrownBy(()->new GuidePdfController("missing.ttf").export(new GuidePdfController.Request("攻略","正文")))
        .isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));
  }
  @Test @EnabledIfSystemProperty(named="os.name",matches="Windows.*")
  void chineseLongTextSurvivesWrappingAndPagination() throws Exception {
    String text="武汉到大同｜门票未知，不按免费计算；交通参考价200元。\n".repeat(100)+"最终待核验事项";
    byte[] bytes=GuidePdfController.render("旅行攻略",text,Path.of("C:/Windows/Fonts/simhei.ttf"));
    try(var doc=Loader.loadPDF(bytes)) {
      assertThat(doc.getNumberOfPages()).isGreaterThan(2);
      var extracted=new PDFTextStripper().getText(doc);
      assertThat(extracted).contains("旅行攻略","武汉到大同","最终待核验事项");
      assertThat(extracted.split("武汉到大同",-1)).hasSize(101);
    }
  }
}
