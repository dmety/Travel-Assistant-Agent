package com.travelassistant.server.interfaces.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class GuidePdfControllerTest {
  @Test void exportsLongAsciiGuideWithPlatformFont() throws Exception {
    Path font = platformTestFont();
    String text = "Travel guide route details and attraction notes. ".repeat(300);

    var response = new GuidePdfController(font.toString())
        .export(new GuidePdfController.Request(null, text));

    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_PDF);
    assertThat(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION))
        .contains("travel-guide.pdf");
    try (var document = Loader.loadPDF(response.getBody())) {
      assertThat(document.getNumberOfPages()).isGreaterThan(1);
      assertThat(new PDFTextStripper().getText(document)).contains("Travel guide");
    }
  }

  private static Path platformTestFont() throws IOException {
    List<Path> candidates = System.getProperty("os.name").startsWith("Windows")
        ? List.of(Path.of("C:/Windows/Fonts/simhei.ttf"), Path.of("C:/Windows/Fonts/arial.ttf"))
        : List.of(
            Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
            Path.of("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"));
    return candidates.stream().filter(Files::isRegularFile).findFirst()
        .orElseThrow(() -> new IOException("No platform TTF font available for the PDF test"));
  }

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
