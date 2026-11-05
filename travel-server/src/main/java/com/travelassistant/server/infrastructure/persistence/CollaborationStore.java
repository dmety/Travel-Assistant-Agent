package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.domain.collaboration.Comment;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 协作数据仓库：留言写 {@code comments.json}，修改记录写 {@code changelog.json}。
 *
 * <p>与账号、行程、邀请一致：读取走内存副本，写入先落临时文件再原子替换。
 * 两者都不放进行程聚合——留言和修改记录不属于方案内容，放进去会让每次评论都推动
 * 行程版本号，引发无意义的版本冲突。
 *
 * <p>修改记录按行程保留最近 {@value #MAX_CHANGES_PER_ITINERARY} 条：这是可追溯的
 * 操作流水，不是审计底账，无上限增长会让单文件无限膨胀。行程删除时整份清零。
 */
@Repository
public class CollaborationStore {
  private static final Logger log = LoggerFactory.getLogger(CollaborationStore.class);

  /** 单个行程保留的修改记录上限（超出后丢弃最旧的）。 */
  public static final int MAX_CHANGES_PER_ITINERARY = 500;

  private final ObjectMapper mapper;
  private final Path commentFile;
  private final Path changeFile;
  private volatile List<Comment> comments = new ArrayList<>();
  private volatile List<ChangeEntry> changes = new ArrayList<>();

  public CollaborationStore(ObjectMapper mapper,
      @Value("${travel.data-directory}") String dataDirectory) {
    this.mapper = mapper;
    Path dir = Paths.get(dataDirectory).toAbsolutePath();
    this.commentFile = dir.resolve("comments.json");
    this.changeFile = dir.resolve("changelog.json");
  }

  @PostConstruct
  public synchronized void init() throws IOException {
    Path dir = commentFile.getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    comments = new ArrayList<>(read(commentFile, Comment[].class, "留言"));
    changes = new ArrayList<>(read(changeFile, ChangeEntry[].class, "修改记录"));
  }

  private <T> List<T> read(Path file, Class<T[]> type, String label) throws IOException {
    if (!Files.exists(file)) {
      write(file, List.of(), label);
      log.info("{}文件不存在，已创建空文件 {}", label, file);
      return List.of();
    }
    T[] loaded = mapper.readValue(file.toFile(), type);
    log.info("已从 {} 加载 {} 条{}", file, loaded.length, label);
    return List.of(loaded);
  }

  // ---- 留言 ----

  /** 某行程的全部留言，按时间升序（讨论按发生顺序读才连贯）。 */
  public List<Comment> findComments(String itineraryId) {
    return comments.stream()
        .filter(c -> c.itineraryId().equals(itineraryId))
        .sorted(Comparator.comparing(Comment::createdAt))
        .toList();
  }

  public synchronized Comment addComment(Comment comment) {
    List<Comment> updated = new ArrayList<>(comments);
    updated.add(comment);
    comments = updated;
    write(commentFile, comments, "留言");
    return comment;
  }

  // ---- 修改记录 ----

  /**
   * 某行程的修改记录，按时间倒序（最近一次在最前，符合「谁刚改了什么」的查看习惯）。
   *
   * @param limit 最多返回条数，{@code <= 0} 时取默认 50
   */
  public List<ChangeEntry> findChanges(String itineraryId, int limit) {
    int size = limit <= 0 ? 50 : limit;
    return changes.stream()
        .filter(c -> c.itineraryId().equals(itineraryId))
        .sorted(Comparator.comparing(ChangeEntry::at).reversed())
        .limit(size)
        .toList();
  }

  /** 追加一条修改记录；同一行程超过上限时丢弃最旧的若干条。 */
  public synchronized void addChange(ChangeEntry entry) {
    List<ChangeEntry> updated = new ArrayList<>(changes);
    updated.add(entry);

    List<ChangeEntry> sameItinerary = updated.stream()
        .filter(c -> c.itineraryId().equals(entry.itineraryId()))
        .sorted(Comparator.comparing(ChangeEntry::at))
        .toList();
    if (sameItinerary.size() > MAX_CHANGES_PER_ITINERARY) {
      List<String> drop = sameItinerary.subList(0,
          sameItinerary.size() - MAX_CHANGES_PER_ITINERARY).stream().map(ChangeEntry::id).toList();
      updated.removeIf(c -> drop.contains(c.id()));
    }

    changes = updated;
    write(changeFile, changes, "修改记录");
  }

  /** 行程删除后清掉它的留言与修改记录，避免留下指向已不存在行程的孤儿数据。 */
  public synchronized void purge(String itineraryId) {
    boolean commentRemoved = comments.removeIf(c -> c.itineraryId().equals(itineraryId));
    boolean changeRemoved = changes.removeIf(c -> c.itineraryId().equals(itineraryId));
    if (commentRemoved) {
      write(commentFile, comments, "留言");
    }
    if (changeRemoved) {
      write(changeFile, changes, "修改记录");
    }
  }

  private void write(Path file, List<?> data, String label) {
    try {
      Path dir = file.getParent();
      Path tmp = Files.createTempFile(dir, file.getFileName().toString(), ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new IllegalStateException("无法写入" + label + "文件: " + file, e);
    }
  }
}
