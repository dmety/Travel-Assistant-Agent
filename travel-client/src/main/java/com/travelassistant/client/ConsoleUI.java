package com.travelassistant.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/**
 * 控制台渲染与输入。
 *
 * <p>只负责表现层，不发起任何网络请求。设计上有三条硬约束：
 * <ul>
 *   <li>所有排版按<strong>显示宽度</strong>计算，中文、全角标点与 emoji 占 2 格，
 *       组合字符占 0 格；不能用 {@code String.length()} 或简单的 {@code >127} 判断；</li>
 *   <li>非交互环境（管道、IDE、无 tty、{@code NO_COLOR}）一律输出纯文本，
 *       保证 {@code export} 等命令重定向到文件时不夹带 ANSI 转义序列；</li>
 *   <li>渲染逻辑全部是静态纯函数，可脱离终端做单元测试。</li>
 * </ul>
 */
public final class ConsoleUI implements AutoCloseable {

  /** 单列上限，防止长简介或长地址把表格撑出终端。 */
  private static final int MAX_COLUMN_WIDTH = 36;
  private static final int LABEL_WIDTH = 10;
  private static final String GAP = "  ";

  private final Terminal terminal;
  private final LineReader reader;
  private final boolean color;
  private final List<String> suggestions = new ArrayList<>();

  private ConsoleUI(boolean interactive, Collection<String> initialSuggestions) {
    suggestions.addAll(initialSuggestions);
    Terminal builtTerminal = null;
    LineReader builtReader = null;
    if (interactive) {
      try {
        builtTerminal = TerminalBuilder.builder().system(true).build();
        builtReader = LineReaderBuilder.builder()
            .terminal(builtTerminal)
            .parser(new DefaultParser())
            .completer(this::complete)
            .history(new DefaultHistory())
            .build();
      } catch (IOException | RuntimeException e) {
        // 没有真实终端时退回纯文本，不因为终端能力探测失败而让整个客户端不可用。
        builtTerminal = null;
        builtReader = null;
      }
    }
    this.terminal = builtTerminal;
    this.reader = builtReader;
    boolean envDisabled = System.getenv("NO_COLOR") != null
        || Boolean.getBoolean("travel.cli.noColor");
    this.color = builtTerminal != null
        && !"dumb".equalsIgnoreCase(builtTerminal.getType())
        && System.console() != null
        && !envDisabled;
  }

  /** 交互式终端：启用颜色、历史与 Tab 补全。 */
  public static ConsoleUI interactive(String... initialSuggestions) {
    return new ConsoleUI(true, List.of(initialSuggestions));
  }

  /** 一次性命令与脚本用法：纯文本输出，绝不写 ANSI。 */
  public static ConsoleUI plain() {
    return new ConsoleUI(false, List.of());
  }

  public boolean isInteractive() {
    return reader != null;
  }

  public boolean isColorEnabled() {
    return color;
  }

  /** 更新 Tab 补全候选（例如查到景点后可把城市名加进来）。 */
  public void setSuggestions(Collection<String> values) {
    suggestions.clear();
    if (values != null) {
      suggestions.addAll(values);
    }
  }

  private void complete(LineReader lineReader, ParsedLine line, List<Candidate> candidates) {
    String word = line.word() == null ? "" : line.word();
    for (String value : suggestions) {
      if (!value.isEmpty() && value.startsWith(word) && !value.equals(word)) {
        candidates.add(new Candidate(value));
      }
    }
  }

  /** 读取一行（支持行编辑、上下键历史、Tab 补全）。无终端时返回 null。 */
  public String readLine(String prompt) {
    return reader == null ? null : reader.readLine(prompt);
  }

  /** 读取敏感输入，回显掩码。无终端时返回 null。 */
  public String readMasked(String prompt) {
    return reader == null ? null : reader.readLine(prompt, '*', null);
  }

  // ---- 输出 ----

  public void println() {
    System.out.println();
  }

  public void println(String text) {
    System.out.println(decorate(text));
  }

