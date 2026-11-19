package com.travelassistant.server.application.agent.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F6：响应体边读边计数，超限即断，绝不先完整下载再校验。 */
@DisplayName("HTTP 适配器响应体上限")
class HttpAgentAdapterBoundedReadTest {

  @Test
  @DisplayName("未超限：完整读回并解码 UTF-8")
  void readsBodyUnderLimit() throws IOException {
    InputStream in = new ByteArrayInputStream("你好，世界".getBytes(StandardCharsets.UTF_8));
    assertEquals("你好，世界", HttpAgentAdapter.readBounded(in, 64));
  }

  @Test
  @DisplayName("恰好等于上限：接受")
  void readsBodyAtLimit() throws IOException {
    byte[] data = "x".repeat(64).getBytes(StandardCharsets.UTF_8);
    assertEquals("x".repeat(64), HttpAgentAdapter.readBounded(new ByteArrayInputStream(data), 64));
  }

  @Test
  @DisplayName("超过上限：返回 null，且只拉取不超过「上限 + 一个缓冲块」的字节")
  void abortsEarlyWhenOverLimit() throws IOException {
    AtomicInteger pulled = new AtomicInteger();
    InputStream endless = new InputStream() {
      @Override
      public int read() {
        pulled.incrementAndGet();
        return 'x';
      }

      @Override
      public int read(byte[] b, int off, int len) {
        pulled.addAndGet(len);
        Arrays.fill(b, off, off + len, (byte) 'x');
        return len;
      }
    };
    assertNull(HttpAgentAdapter.readBounded(endless, 64));
    // 旧实现 ofString 会把这条「无限流」整个吞下直到 OOM；新实现读一个块超限即停。
    assertTrue(pulled.get() <= 64 + 8192,
        "应在超限后立即断流，实际拉取字节=" + pulled.get());
  }
}