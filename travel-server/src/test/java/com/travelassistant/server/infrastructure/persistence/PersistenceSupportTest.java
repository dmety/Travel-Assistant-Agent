package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.TestDataDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON 原子写回归：验证正常写入、覆盖写入，以及失败路径不留下临时文件。
 *
 * <p>数据目录走 {@link TestDataDirs}（模块内 target/test-data），不使用 JVM 系统临时目录。
 */
@DisplayName("JSON 原子写")
class PersistenceSupportTest {

  private static final Path DIR = TestDataDirs.sub("persistence");
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** getter 抛异常，让 Jackson 在写临时文件阶段失败。 */
  static final class Unserializable {
    public String getBoom() throws IOException {
      throw new IOException("序列化故意失败");
    }
  }

  private static long tempCount() throws IOException {
    try (Stream<Path> files = Files.list(DIR)) {
      return files.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
    }
  }

  @Test
  @DisplayName("正常写入后目标完整、临时文件不残留")
  void writesSnapshotWithoutLeavingTempFiles() throws Exception {
    Path file = DIR.resolve("accounts.json");
    PersistenceSupport.writeAtomically(file, MAPPER, List.of("a", "b"), "账号");

    assertThat(Files.readString(file)).contains("\"a\"").contains("\"b\"");
    assertThat(tempCount()).isZero();
  }

  @Test
  @DisplayName("重复写入覆盖旧内容，不留临时文件")
  void repeatedWriteReplacesTarget() throws Exception {
    Path file = DIR.resolve("itineraries.json");
    PersistenceSupport.writeAtomically(file, MAPPER, List.of("old"), "行程");
    PersistenceSupport.writeAtomically(file, MAPPER, List.of("new"), "行程");

    assertThat(Files.readString(file)).contains("\"new\"").doesNotContain("\"old\"");
    assertThat(tempCount()).isZero();
  }

  @Test
  @DisplayName("序列化失败抛带名称的原因，且不留下 .tmp 垃圾")
  void failedSerializationCleansUpTempFile() throws Exception {
    Path file = DIR.resolve("broken.json");

    assertThatThrownBy(() ->
        PersistenceSupport.writeAtomically(file, MAPPER, new Unserializable(), "账号"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("账号");

    assertThat(tempCount()).isZero();
    assertThat(Files.exists(file)).isFalse();
  }
}