  public void print(String text) {
    System.out.print(text == null ? "" : text);
  }

  /**
   * 给既有输出做最小装饰：只加 ANSI，不改任何字符。
   * 这样 TravelClient 原有的上百处 println 无需逐行改写即可统一观感，
   * 且纯文本模式下 {@code decorate} 原样返回，脚本输出逐字不变。
   */
  private String decorate(String text) {
    if (!color || text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    if (text.startsWith("==") && text.endsWith("==")) {
      return paint(text, AttributedStyle.CYAN, true);
    }
    if (text.startsWith("■ ")) {
      return paint(text, AttributedStyle.GREEN, true);
    }
    if (text.startsWith("  · ") || text.startsWith("· ")) {
      return paint(text, AttributedStyle.YELLOW, false);
    }
    return text;
  }

  /** 一级标题：上留一行空行，标题加青色粗体，下面一条分隔线。 */
  public void title(String text) {
    println();
    println(paint("  " + text, AttributedStyle.CYAN, true));
    println("  " + "-".repeat(Math.min(64, Math.max(24, displayWidth(text) + 6))));
  }

  /** 二级小节标题。 */
  public void section(String text) {
    println(paint("  " + text, AttributedStyle.YELLOW, true));
  }

  public void success(String text) {
    println(paint("  [OK] ", AttributedStyle.GREEN, true) + text);
  }

  public void warn(String text) {
    println(paint("  [!] ", AttributedStyle.YELLOW, true) + text);
  }

  public void error(String text) {
    println(paint("  [x] ", AttributedStyle.RED, true) + text);
  }

  public void note(String text) {
    println("  " + text);
  }

  /** 键值行：标签按显示宽度补齐，中文标签也不会错位。 */
  public void kv(String label, String value) {
    println("  " + paint(padRight(label, LABEL_WIDTH), AttributedStyle.CYAN, false)
        + ": " + (value == null ? "" : value));
  }

  /** 打印表格，表头着色，其余行纯文本。 */
  public void table(List<String> headers, List<List<String>> rows) {
    String rendered = renderTable(headers, rows);
    String[] lines = rendered.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      println(i == 0 ? paint(lines[i], AttributedStyle.CYAN, true) : lines[i]);
    }
  }

  /** 首页横幅。 */
  public void banner(String serviceName, String baseUrl) {
    println();
    println(paint("  +--------------------------------------------+", AttributedStyle.CYAN, false));
    println(paint("  |   Travel Assistant  /  控制台客户端        |", AttributedStyle.CYAN, true));
    println(paint("  +--------------------------------------------+", AttributedStyle.CYAN, false));
    kv("服务", serviceName);
    kv("地址", baseUrl);
    println("  输入 help 查看命令，Tab 补全，上下键翻历史，exit 退出。");
  }

  private String paint(String text, int foreground, boolean bold) {
    if (!color || text == null || text.isEmpty()) {
      return text;
    }
    AttributedStyle style = AttributedStyle.DEFAULT.foreground(foreground);
    if (bold) {
      style = style.bold();
    }
    return new AttributedStringBuilder().style(style).append(text)
        .toAttributedString().toAnsi();
  }

  // ---- 纯函数：排版计算，可脱离终端测试 ----

