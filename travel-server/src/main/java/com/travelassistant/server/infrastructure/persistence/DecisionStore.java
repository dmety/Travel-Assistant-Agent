package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.decision.Candidate;
import com.travelassistant.server.domain.decision.Vote;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * 决策数据仓库：候选项写 {@code candidates.json}，选票写 {@code votes.json}。
 *
 * <p>与账号、行程、邀请、留言一致：读取走内存副本，写入先落临时文件再原子替换。
 * 候选与选票都不放进行程聚合，因此**添加候选、投票都不会改变行程版本号**——
 * 它们讨论的对象是「要不要去」，而不是「行程怎么写」。
 *
 * <p>删除候选项时连带清掉它名下的选票，避免留下指向已删除候选的孤儿票。
 */
@Repository
public class DecisionStore {
  private static final Logger log = LoggerFactory.getLogger(DecisionStore.class);

  private final ObjectMapper mapper;
  private final Path candidateFile;
  private final Path voteFile;
  private volatile List<Candidate> candidates = new ArrayList<>();
  private volatile List<Vote> votes = new ArrayList<>();

  public DecisionStore(ObjectMapper mapper, @Value("${travel.data-directory}") String dataDirectory) {
    this.mapper = mapper;
    Path dir = Paths.get(dataDirectory).toAbsolutePath();
    this.candidateFile = dir.resolve("candidates.json");
    this.voteFile = dir.resolve("votes.json");
  }

  @PostConstruct
  public synchronized void init() throws IOException {
    Path dir = candidateFile.getParent();
    if (dir != null) {
      Files.createDirectories(dir);
    }
    candidates = new ArrayList<>(read(candidateFile, Candidate[].class, "候选项"));
    votes = new ArrayList<>(read(voteFile, Vote[].class, "选票"));
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

  // ---- 候选 ----

  /** 某行程的候选项，按添加时间升序（先提的先列）。 */
  public List<Candidate> findCandidates(String itineraryId) {
    return candidates.stream()
        .filter(c -> c.itineraryId().equals(itineraryId))
        .sorted(Comparator.comparing(Candidate::createdAt))
        .toList();
  }

  public Optional<Candidate> findCandidate(String itineraryId, String candidateId) {
    return candidates.stream()
        .filter(c -> c.itineraryId().equals(itineraryId) && c.id().equals(candidateId))
        .findFirst();
  }

  public synchronized Candidate addCandidate(Candidate candidate) {
    List<Candidate> updated = new ArrayList<>(candidates);
    updated.add(candidate);
    candidates = updated;
    write(candidateFile, candidates, "候选项");
    return candidate;
  }

  /** 删除候选项，并连带删除它名下的全部选票。 */
  public synchronized void deleteCandidate(String itineraryId, String candidateId) {
    boolean removed = candidates.removeIf(
        c -> c.itineraryId().equals(itineraryId) && c.id().equals(candidateId));
    if (removed) {
      write(candidateFile, candidates, "候选项");
    }
    boolean voteRemoved = votes.removeIf(v -> v.candidateId().equals(candidateId));
    if (voteRemoved) {
      write(voteFile, votes, "选票");
    }
  }

  // ---- 选票 ----

  public List<Vote> findVotes(String itineraryId) {
    return votes.stream()
        .filter(v -> v.itineraryId().equals(itineraryId))
        .sorted(Comparator.comparing(Vote::votedAt))
        .toList();
  }

  public List<Vote> findVotes(String itineraryId, String candidateId) {
    return votes.stream()
        .filter(v -> v.itineraryId().equals(itineraryId) && v.candidateId().equals(candidateId))
        .sorted(Comparator.comparing(Vote::votedAt))
        .toList();
  }

  public Optional<Vote> findVote(String candidateId, String voter) {
    return votes.stream()
        .filter(v -> v.candidateId().equals(candidateId) && v.voter().equals(voter))
        .findFirst();
  }

  /** 投票或改票：同一（候选，投票人）只保留一张票。 */
  public synchronized Vote saveVote(Vote vote) {
    List<Vote> updated = new ArrayList<>(votes);
    boolean replaced = false;
    for (int i = 0; i < updated.size(); i++) {
      Vote existing = updated.get(i);
      if (existing.candidateId().equals(vote.candidateId())
          && existing.voter().equals(vote.voter())) {
        updated.set(i, vote);
        replaced = true;
        break;
      }
    }
    if (!replaced) {
      updated.add(vote);
    }
    votes = updated;
    write(voteFile, votes, "选票");
    return vote;
  }

  /** 撤回自己的票；返回是否确实删掉了。 */
  public synchronized boolean removeVote(String candidateId, String voter) {
    boolean removed = votes.removeIf(
        v -> v.candidateId().equals(candidateId) && v.voter().equals(voter));
    if (removed) {
      write(voteFile, votes, "选票");
    }
    return removed;
  }

  /** 行程删除后清掉候选与选票。 */
  public synchronized void purge(String itineraryId) {
    boolean candidateRemoved = candidates.removeIf(c -> c.itineraryId().equals(itineraryId));
    if (candidateRemoved) {
      write(candidateFile, candidates, "候选项");
    }
    boolean voteRemoved = votes.removeIf(v -> v.itineraryId().equals(itineraryId));
    if (voteRemoved) {
      write(voteFile, votes, "选票");
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
