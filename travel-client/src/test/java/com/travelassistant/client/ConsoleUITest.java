package com.travelassistant.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

/**
 * 控制台排版回归测试。
 *
 * <p>只测纯函数与纯文本降级，不依赖真实终端、不发起网络请求。
 * 中文双宽字符是 Windows 黑框下最容易错位的地方，这里用显示宽度逐项断言。
 */
class ConsoleUITest {

  @Test
  void maskedInputDoesNotLeakOrKeepItsPromptForTheNextCommand() throws Exception {
    String secret = "probe-only-123";
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal = new org.jline.terminal.impl.DumbTerminal("test", "dumb",
        new ByteArrayInputStream((secret + "\rhelp\r").getBytes(StandardCharsets.UTF_8)),
        output, StandardCharsets.UTF_8)) {
      var attributes = terminal.getAttributes();
      attributes.setLocalFlag(org.jline.terminal.Attributes.LocalFlag.ECHO, true);
      terminal.setAttributes(attributes);
      assertEquals(secret, ConsoleUI.readMasked(terminal, terminal.writer(), "密码: "));
      assertTrue(terminal.echo());
      var reader = org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build();
      assertEquals("help", reader.readLine("admin > "));
      assertFalse(output.toString(StandardCharsets.UTF_8).contains(secret));
      assertEquals(1, output.toString(StandardCharsets.UTF_8).split("密码:", -1).length - 1);
      assertTrue(output.toString(StandardCharsets.UTF_8).contains("admin >"));
      assertFalse(reader.getHistory().iterator().next().line().contains(secret));
    }
  }

  @Test
  void maskedInputSupportsBackspaceAndRestoresTerminalAfterCancellation() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal = new org.jline.terminal.impl.DumbTerminal("test", "dumb",
        new ByteArrayInputStream("abc\177X\rhidden\003help\r".getBytes(StandardCharsets.UTF_8)),
        output, StandardCharsets.UTF_8)) {
      assertEquals("abX", ConsoleUI.readMasked(terminal, terminal.writer(), "密码: "));
      var before = terminal.getAttributes().getLocalFlags();
      org.junit.jupiter.api.Assertions.assertThrows(org.jline.reader.UserInterruptException.class,
          () -> ConsoleUI.readMasked(terminal, terminal.writer(), "密码: "));
      assertEquals(before, terminal.getAttributes().getLocalFlags());
      assertEquals("help", org.jline.reader.LineReaderBuilder.builder().terminal(terminal).build().readLine("未登录 > "));
      assertFalse(output.toString(StandardCharsets.UTF_8).contains("hidden"));
    }
  }

  @Test
  void maskedInputReturnsNullOnEndOfInputWithoutLeakingPartialPassword() throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (var terminal = new org.jline.terminal.impl.DumbTerminal("test", "dumb",
        new ByteArrayInputStream("unfinished".getBytes(StandardCharsets.UTF_8)), output, StandardCharsets.UTF_8)) {
      org.junit.jupiter.api.Assertions.assertNull(ConsoleUI.readMasked(terminal, terminal.writer(), "密码: "));
      assertFalse(output.toString(StandardCharsets.UTF_8).contains("unfinished"));
    }
  }

  @Test
  void displayWidthHandlesAsciiCjkAndCombiningMarks() {
    assertEquals(0, ConsoleUI.displayWidth(null));
    assertEquals(0, ConsoleUI.displayWidth(""));
    assertEquals(3, ConsoleUI.displayWidth("abc"));
    assertEquals(4, ConsoleUI.displayWidth("杭州"));
    assertEquals(2, ConsoleUI.displayWidth("，"));
    assertEquals(12, ConsoleUI.displayWidth("杭州 West 湖"));
  }

  @Test
  void displayWidthHandlesEmojiAndControlCharacters() {
    assertEquals(2, ConsoleUI.displayWidth("😀"));
    assertEquals(0, ConsoleUI.displayWidth("\t"));
    assertEquals(0, ConsoleUI.displayWidth("\u001b"));
    // e + 组合锐音符：组合记号不占格。
    assertEquals(1, ConsoleUI.displayWidth("e\u0301"));
    assertEquals(5, ConsoleUI.displayWidth("A中😀"));
  }

  @Test
  void padRightUsesDisplayWidthNotLength() {
    assertEquals("杭州  ", ConsoleUI.padRight("杭州", 6));
    assertEquals(6, ConsoleUI.displayWidth(ConsoleUI.padRight("杭州", 6)));
    assertEquals("abcdef", ConsoleUI.padRight("abcdef", 3));
    assertEquals("   ", ConsoleUI.padRight(null, 3));
  }

  @Test
  void truncateKeepsResultWithinWidthLimit() {
    assertEquals("杭州", ConsoleUI.truncateToWidth("杭州", 8));
    assertEquals("", ConsoleUI.truncateToWidth("杭州", 0));
    String longText = "这是一个非常非常长的景点名称用来测试列宽上限与省略号";
    String cut = ConsoleUI.truncateToWidth(longText, 12);
    assertTrue(ConsoleUI.displayWidth(cut) <= 12);
    assertTrue(cut.endsWith("…"));
    assertTrue(ConsoleUI.displayWidth(cut) > 0);
    // 混合中英文时不能把中文从中间劈开。
    String mixed = ConsoleUI.truncateToWidth("西湖 West Lake 风景名胜区", 9);
    assertTrue(ConsoleUI.displayWidth(mixed) <= 9);
  }

  @Test
  void renderTableAlignsCjkColumns() {
    String table = ConsoleUI.renderTable(
        List.of("名称", "城市"),
        List.of(List.of("西湖", "杭州"), List.of("A", "B")));
    String[] lines = table.split("\n", -1);
    assertEquals(4, lines.length);
    // 两列各 4 格，列间 2 格，总宽 10。
    assertEquals(10, ConsoleUI.displayWidth(lines[0]));
    assertEquals(10, ConsoleUI.displayWidth(lines[1]));
    assertEquals(10, ConsoleUI.displayWidth(lines[2]));
    assertEquals(10, ConsoleUI.displayWidth(lines[3]));
    assertTrue(lines[1].matches("-+"));
    assertTrue(lines[2].contains("西湖"));
    assertTrue(lines[2].contains("杭州"));
    assertFalse(table.contains("\u001b"));
  }

  @Test
  void renderTableCapsOverlongCellsWithoutAnsi() {
    String longName = "这是一个非常非常长的景点名称用来测试列宽上限与省略号处理是否正确";
    String table = ConsoleUI.renderTable(
        List.of("名称", "城市"),
        List.of(List.of(longName, "杭州")));
    String[] lines = table.split("\n", -1);
    assertEquals(3, lines.length);
    assertTrue(ConsoleUI.displayWidth(lines[2]) <= 36 + 2 + 4);
    assertTrue(lines[2].contains("…"));
    assertFalse(table.contains("\u001b"));
  }

  @Test
  void renderTableHandlesEmptyInputAndShortRows() {
    assertEquals("", ConsoleUI.renderTable(List.of(), List.of()));
    String table = ConsoleUI.renderTable(
        List.of("A", "B", "C"), List.of(List.of("1")));
    String[] lines = table.split("\n", -1);
    assertEquals(3, lines.length);
    assertEquals(ConsoleUI.displayWidth(lines[0]), ConsoleUI.displayWidth(lines[2]));
  }

  @Test
  void clipboardImageIsEncodedAsBoundedJpegDataUrl() throws Exception {
    var image = new java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    var graphics = image.createGraphics();
    try { graphics.setColor(java.awt.Color.BLUE); graphics.fillRect(0, 0, 64, 48); }
    finally { graphics.dispose(); }
    String dataUrl = ConsoleUI.encodeClipboardImage(image);
    assertTrue(dataUrl.startsWith("data:image/jpeg;base64,"));
    byte[] bytes = java.util.Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1));
    assertTrue(bytes.length <= 2 * 1024 * 1024);
    var decoded = ImageIO.read(new ByteArrayInputStream(bytes));
    assertEquals(64, decoded.getWidth());
    assertEquals(48, decoded.getHeight());
  }

  @Test
  void plainUiNeverEmitsEscapeSequences() {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      try (ConsoleUI ui = ConsoleUI.plain()) {
        assertFalse(ui.isInteractive());
        assertFalse(ui.isColorEnabled());
        ui.println("== 景点详情 ==");
        ui.println("■ 已接受邀请");
        ui.println("  · 这是一条提示");
        ui.title("服务状态");
        ui.kv("城市", "杭州");
        ui.table(List.of("名称"), List.of(List.of("西湖")));
      }
    } finally {
      System.setOut(original);
    }
    String output = buffer.toString(StandardCharsets.UTF_8);
    assertFalse(output.contains("\u001b"));
    assertTrue(output.contains("== 景点详情 =="));
    assertTrue(output.contains("■ 已接受邀请"));
    assertTrue(output.contains("城市"));
    assertTrue(output.contains("杭州"));
  }

  @Test
  void interactiveFallsBackSafelyWithoutRealTerminal() {
    // 单测环境通常没有 tty：构造不应抛异常，且颜色必须关闭。
    assertDoesNotThrow(() -> {
      try (ConsoleUI ui = ConsoleUI.interactive("help", "exit")) {
        assertFalse(ui.isColorEnabled());
        // 不在此处调用 readLine：无 tty 时它可能阻塞等待输入。
      }
    });
  }
}
