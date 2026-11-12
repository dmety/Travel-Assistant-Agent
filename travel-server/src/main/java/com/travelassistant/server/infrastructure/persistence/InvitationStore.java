package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.invitation.Invitation;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 服务端邀请仓库：所有邀请落盘到 {@code <data-directory>/invitations.json}。
 *
 * <p>与账号、行程一致：读取走内存副本，写入先落临时文件再原子替换。
 * 需求 §4 明确要求「账号、邀请和方案等数据保存为本地文本文件」，邀请不放在行程里，
 * 因此单独成库。
 */
@Repository
public class InvitationStore {
  private static final Logger log = LoggerFactory.getLogger(InvitationStore.class);

  private final ObjectMapper mapper;
  private final Path file;
  private volatile List<Invitation> invitations = new ArrayList<>();

  public InvitationStore(ObjectMapper mapper,
      @Value("${travel.data-directory}") String dataDirectory) {
    this.mapper = mapper;
    this.file = Paths.get(dataDirectory, "invitations.json").toAbsolutePath();
  }

  @PostConstruct
  public void init() throws IOException {
    Path dir = file.getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    if (Files.exists(file)) {
      Invitation[] loaded = mapper.readValue(file.toFile(), Invitation[].class);
      invitations = new ArrayList<>(List.of(loaded));
      log.info("已从 {} 加载 {} 条邀请", file, invitations.size());
    } else {
      write();
      log.info("邀请文件不存在，已创建空文件 {}", file);
    }
  }

  public List<Invitation> findAll() {
    return List.copyOf(invitations);
  }

  public Optional<Invitation> findById(String id) {
    return invitations.stream().filter(i -> i.id().equals(id)).findFirst();
  }

  /** 某行程的全部邀请（含已处理），供创建者查看邀请记录。 */
  public List<Invitation> findByItinerary(String itineraryId) {
    return invitations.stream().filter(i -> i.itineraryId().equals(itineraryId)).toList();
  }

  /** 新增或按 id 覆盖一条邀请。 */
  public synchronized Invitation save(Invitation invitation) {
    List<Invitation> updated = new ArrayList<>(invitations);
    updated.removeIf(i -> i.id().equals(invitation.id()));
    updated.add(invitation);
    invitations = updated;
    write();
    return invitation;
  }

  /** 批量写入（如行程删除时一次性把该行程的待处理邀请置为已取消）。 */
  public synchronized void saveAll(List<Invitation> changed) {
    if (changed == null || changed.isEmpty()) {
      return;
    }
    List<Invitation> updated = new ArrayList<>(invitations);
    for (Invitation invitation : changed) {
      updated.removeIf(i -> i.id().equals(invitation.id()));
      updated.add(invitation);
    }
    invitations = updated;
    write();
  }

  private void write() {
    PersistenceSupport.writeAtomically(file, mapper, invitations, "邀请");
  }
}
