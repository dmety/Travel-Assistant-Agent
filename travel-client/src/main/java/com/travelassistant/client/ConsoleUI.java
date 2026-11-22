package com.travelassistant.client;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.keymap.KeyMap;
import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
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
  private final java.io.Console nativeConsole;
  private final PrintWriter output;
  private final boolean color;
  private final List<String> suggestions = new ArrayList<>();
  private final List<String> transcript = new ArrayList<>();
  private final StringBuilder partialLine = new StringBuilder();
  private final List<String> pendingImages = new ArrayList<>();
  private boolean fullscreen;
  private List<String> commandMenu = List.of();
  private int commandChoice;
  private String commandPrefix = "";
  private long lastRenderNanos;

  private ConsoleUI(boolean interactive, Collection<String> initialSuggestions) {
    suggestions.addAll(initialSuggestions);
    Terminal builtTerminal = null;
    LineReader builtReader = null;
    if (interactive) {
      try {
        builtTerminal = TerminalBuilder.builder().system(true)
            .encoding(StandardCharsets.UTF_8).build();
        if (!builtTerminal.getType().startsWith("dumb")) {
          var editor = new org.jline.reader.impl.LineReaderImpl(builtTerminal) {
            @Override protected void redisplay(boolean flush) {
              if (!commandMenu.isEmpty() && !getBuffer().toString().equals(commandPrefix)) {
                commandMenu=List.of(); renderScreen(); display.reset();
              }
              // The editor owns one bottom row, not the entire transcript screen.
              // JLine's small-height mode scrolls long input horizontally.
              int rows = size.getRows();
              int columns = size.getColumns();
              try {
                if (fullscreen) {
                  size.setRows(1);
                  size.setColumns(Math.max(10, getTerminal().getWidth() - 1));
                }
                super.redisplay(flush);
              } finally {
                size.setRows(rows);
                size.setColumns(columns);
              }
            }
          };
          editor.setParser(new DefaultParser());
          editor.setCompleter(this::complete);
          editor.setHistory(new DefaultHistory());
          editor.setOpt(LineReader.Option.AUTO_MENU);
          builtReader = editor;
        }
        if (builtReader != null) {
          LineReader configuredReader = builtReader;
          builtReader.getWidgets().put("travel-command-menu", this::commandMenu);
          builtReader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("travel-command-menu"), "\t");
          builtReader.getWidgets().put("travel-accept", () -> {
            if (!commandMenu.isEmpty()) {
              configuredReader.getBuffer().clear(); configuredReader.getBuffer().write(commandMenu.get(commandChoice));
              commandMenu = List.of(); renderScreen(); configuredReader.callWidget(LineReader.REDRAW_LINE);
              return true;
            }
            configuredReader.callWidget(LineReader.ACCEPT_LINE); return true;
          });
          builtReader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("travel-accept"), "\r", "\n");
          builtReader.getWidgets().put("travel-cancel-menu", () -> {
            commandMenu=List.of(); renderScreen(); configuredReader.callWidget(LineReader.REDRAW_LINE); return true;
          });
          builtReader.getKeyMaps().get(LineReader.MAIN).bind(new Reference("travel-cancel-menu"), "\u0007");
          builtReader.getKeyMaps().get(LineReader.MAIN).bind(
              new Reference(LineReader.HISTORY_INCREMENTAL_SEARCH_BACKWARD), "\u0012");
          builtReader.getWidgets().put("travel-paste-image", () -> pasteClipboardImageIntoPrompt());
          builtReader.getKeyMaps().get(LineReader.MAIN).bind(
              new Reference("travel-paste-image"), KeyMap.alt('v'));
        }
      } catch (IOException | RuntimeException e) {
        // 没有真实终端时退回纯文本，不因为终端能力探测失败而让整个客户端不可用。
        builtTerminal = null;
        builtReader = null;
      }
    }
    this.terminal = builtTerminal;
    this.reader = builtReader;
    // A dumb terminal cannot disable OS echo. Use one native console reader for
    // commands AND passwords; mixing it with JLine's input thread loses input.
    this.nativeConsole = interactive && builtReader == null ? System.console() : null;
    this.output = nativeConsole != null ? nativeConsole.writer() : builtTerminal == null
        ? new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true)
        : builtTerminal.writer();
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
    return reader != null || nativeConsole != null;
  }

  public boolean isColorEnabled() {
    return color;
  }

  public boolean supportsLineEditing() {
    return reader != null;
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
    java.util.Set<String> emitted = new java.util.LinkedHashSet<>();
    for (String value : suggestions) {
      String match = word.startsWith("/") ? "/" + value.replaceFirst("^/", "") : value;
      if (!match.isEmpty() && match.startsWith(word) && !match.equals(word) && emitted.add(match)) {
        candidates.add(new Candidate(match, match, null, null, null, null, true));
      }
    }
  }

  /** 读取一行（支持行编辑、上下键历史、Tab 补全）。无终端时返回 null。 */
  public String readLine(String prompt) {
    if (fullscreen) renderScreen();
    String safePrompt = sanitize(prompt);
    return nativeConsole != null ? nativeConsole.readLine("%s", safePrompt)
        : reader == null ? null : reader.readLine(safePrompt);
  }

  /** Enter an alternate-screen transcript for a fixed footer and line-editor prompt. */
  public boolean enterFullscreen() {
    if (terminal == null || reader == null || "dumb".equalsIgnoreCase(terminal.getType())) return false;
    try {
      fullscreen = terminal.puts(org.jline.utils.InfoCmp.Capability.enter_ca_mode);
      if (!fullscreen) return false;
      terminal.writer().flush();
      renderScreen();
      return true;
    } catch (RuntimeException ignored) {
      fullscreen = false;
      return false;
    }
  }

  public boolean isFullscreen() { return fullscreen; }

  private boolean commandMenu() {
    String prefix = reader.getBuffer().toString();
    if (!prefix.startsWith("/") || prefix.contains(" ")) {
      reader.callWidget(LineReader.COMPLETE_WORD); return true;
    }
    if (commandMenu.isEmpty()) {
      commandPrefix = prefix;
      commandMenu = suggestions.stream().map(s -> "/" + s.replaceFirst("^/", ""))
          .distinct().filter(s -> s.startsWith(prefix)).sorted().toList();
      commandChoice = 0;
    } else commandChoice = (commandChoice + 1) % commandMenu.size();
    if (commandMenu.isEmpty()) return true;
    int width = Math.max(20, terminal.getWidth()) - 1;
    int rows = Math.min(6, Math.max(1, terminal.getHeight() - 4));
    int start = Math.max(0, commandChoice - rows + 1);
    var writer = terminal.writer(); writer.print("\u001b7");
    for (int n=0;n<rows;n++) {
      int i=start+n;
      String label=i<commandMenu.size()?(i==commandChoice?" > ":"   ")+commandMenu.get(i):"";
      writer.print("\u001b["+(terminal.getHeight()-rows-2+n)+";1H\u001b[2K"+truncateToWidth(label,width));
    }
    writer.print("\u001b["+(terminal.getHeight()-2)+";1H\u001b[2K"+truncateToWidth("Tab 切换 · Enter 填入命令，再按 Enter 执行",width)+"\u001b8");
    writer.flush(); return true;
  }

  private void append(String text, boolean newline) {
    String value = sanitize(text);
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '\r') continue;
      if (ch == '\n') {
        transcript.add(partialLine.toString());
        partialLine.setLength(0);
      } else partialLine.append(ch);
    }
    if (newline) {
      transcript.add(partialLine.toString());
      partialLine.setLength(0);
    }
    if (transcript.size() > 10_000) transcript.subList(0, transcript.size() - 10_000).clear();
    if (fullscreen) renderScreenThrottled();
  }

  private void renderScreenThrottled() {
    long now = System.nanoTime();
    if (now - lastRenderNanos > 45_000_000L) renderScreen();
  }

  private void renderScreen() {
    if (!fullscreen) return;
    int width = Math.max(20, terminal.getWidth()) - 1;
    int height = Math.max(8, terminal.getHeight());
    int contentRows = Math.max(1, height - 4);
    List<String> lines = new ArrayList<>();
    for (String line : transcript) lines.addAll(wrap(line, width));
    if (!partialLine.isEmpty()) lines.addAll(wrap(partialLine.toString(), width));
    int start = Math.max(0, lines.size() - contentRows);
    PrintWriter writer = terminal.writer();
    writer.print("\u001b[H\u001b[2J");
    writer.print(ConsoleUI.truncateToWidth("旅行助手  ·  " + terminal.getName(), width));
    writer.print("\r\n");
    for (int i = 0; i < contentRows; i++) {
      String line = start + i < lines.size() ? lines.get(start + i) : "";
      writer.print(ConsoleUI.truncateToWidth(line, width));
      writer.print("\u001b[K\r\n");
    }
    writer.print("\u001b[2m" + "─".repeat(width) + "\u001b[0m\u001b[K\r\n");
    writer.print(ConsoleUI.truncateToWidth("Enter 发送  ·  / 命令菜单  ·  Ctrl+R 搜索历史  ·  Alt+V 图片  ·  ↑↓ 历史", width - 1) + "\u001b[K");
    writer.print("\u001b[" + height + ";1H");
    writer.flush();
    lastRenderNanos = System.nanoTime();
  }

  private static List<String> wrap(String text, int maxWidth) {
    text = sanitize(text);
    if (text.isEmpty()) return List.of("");
    List<String> lines = new ArrayList<>();
    StringBuilder line = new StringBuilder();
    int used = 0;
    for (int i = 0; i < text.length();) {
      int cp = text.codePointAt(i);
      int cells = codePointWidth(cp);
      if (used + cells > maxWidth && used > 0) {
        lines.add(line.toString()); line.setLength(0); used = 0;
      }
      line.appendCodePoint(cp); used += cells; i += Character.charCount(cp);
    }
    lines.add(line.toString());
    return lines;
  }

  private boolean pasteClipboardImageIntoPrompt() {
    try {
      var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      var contents = clipboard.getContents(null);
      if (contents == null || !contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
        warn("剪贴板里没有图片。请复制图片后按 Alt+V。Windows Terminal 的 Ctrl+V 仍用于粘贴文字。");
        return true;
      }
      Image image = (Image) contents.getTransferData(DataFlavor.imageFlavor);
      String dataUrl = encodeClipboardImage(image);
      if (dataUrl == null) {
        warn("读取剪贴板图片失败；支持 PNG、JPEG、GIF、WebP 位图剪贴板。");
        return true;
      }
      pendingImages.add(dataUrl);
      reader.getBuffer().write("[图片" + pendingImages.size() + "已附加]");
      return true;
    } catch (Exception e) {
      warn("无法读取图片剪贴板：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      return true;
    }
  }

  static String encodeClipboardImage(Image image) throws IOException {
    if (image == null) return null;
    int sourceWidth = image.getWidth(null), sourceHeight = image.getHeight(null);
    if (sourceWidth < 1 || sourceHeight < 1) return null;
    double ratio = Math.min(1.0, 2048.0 / Math.max(sourceWidth, sourceHeight));
    int width = Math.max(1, (int) Math.round(sourceWidth * ratio));
    int height = Math.max(1, (int) Math.round(sourceHeight * ratio));
    BufferedImage converted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = converted.createGraphics();
    try {
      graphics.setColor(java.awt.Color.WHITE); graphics.fillRect(0, 0, width, height);
      graphics.drawImage(image, 0, 0, width, height, null);
    } finally { graphics.dispose(); }
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    if (!ImageIO.write(converted, "jpg", bytes) || bytes.size() > 2 * 1024 * 1024) return null;
    return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bytes.toByteArray());
  }

  public List<String> takePendingImages() {
    List<String> images = List.copyOf(pendingImages);
    pendingImages.clear();
    return images;
  }

  /** Show long output in a scrollable full-screen viewer; q or Esc returns to the prompt. */
  public void viewText(String title, String text) {
    if (!fullscreen || terminal == null || reader == null) {
      println(title);
      println(text);
      return;
    }
    int width = Math.max(20, terminal.getWidth());
    int height = Math.max(8, terminal.getHeight());
    int pageSize = Math.max(1, height - 3);
    List<String> lines = new ArrayList<>();
    for (String line : (text == null ? "" : text).split("\\R", -1)) lines.addAll(wrap(line, width));
    int top = 0;
    Attributes previous = terminal.enterRawMode();
    try {
      terminal.echo(false);
      boolean done = false;
      while (!done) {
        PrintWriter writer = terminal.writer();
        writer.print("\u001b[H\u001b[2J");
        writer.print(ConsoleUI.truncateToWidth(title, width) + "\r\n");
        for (int row = 0; row < pageSize; row++) {
          String line = top + row < lines.size() ? lines.get(top + row) : "";
          writer.print(line + "\u001b[K\r\n");
        }
        writer.print("\u001b[2m↑↓/PgUp/PgDn 翻页  ·  Home/End 跳转  ·  q/Esc 返回  ·  "
            + Math.min(lines.size(), top + pageSize) + "/" + lines.size() + "\u001b[0m\u001b[K");
        writer.flush();
        String key = readViewerKey(terminal);
        if (key.isEmpty() || "q".equalsIgnoreCase(key) || "\u001b".equals(key)) {
          done = true;
        } else if ("\r".equals(key) || "\n".equals(key) || " ".equals(key) || "\u001b[6~".equals(key)) top = Math.min(Math.max(0, lines.size() - pageSize), top + pageSize);
        else if ("\u0010".equals(key) || "\u001b[A".equals(key)) top = Math.max(0, top - 1);
        else if ("\u000e".equals(key) || "\u001b[B".equals(key)) top = Math.min(Math.max(0, lines.size() - pageSize), top + 1);
        else if ("\u0002".equals(key) || "\u001b[5~".equals(key)) top = Math.max(0, top - pageSize);
        else if ("\u0006".equals(key)) top = Math.min(Math.max(0, lines.size() - pageSize), top + pageSize);
        else if ("\u0001".equals(key) || "\u001b[H".equals(key)) top = 0;
        else if ("\u0005".equals(key) || "\u001b[F".equals(key)) top = Math.max(0, lines.size() - pageSize);
      }
    } catch (IOException e) {
      // Keep the primary CLI session usable even if terminal paging fails.
    } finally {
      terminal.setAttributes(previous);
      renderScreen();
    }
  }

  private static String readViewerKey(Terminal terminal) throws IOException {
    int first = terminal.reader().read();
    if (first < 0) return "";
    if (first != 27) return Character.toString((char) first);
    if (terminal.reader().peek(35) < 0) return "\u001b";
    StringBuilder sequence = new StringBuilder("\u001b");
    sequence.append((char) terminal.reader().read());
    if (sequence.charAt(1) == '[') {
      while (terminal.reader().peek(35) >= 0 && sequence.length() < 5) {
        int next = terminal.reader().read(); sequence.append((char) next);
        if (next >= 0x40 && next <= 0x7e) break;
      }
    }
    return sequence.toString();
  }

  /** 读取敏感输入，回显掩码。无终端时返回 null。 */
  public String readMasked(String prompt) {
    if (nativeConsole != null) {
      char[] password = nativeConsole.readPassword("%s", sanitize(prompt));
      if (password == null) return null;
      try { return new String(password); }
      finally { java.util.Arrays.fill(password, '\0'); }
    }
    return reader == null ? null : readMasked(terminal, output, sanitize(prompt));
  }

  // Use the same terminal input, without a second line-editor display/history lifecycle.
  // In Windows pseudo terminals a masked JLine read can keep repainting its old prompt
  // after returning, corrupting later prompts and even exposing echoed input.
  static String readMasked(Terminal terminal, PrintWriter output, String prompt) {
    Attributes previous = terminal.enterRawMode();
    StringBuilder value = new StringBuilder();
    try {
      terminal.echo(false);
      output.print(sanitize(prompt));
      output.flush();
      int escape = 0;
      while (true) {
        int ch = terminal.reader().read();
        if (ch < 0 || ch == 4) return null;
        if (ch == 3) throw new UserInterruptException("");
        if (escape != 0) {
          if (escape == 1 && (ch == '[' || ch == 'O')) escape = 2;
          else if (escape == 1 || ch >= 0x40 && ch <= 0x7e) escape = 0;
          continue;
        }
        if (ch == 27) { escape = 1; continue; }
        if (ch == '\r' || ch == '\n') return value.toString();
        if (ch == 8 || ch == 127) {
          if (!value.isEmpty()) {
            value.setLength(value.offsetByCodePoints(value.length(), -1));
            output.print("\b \b");
          }
        } else if (ch >= 32) {
          value.append((char) ch);
          if (!Character.isLowSurrogate((char) ch)) output.print('*');
        }
        output.flush();
      }
    } catch (IOException e) {
      throw new IllegalStateException("密码输入失败，请重新登录", e);
    } finally {
      terminal.setAttributes(previous);
      output.println();
      output.flush();
    }
  }

  // ---- 输出 ----

  public void println() {
    if (fullscreen) { append("", true); return; }
    PrintWriter writer = currentOutput();
    writer.println();
    writer.flush();
  }

  public void println(String text) {
    String safe = sanitize(text);
    if (fullscreen) { append(safe, true); return; }
    PrintWriter writer = currentOutput();
    writer.println(decorate(safe));
    writer.flush();
  }

  public void print(String text) {
    String safe = sanitize(text);
    if (fullscreen) { append(safe, false); return; }
    PrintWriter writer = currentOutput();
    writer.print(safe);
    writer.flush();
  }

  /** Strip terminal control sequences and unsafe controls from display text. */
  public static String sanitize(String text) {
    if (text == null || text.isEmpty()) return "";
    StringBuilder safe = new StringBuilder(text.length());
    for (int i = 0; i < text.length();) {
      char ch = text.charAt(i);
      if (ch == '\u001b') {
        if (i + 1 >= text.length()) { i++; continue; }
        char introducer = text.charAt(i + 1);
        if (introducer == '[') i = skipCsi(text, i + 2);
        else if (introducer == ']' || introducer == 'P' || introducer == '^'
            || introducer == '_' || introducer == 'X') i = skipStringControl(text, i + 2);
        else {
          int end = i + 1;
          while (end < text.length() && text.charAt(end) >= 0x20
              && text.charAt(end) <= 0x2f) end++;
          i = end < text.length() && text.charAt(end) >= 0x30
              && text.charAt(end) <= 0x7e ? end + 1 : end;
        }
        continue;
      }
      if (ch == '\u009b') { i = skipCsi(text, i + 1); continue; }
      if (ch == '\u009d' || ch == '\u0090' || ch == '\u0098'
          || ch == '\u009e' || ch == '\u009f') {
        i = skipStringControl(text, i + 1);
        continue;
      }
      int codePoint = text.codePointAt(i);
      if (codePoint == '\n' || codePoint == '\t') safe.appendCodePoint(codePoint);
      else if (codePoint != '\r' && codePoint >= 0x20 && codePoint != 0x7f
          && !(codePoint >= 0x80 && codePoint <= 0x9f)) safe.appendCodePoint(codePoint);
      i += Character.charCount(codePoint);
    }
    return safe.toString();
  }

  private static int skipCsi(String text, int start) {
    int i = start;
    while (i < text.length()) {
      char ch = text.charAt(i++);
      if (ch >= 0x40 && ch <= 0x7e) break;
    }
    return i;
  }

  private static int skipStringControl(String text, int start) {
    int i = start;
    while (i < text.length()) {
      char ch = text.charAt(i++);
      if (ch == '\u0007' || ch == '\u009c') return i;
      if (ch == '\u001b' && i < text.length() && text.charAt(i) == '\\') return i + 1;
    }
    return i;
  }

  /**
   * 给既有输出做最小装饰：只加 ANSI，不改任何字符。
   * 这样 TravelClient 原有的上百处 println 无需逐行改写即可统一观感，
   * 且纯文本模式下 {@code decorate} 原样返回，脚本输出逐字不变。
   */
  private String decorate(String text) {
    if (!color || fullscreen || text == null || text.isEmpty()) {
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
    String safe = sanitize(text);
    printlnStyled(paint("  " + safe, AttributedStyle.CYAN, true));
    println("  " + "-".repeat(Math.min(64, Math.max(24, displayWidth(safe) + 6))));
  }

  /** 二级小节标题。 */
  public void section(String text) {
    printlnStyled(paint("  " + sanitize(text), AttributedStyle.YELLOW, true));
  }

  public void success(String text) {
    printlnStyled(paint("  [OK] ", AttributedStyle.GREEN, true) + sanitize(text));
  }

  public void warn(String text) {
    printlnStyled(paint("  [!] ", AttributedStyle.YELLOW, true) + sanitize(text));
  }

  public void error(String text) {
    printlnStyled(paint("  [x] ", AttributedStyle.RED, true) + sanitize(text));
  }

  public void note(String text) {
    println("  " + sanitize(text));
  }

  /** 键值行：标签按显示宽度补齐，中文标签也不会错位。 */
  public void kv(String label, String value) {
    printlnStyled("  " + paint(padRight(sanitize(label), LABEL_WIDTH), AttributedStyle.CYAN, false)
        + ": " + sanitize(value));
  }

  /** 打印表格，表头着色，其余行纯文本。 */
  public void table(List<String> headers, List<List<String>> rows) {
    String rendered = renderTable(headers, rows);
    String[] lines = rendered.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (i == 0) printlnStyled(paint(lines[i], AttributedStyle.CYAN, true));
      else println(lines[i]);
    }
  }

  /** 首页横幅。 */
  public void banner(String serviceName, String baseUrl) {
    println();
    printlnStyled(paint("  +--------------------------------------------+", AttributedStyle.CYAN, false));
    printlnStyled(paint("  |   Travel Assistant  /  控制台客户端        |", AttributedStyle.CYAN, true));
    printlnStyled(paint("  +--------------------------------------------+", AttributedStyle.CYAN, false));
    kv("服务", serviceName);
    kv("地址", baseUrl);
    println(reader != null ? "  输入 / 浏览命令，Tab 补全，Ctrl+R 搜索历史，Alt+V 粘贴图片；/details 展开攻略。"
        : "  输入 help 查看命令，exit 退出；密码输入不回显。");
  }

  private String paint(String text, int foreground, boolean bold) {
    String safe = sanitize(text);
    if (!color || fullscreen || safe.isEmpty()) {
      return safe;
    }
    AttributedStyle style = AttributedStyle.DEFAULT.foreground(foreground);
    if (bold) {
      style = style.bold();
    }
    return new AttributedStringBuilder().style(style).append(safe)
        .toAttributedString().toAnsi();
  }

  private void printlnStyled(String text) {
    if (fullscreen) {
      append(text, true);
      return;
    }
    PrintWriter writer = currentOutput();
    writer.println(text == null ? "" : text);
    writer.flush();
  }

  private PrintWriter currentOutput() {
    if (terminal == null && nativeConsole == null) {
      return new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
    }
    return output;
  }

  // ---- 纯函数：排版计算，可脱离终端测试 ----

  /**
   * 字符串的终端显示宽度。中文、全角、Hangul、emoji 按 2 格，
   * 组合记号、格式字符与控制字符按 0 格。
   */
  public static int displayWidth(String text) {
    text = sanitize(text);
    if (text.isEmpty()) {
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
    String value = sanitize(text);
    int padding = Math.max(0, width - displayWidth(value));
    return value + " ".repeat(padding);
  }

  /** 按显示宽度截断，末尾补一个省略号；返回串宽度不超过 maxWidth。 */
  public static String truncateToWidth(String text, int maxWidth) {
    text = sanitize(text);
    if (text.isEmpty() || maxWidth <= 0) {
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
    headers = headers.stream().map(ConsoleUI::sanitize).toList();
    rows = rows.stream().map(row -> row.stream().map(ConsoleUI::sanitize).toList()).toList();
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
    if (fullscreen && terminal != null) {
      terminal.puts(org.jline.utils.InfoCmp.Capability.exit_ca_mode);
      terminal.writer().flush();
      fullscreen = false;
    }
    if (terminal != null) {
      try {
        terminal.close();
      } catch (IOException ignored) {
        // 关闭终端失败不影响已经完成的输出。
      }
    }
  }
}
