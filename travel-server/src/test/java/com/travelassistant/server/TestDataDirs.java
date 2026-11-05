package com.travelassistant.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 测试数据目录：统一放在模块的 {@code target/test-data} 下，JVM 退出时整份删除。
 *
 * <p>此前的做法是 {@code Files.createTempDirectory("travel-api-test-")}，落在系统临时目录
 * （Windows 上是 C 盘 {@code %TEMP%}），而且 JVM 退出不回收——每跑一次测试就留一份数据。
 * 实测单机已累积 17 个目录 / 1.2 MB，既占空间，也违反「构建产物归 target/」的约定。
 *
 * <p>目录带进程号与随机后缀，保证不同次运行、不同 JVM（{@code reuseForks=false}）之间互不干扰；
 * 即使清理钩子没能执行，{@code mvn clean} 也能一并清掉，不会外溢到用户目录。
 */
public final class TestDataDirs {

  private static final Path ROOT = Path.of("target", "test-data").toAbsolutePath();

  private static final Path RUN =
      ROOT.resolve(ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime()));

  private static boolean cleanupRegistered;

  private TestDataDirs() {
  }

  /**
   * 取本次运行下的一个命名子目录，不存在则创建。
   *
   * <p>不同测试基类各用一个名字，避免共享同一份 {@code itineraries.json} 互相污染；
   * 同时目录不同会让 Spring 的上下文缓存分开，这一点与改造前保持一致。
   */
  public static synchronized Path sub(String name) {
    Path dir = RUN.resolve(name);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("无法创建测试数据目录: " + dir, e);
    }
    registerCleanup();
    return dir;
  }

  private static void registerCleanup() {
    if (cleanupRegistered) {
      return;
    }
    cleanupRegistered = true;
    Runtime.getRuntime().addShutdownHook(new Thread(TestDataDirs::deleteRunDir,
        "test-data-cleanup"));
  }

  /** 删除本次运行目录；尽力而为，删不掉不影响测试结果。 */
  static void deleteRunDir() {
    if (!Files.exists(RUN)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(RUN)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> {
        try {
          Files.deleteIfExists(path);
        } catch (IOException ignored) {
          // 被占用或只读：留给 mvn clean
        }
      });
    } catch (IOException ignored) {
      // 目录遍历失败同样不致命
    }
  }
}
