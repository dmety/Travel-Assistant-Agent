package com.travelassistant.client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 控制台排版回归测试。
 *
 * <p>只测纯函数与纯文本降级，不依赖真实终端、不发起网络请求。
 * 中文双宽字符是 Windows 黑框下最容易错位的地方，这里用显示宽度逐项断言。
 */
class ConsoleUITest {

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

  @Test
  void printlnAndPrintStripServerInjectedEscapes() {
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      try (ConsoleUI ui = ConsoleUI.plain()) {
        // 服务端文本里夹带 CSI 颜色、OSC 标题、清屏与光标归位——都必须被剥离。
        ui.println("\u001b[31m伪造成功\u001b[0m");
        ui.println("正常\u001b]0;PWNED\u0007文本");
        ui.print("\u001b[2J清屏\u001b[H");
        ui.println("第一行\r覆盖\u0007叮\u0000尾");
      }
    } finally {
      System.setOut(original);
    }
    String output = buffer.toString(StandardCharsets.UTF_8);
    // println 在 Windows 下自带 "\r\n" 行尾，先归一化再检查是否还有「行内注入的回车」。
    String normalized = output.replace("\r\n", "\n");
    assertFalse(output.contains("\u001b"), "服务端注入的 ESC 必须被剥离");
    assertFalse(normalized.contains("\r"), "回车会被用来覆盖行首伪造输出");
    assertFalse(output.contains("\u0007"), "BEL 必须被剥离");
    assertFalse(output.contains("\u0000"), "NUL 必须被剥离");
    assertFalse(output.contains("PWNED"), "OSC 标题载荷必须整段剥离");
    assertTrue(output.contains("伪造成功"));
    assertTrue(output.contains("正常"));
    assertTrue(output.contains("文本"));
    assertTrue(output.contains("清屏"));
    assertTrue(output.contains("第一行覆盖"));
  }

  @Test
  void sanitizeStripsEscapesAndControlsButKeepsPrintableText() {
    assertEquals("", ConsoleUI.sanitize(null));
    assertEquals("", ConsoleUI.sanitize(""));
    assertEquals("杭州 West 湖😀", ConsoleUI.sanitize("杭州 West 湖😀"));
    assertEquals("红正常", ConsoleUI.sanitize("\u001b[31m红\u001b[0m正常"));
    assertEquals("标题文本", ConsoleUI.sanitize("\u001b]0;PWNED\u0007标题文本"));
    assertEquals("清屏", ConsoleUI.sanitize("\u001b[2J清屏\u001b[H"));
    assertEquals("第一行覆盖", ConsoleUI.sanitize("第一行\r覆盖"));
    assertEquals("叮尾", ConsoleUI.sanitize("叮\u0007\u0000尾"));
    assertEquals("a\nb\tc", ConsoleUI.sanitize("a\nb\tc"));
    assertEquals("ab", ConsoleUI.sanitize("a\u007fb"));
    // 8-bit C1 控制字符（含 CSI/OSC 引导符）一律剥离，终端无法再解释成命令。
    String c1 = ConsoleUI.sanitize("\u009b31m\u009dx");
    assertFalse(c1.contains("\u009b"));
    assertFalse(c1.contains("\u009d"));
  }

  @Test
  void renderTableStripsInjectedAnsiFromCells() {
    String table = ConsoleUI.renderTable(
        List.of("名称", "城市"),
        List.of(List.of("\u001b[31m西湖\u001b[0m", "杭州\u001b]0;X\u0007")));
    assertFalse(table.contains("\u001b"));
    assertTrue(table.contains("西湖"));
    assertTrue(table.contains("杭州"));
    // 宽度按剥离后的文本计算：两列各 4 格 + 2 间隔 = 10，不被 ANSI 字节撑大。
    String[] lines = table.split("\n", -1);
    assertEquals(10, ConsoleUI.displayWidth(lines[0]));
    assertEquals(10, ConsoleUI.displayWidth(lines[2]));
  }

  @Test
  void styledMethodsKeepLegitColorButStripInjectedAnsi() throws Exception {
    // 单测无 tty，color 恒为 false；用反射强制开启着色，验证 paint 的合法 ANSI
    // 不会被 sanitize 抹掉，而服务端注入的转义仍被剥离。
    ConsoleUI ui = ConsoleUI.plain();
    Field colorField = ConsoleUI.class.getDeclaredField("color");
    colorField.setAccessible(true);
    colorField.setBoolean(ui, true);
    PrintStream original = System.out;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      ui.success("\u001b[31m红\u001b[0m\u001b]0;PWNED\u0007正常");
    } finally {
      System.setOut(original);
    }
    String output = buffer.toString(StandardCharsets.UTF_8);
    assertTrue(output.contains("\u001b["), "paint 生成的合法着色必须保留");
    assertFalse(output.contains("\u001b[31m"), "服务端注入的红色必须被剥离");
    assertFalse(output.contains("PWNED"), "服务端注入的 OSC 标题必须被剥离");
    assertTrue(output.contains("正常"));
    assertTrue(output.contains("红"));
    ui.close();
  }
}
