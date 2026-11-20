package com.travelassistant.server.interfaces.rest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Stateless export of the displayed guide; never saves a user's itinerary. */
@RestController
public class GuidePdfController {
  private final String fontPath;
  public GuidePdfController(@Value("${travel.pdf.font:${TRAVEL_PDF_FONT:C:/Windows/Fonts/simhei.ttf}}") String fontPath) {
    this.fontPath=fontPath;
  }
  public record Request(String title,String text) {}

  @PostMapping(value="/api/guides/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> export(@RequestBody Request request) throws IOException {
    if(request.text()==null || request.text().isBlank() || request.text().length()>120_000
        || request.title()!=null && request.title().length()>120)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供攻略正文（最多120000字符）及简短标题");
    if(!Files.isRegularFile(Path.of(fontPath)))
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"PDF中文字体未配置，请设置 TRAVEL_PDF_FONT 为中文 TTF 字体路径");
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
        .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=travel-guide.pdf")
        .header(HttpHeaders.CACHE_CONTROL,"no-store")
        .body(render(request.title()==null?"旅行攻略":request.title(),request.text(),Path.of(fontPath)));
  }

  static byte[] render(String title,String text,Path fontPath) throws IOException {
    try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
      var font=PDType0Font.load(doc,fontPath.toFile());
      List<String> lines=new ArrayList<>();
      lines.addAll(wrap(title,font)); lines.add(""); lines.addAll(wrap(text,font));
      int capacity=42, pages=(lines.size()+capacity-1)/capacity;
      for(int pageIndex=0;pageIndex<pages;pageIndex++) {
        var page=new PDPage(PDRectangle.A4); doc.addPage(page);
        try(var cs=new PDPageContentStream(doc,page)) {
          cs.beginText();cs.setFont(font,11);cs.setLeading(17);cs.newLineAtOffset(44,790);
          for(int i=pageIndex*capacity;i<Math.min(lines.size(),(pageIndex+1)*capacity);i++) {
            cs.showText(lines.get(i));cs.newLine();
          }
          cs.endText();cs.beginText();cs.setFont(font,9);cs.newLineAtOffset(265,28);
          cs.showText("第 "+(pageIndex+1)+" / "+pages+" 页");cs.endText();
        }
      }
      doc.save(out); return out.toByteArray();
    }
  }
  private static List<String> wrap(String text,PDType0Font font) throws IOException {
    List<String> lines=new ArrayList<>();
    for(String paragraph:text.replace("\r","").split("\n",-1)) {
      StringBuilder line=new StringBuilder();float width=0;
      for(int cp:paragraph.codePoints().toArray()) {
        if(Character.isISOControl(cp))cp=' ';
        String c=new String(Character.toChars(cp));float w;
        try { w=font.getStringWidth(c)*11/1000; }
        catch(IllegalArgumentException e) { c="?"; w=font.getStringWidth(c)*11/1000; }
        if(width+w>507 && !line.isEmpty()){lines.add(line.toString());line.setLength(0);width=0;}
        line.append(c);width+=w;
      }
      lines.add(line.toString());
    }
    return lines;
  }
}