  /**
   * 字符串的终端显示宽度。中文、全角、Hangul、emoji 按 2 格，
   * 组合记号、格式字符与控制字符按 0 格。
   */
  public static int displayWidth(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    int width = 0;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      width += codePointWidth(cp);
      i += Character.charCount(cp);
    }
    return width;
  }

  private static int codePointWidth(int cp) {
    if (cp == 0 || cp < 32 || (cp >= 0x7f && cp < 0xa0)) {
      return 0;
    }
    int type = Character.getType(cp);
    if (type == Character.NON_SPACING_MARK
        || type == Character.ENCLOSING_MARK
        || type == Character.FORMAT
        || type == Character.UNASSIGNED) {
      return 0;
    }
    return isWide(cp) ? 2 : 1;
  }

  private static boolean isWide(int cp) {
    return (cp >= 0x1100 && cp <= 0x115f)
        || (cp >= 0x2e80 && cp <= 0xa4cf)
        || (cp >= 0xac00 && cp <= 0xd7a3)
        || (cp >= 0xf900 && cp <= 0xfaff)
        || (cp >= 0xfe10 && cp <= 0xfe19)
        || (cp >= 0xfe30 && cp <= 0xfe6f)
        || (cp >= 0xff00 && cp <= 0xff60)
        || (cp >= 0xffe0 && cp <= 0xffe6)
        || (cp >= 0x1f300 && cp <= 0x1faff)
        || (cp >= 0x20000 && cp <= 0x3fffd);
  }

  /** 按显示宽度右侧补空格。 */
  public static String padRight(String text, int width) {
    String value = text == null ? "" : text;
    int padding = Math.max(0, width - displayWidth(value));
    return value + " ".repeat(padding);
  }

  /** 按显示宽度截断，末尾补一个省略号；返回串宽度不超过 maxWidth。 */
  public static String truncateToWidth(String text, int maxWidth) {
    if (text == null || text.isEmpty() || maxWidth <= 0) {
      return "";
    }
    if (displayWidth(text) <= maxWidth) {
      return text;
    }
    if (maxWidth == 1) {
      return ".";
    }
    StringBuilder sb = new StringBuilder();
    int width = 0;
    int limit = maxWidth - 1;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      int cpWidth = codePointWidth(cp);
      if (width + cpWidth > limit) {
        break;
      }
      sb.appendCodePoint(cp);
      width += cpWidth;
      i += Character.charCount(cp);
    }
    return sb.append('…').toString();
  }

  /** 生成纯文本表格（不含 ANSI），列宽按显示宽度计算并受 {@link #MAX_COLUMN_WIDTH} 限制。 */
  public static String renderTable(List<String> headers, List<List<String>> rows) {
    int columns = headers.size();
    if (columns == 0) {
      return "";
    }
    int[] widths = new int[columns];
    for (int c = 0; c < columns; c++) {
      widths[c] = Math.min(MAX_COLUMN_WIDTH, displayWidth(headers.get(c)));
    }
    for (List<String> row : rows) {
      for (int c = 0; c < columns; c++) {
        String cell = c < row.size() ? row.get(c) : "";
        widths[c] = Math.min(MAX_COLUMN_WIDTH,
            Math.max(widths[c], displayWidth(cell)));
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append(renderRow(headers, widths)).append('\n');
    int total = 0;
    for (int width : widths) {
      total += width;
    }
    total += GAP.length() * (columns - 1);
    sb.append("-".repeat(Math.max(1, total))).append('\n');
    for (List<String> row : rows) {
      List<String> cells = new ArrayList<>();
      for (int c = 0; c < columns; c++) {
        cells.add(c < row.size() ? row.get(c) : "");
      }
      sb.append(renderRow(cells, widths)).append('\n');
    }
    // 只去掉最后一个换行；不能用 stripTrailing()，它会把末行的列填充空格一并删掉，
    // 导致最后一行比其他行窄、右侧无法对齐。
    String rendered = sb.toString();
    return rendered.endsWith("\n")
        ? rendered.substring(0, rendered.length() - 1)
        : rendered;
  }

  private static String renderRow(List<String> cells, int[] widths) {
    StringBuilder sb = new StringBuilder();
    for (int c = 0; c < widths.length; c++) {
      if (c > 0) {
        sb.append(GAP);
      }
      String cell = c < cells.size() ? cells.get(c) : "";
      sb.append(padRight(truncateToWidth(cell, widths[c]), widths[c]));
    }
    return sb.toString();
  }

  @Override
  public void close() {
    if (terminal != null) {
      try {
        terminal.close();
      } catch (IOException ignored) {
        // 关闭终端失败不影响已经完成的输出。
      }
    }
  }
}
