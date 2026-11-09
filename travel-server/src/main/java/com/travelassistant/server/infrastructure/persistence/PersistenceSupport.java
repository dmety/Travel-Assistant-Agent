package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON 文件原子写：账号、行程、邀请、协作、决策五个仓库共用这一份实现。
 *
 * <p>先写同目录临时文件再 move 替换，读者不会看到半份 JSON。**无论序列化还是 move
 * 失败，finally 都会删除临时文件**，避免在 {@code data/} 留下垃圾。
 *
 * <p>Windows 上目标文件被编辑器或杀软占用时抛的是 {@link FileSystemException}
 * （既不是 {@link AtomicMoveNotSupportedException}，也不该被泛化成一个无原因的
 * IOException），这里单独包装以保留「文件可能被占用」这个可操作线索。
 */
final class PersistenceSupport {

  private PersistenceSupport() {
  }

  /**
   * @param file  目标 JSON 文件
   * @param mapper 统一的对象映射器
   * @param data  要写入的快照
   * @param label 报错用的中文名称（如「账号」「行程」）
   */
  static void writeAtomically(Path file, ObjectMapper mapper, Object data, String label) {
    Path dir = file.getParent();
    Path tmp = null;
    try {
      String prefix = file.getFileName() == null ? "data" : file.getFileName().toString();
      tmp = Files.createTempFile(dir == null ? Path.of(".") : dir, prefix, ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (FileSystemException e) {
      throw new IllegalStateException(
          "无法写入" + label + "文件（目标可能被其它程序占用）：" + file, e);
    } catch (IOException e) {
      throw new IllegalStateException("无法写入" + label + "文件: " + file, e);
    } finally {
      if (tmp != null) {
        try {
          // move 成功后 tmp 已不存在，deleteIfExists 是空操作；失败时清理现场。
          Files.deleteIfExists(tmp);
        } catch (IOException | RuntimeException ignored) {
          // 清理失败不能掩盖原始异常。
        }
      }
    }
  }
}
