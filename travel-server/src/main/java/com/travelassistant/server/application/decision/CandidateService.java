package com.travelassistant.server.application.decision;

import com.travelassistant.server.application.itinerary.ItineraryArchivedException;
import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.decision.Candidate;
import com.travelassistant.server.domain.decision.CandidateBoard;
import com.travelassistant.server.domain.decision.CandidateKind;
import com.travelassistant.server.domain.decision.Vote;
import com.travelassistant.server.domain.decision.VoteChoice;
import com.travelassistant.server.domain.decision.VoteTally;
import com.travelassistant.server.domain.itinerary.Itinerary;
import com.travelassistant.server.infrastructure.persistence.DecisionStore;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 候选与投票用例（V3 第 1 节）。
 *
 * <p>权限口径：**能看行程的人就能提候选、就能投票**，不要求板块编辑权。
 * 提候选是提议，不是改方案；如果连「我想去这里」都要先拿到编辑授权，
 * 那投票就只剩下一个人自问自答。真正写进日程仍要 DAILY 编辑权（{@link #apply}），
 * 该拦的地方拦住了，讨论环节没必要设门槛。
 *
 * <p>候选与选票都不进入行程聚合，因此这些操作**不会推动行程版本号**。
 */
@Service
public class CandidateService {

  private final ItineraryService itineraries;
  private final DecisionStore store;

  public CandidateService(ItineraryService itineraries, DecisionStore store) {
    this.itineraries = itineraries;
    this.store = store;
  }

  /** 添加候选项。同名同类型的重复添加被拒绝，而不是静默产生两条一样的候选。 */
  public Candidate add(String itineraryId, String user, CandidateKind kind, String name,
      String city, String attractionId, String note) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    requireEditable(itinerary);

    String trimmedName = name == null ? "" : name.strip();
    if (trimmedName.isEmpty()) {
      throw new InvalidCandidateException("候选名称不能为空");
    }
    if (trimmedName.length() > Candidate.MAX_NAME) {
      throw new InvalidCandidateException(
          "候选名称不能超过 " + Candidate.MAX_NAME + " 字，当前 " + trimmedName.length() + " 字");
    }
    String trimmedNote = note == null ? "" : note.strip();
    if (trimmedNote.length() > Candidate.MAX_NOTE) {
      throw new InvalidCandidateException(
          "备注不能超过 " + Candidate.MAX_NOTE + " 字，当前 " + trimmedNote.length() + " 字");
    }
    CandidateKind actualKind = kind == null ? CandidateKind.ATTRACTION : kind;
    String key = trimmedName.toLowerCase(Locale.ROOT);
    for (Candidate existing : store.findCandidates(itineraryId)) {
      if (existing.kind() == actualKind
          && existing.name().toLowerCase(Locale.ROOT).equals(key)) {
        throw new InvalidCandidateException("候选列表里已有「" + existing.name() + "」");
      }
    }

    Candidate candidate = Candidate.create(itineraryId, actualKind, trimmedName,
        blankToNull(city), blankToNull(attractionId), trimmedNote, user);
    return store.addCandidate(candidate);
  }

  /** 候选看板：投票结果、排序、并列与未投票提示。 */
  public CandidateBoard board(String itineraryId, String user) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    return board(itinerary);
  }

  /** 删除候选项：提出者本人或创建者可删。 */
  public void remove(String itineraryId, String user, String candidateId) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    requireEditable(itinerary);
    Candidate candidate = requireCandidate(itineraryId, candidateId);
    if (!candidate.proposedBy().equals(user) && !itinerary.owner().equals(user)) {
      throw new com.travelassistant.server.application.itinerary.ItineraryForbiddenException();
    }
    store.deleteCandidate(itineraryId, candidateId);
  }

  /** 投票或改票：同一人同一候选只保留一张票，重复投票是改主意而不是加票。 */
  public CandidateBoard vote(String itineraryId, String user, String candidateId, VoteChoice choice) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    requireEditable(itinerary);
    requireCandidate(itineraryId, candidateId);
    if (choice == null) {
      throw new InvalidCandidateException("请选择投票选项：SUPPORT / OPPOSE / ABSTAIN");
    }

    Optional<Vote> existing = store.findVote(candidateId, user);
    if (existing.isPresent()) {
      store.saveVote(existing.get().withChoice(choice));
    } else {
      store.saveVote(Vote.create(itineraryId, candidateId, user, choice));
    }
    return board(itinerary);
  }

  /** 撤回自己的票。 */
  public CandidateBoard withdrawVote(String itineraryId, String user, String candidateId) {
    Itinerary itinerary = itineraries.get(itineraryId, user);
    requireEditable(itinerary);
    requireCandidate(itineraryId, candidateId);
    if (!store.removeVote(candidateId, user)) {
      throw new InvalidCandidateException("你还没有对这项候选投票，无法撤回");
    }
    return board(itinerary);
  }

  /**
   * 确认候选：把票选出来的地方真正写进某一天的日程。
   *
   * <p>这是「投票不自动改动行程」的落点——只有用户显式调用它，方案才会变。
   * 需要 DAILY 编辑权与当前版本号，走的是 {@code ItineraryService.appendActivity}，
   * 因此会产生一条正常的修改记录，而不是绕过审计偷偷写入。
   */
  public Itinerary apply(String itineraryId, String user, String candidateId, LocalDate date,
      long version) {
    Candidate candidate = requireCandidate(itineraryId, candidateId);
    String location = candidate.city() == null ? candidate.name() : candidate.city();
    String note = candidate.note() == null || candidate.note().isBlank()
        ? "由候选「" + candidate.name() + "」确认加入"
        : candidate.note();
    return itineraries.appendActivity(itineraryId, user, date, candidate.name(), location, note,
        version);
  }

  /** 供决策建议复用的看板构造（不再重复一次权限校验）。 */
  public CandidateBoard boardOf(Itinerary itinerary) {
    return board(itinerary);
  }

  private CandidateBoard board(Itinerary itinerary) {
    List<Candidate> candidates = store.findCandidates(itinerary.id());
    List<Vote> votes = store.findVotes(itinerary.id());
    int participants = 1 + itinerary.members().size();
    return VoteTally.board(itinerary.id(), itinerary.version(), participants, candidates, votes);
  }

  private Candidate requireCandidate(String itineraryId, String candidateId) {
    return store.findCandidate(itineraryId, candidateId)
        .orElseThrow(() -> new CandidateNotFoundException(candidateId));
  }

  /** 归档后只读：候选与投票同样不例外。 */
  private void requireEditable(Itinerary itinerary) {
    if (itinerary.archived()) {
      throw new ItineraryArchivedException(itinerary.id());
    }
  }

  private String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.strip();
  }
}
