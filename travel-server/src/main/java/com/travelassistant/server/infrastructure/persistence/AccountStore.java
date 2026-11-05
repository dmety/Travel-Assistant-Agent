package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 服务端账号仓库：所有账号落盘到 {@code <data-directory>/accounts.json}。
 * 读取走内存副本，写入先落临时文件再原子替换，避免并发损坏。
 */
@Repository
public class AccountStore {
  private static final Logger log = LoggerFactory.getLogger(AccountStore.class);

  private final ObjectMapper mapper;
  private final Path file;
  private volatile List<Account> accounts = new ArrayList<>();

  public AccountStore(ObjectMapper mapper, @Value("${travel.data-directory}") String dataDirectory) {
    this.mapper = mapper;
    this.file = Paths.get(dataDirectory, "accounts.json").toAbsolutePath();
  }

  @PostConstruct
  public void init() throws IOException {
    Path dir = file.getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    if (Files.exists(file)) {
      Account[] loaded = mapper.readValue(file.toFile(), Account[].class);
      accounts = new ArrayList<>(List.of(loaded));
      log.info("已从 {} 加载 {} 个账号", file, accounts.size());
    } else {
      write(accounts);
      log.info("账号文件不存在，已创建空文件 {}", file);
    }
  }

  public Optional<Account> findByUsername(String username) {
    return accounts.stream().filter(a -> a.username().equals(username)).findFirst();
  }

  public boolean exists(String username) {
    return accounts.stream().anyMatch(a -> a.username().equals(username));
  }

  /** 原子创建：同名账号不能覆盖；落盘失败不发布到内存。 */
  public synchronized boolean createIfAbsent(Account account) {
    if (exists(account.username())) {
      return false;
    }
    List<Account> updated = new ArrayList<>(accounts);
    updated.add(account);
    write(updated);
    accounts = updated;
    return true;
  }

  private void write(List<Account> snapshot) {
    try {
      Path dir = file.getParent();
      Path tmp = Files.createTempFile(dir, "accounts", ".tmp");
      mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
      try {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new IllegalStateException("无法写入账号文件: " + file, e);
    }
  }
}
