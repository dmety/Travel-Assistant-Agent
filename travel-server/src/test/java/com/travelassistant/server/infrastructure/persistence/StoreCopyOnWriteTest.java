package com.travelassistant.server.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.domain.collaboration.Comment;
import com.travelassistant.server.domain.decision.Candidate;
import com.travelassistant.server.domain.decision.CandidateKind;
import com.travelassistant.server.domain.decision.Vote;
import com.travelassistant.server.domain.decision.VoteChoice;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 删除类写操作的<b>写时复制（COW）</b>不变量（缺陷 F4，确定性复现）。
 *
 * <p>读方法（{@code findCandidates/findVotes/findComments/findChanges}）不加锁，直接对
 * volatile 列表 {@code stream()}。若删除写方法在<b>原列表上</b> {@code removeIf}，正在迭代的
 * 读线程会撞 {@link java.util.ConcurrentModificationException}。添加类写操作早已走 COW
 * （新建 {@code ArrayList} → 赋值 volatile 引用），删除也必须一致：新建过滤后的列表 → 原子替换，
 * 读线程持有的旧快照永不被就地改动。
 *
 * <p><b>为什么不用并发压测判定</b>：与 {@code OptimisticLockAtomicityTest} 同一原则——
 * 概率压测依赖线程调度，缺陷还在时也可能偶然变绿。这里把竞态变成必然：取出写操作前的活列表
 * 引用（这正是读线程 {@code stream()} 捕获的那个对象），建立迭代器并前进一步后调用删除，
 * 再继续迭代。就地 {@code removeIf} 必然抛 CME 且旧快照被改小；COW 则旧快照分毫不动。
 * 结果与线程快慢无关，跑多少次都一样。
 */
@DisplayName("F4 删除写操作的写时复制不变量（确定性复现）")
class StoreCopyOnWriteTest {

  private static final String ITIN = "itin-1";
  private static final String OTHER = "itin-2";

  @Test
  @DisplayName("DecisionStore.deleteCandidate：候选与选票列表都不得就地改动")
  void deleteCandidateCopiesBothLists() throws Exception {
    DecisionStore store = newDecisionStore("cow-delete-candidate");
    Candidate a = addCandidate(store, ITIN, "西湖");
    Candidate b = addCandidate(store, ITIN, "灵隐寺");
    addCandidate(store, ITIN, "雷峰塔");
    store.saveVote(Vote.create(ITIN, b.id(), "alice", VoteChoice.SUPPORT));
    store.saveVote(Vote.create(ITIN, b.id(), "bob", VoteChoice.OPPOSE));
    store.saveVote(Vote.create(ITIN, a.id(), "alice", VoteChoice.SUPPORT));

    List<Candidate> liveCandidates = liveList(store, "candidates");
    List<Vote> liveVotes = liveList(store, "votes");
    int candidatesBefore = liveCandidates.size();
    int votesBefore = liveVotes.size();

    Iterator<Candidate> candIt = liveCandidates.iterator();
    Iterator<Vote> voteIt = liveVotes.iterator();
    candIt.next();
    voteIt.next();

    store.deleteCandidate(ITIN, b.id());

    assertThatCode(candIt::next)
        .as("删除候选不得就地改动读线程正在迭代的候选列表")
        .doesNotThrowAnyException();
    assertThatCode(voteIt::next)
        .as("连带删票不得就地改动读线程正在迭代的选票列表")
        .doesNotThrowAnyException();
    assertThat(liveCandidates).as("旧候选快照未被改小").hasSize(candidatesBefore);
    assertThat(liveVotes).as("旧选票快照未被改小").hasSize(votesBefore);
    assertThat(store.findCandidates(ITIN)).extracting(Candidate::name)
        .containsExactly("西湖", "雷峰塔");
    assertThat(store.findVotes(ITIN, b.id())).isEmpty();
  }

  @Test
  @DisplayName("DecisionStore.removeVote：选票列表不得就地改动")
  void removeVoteCopiesList() throws Exception {
    DecisionStore store = newDecisionStore("cow-remove-vote");
    Candidate c = addCandidate(store, ITIN, "西湖");
    store.saveVote(Vote.create(ITIN, c.id(), "alice", VoteChoice.SUPPORT));
    store.saveVote(Vote.create(ITIN, c.id(), "bob", VoteChoice.OPPOSE));
    store.saveVote(Vote.create(ITIN, c.id(), "carol", VoteChoice.ABSTAIN));

    List<Vote> liveVotes = liveList(store, "votes");
    int before = liveVotes.size();
    Iterator<Vote> it = liveVotes.iterator();
    it.next();

    boolean removed = store.removeVote(c.id(), "bob");

    assertThat(removed).isTrue();
    assertThatCode(it::next)
        .as("撤票不得就地改动读线程正在迭代的选票列表")
        .doesNotThrowAnyException();
    assertThat(liveVotes).as("旧选票快照未被改小").hasSize(before);
    assertThat(store.findVotes(ITIN, c.id())).extracting(Vote::voter)
        .containsExactly("alice", "carol");
  }

  @Test
  @DisplayName("DecisionStore.purge：候选与选票列表都不得就地改动")
  void purgeDecisionCopiesBothLists() throws Exception {
    DecisionStore store = newDecisionStore("cow-purge-decision");
    Candidate a = addCandidate(store, ITIN, "西湖");
    addCandidate(store, ITIN, "灵隐寺");
    addCandidate(store, OTHER, "外滩");
    store.saveVote(Vote.create(ITIN, a.id(), "alice", VoteChoice.SUPPORT));
    store.saveVote(Vote.create(ITIN, a.id(), "bob", VoteChoice.OPPOSE));

    List<Candidate> liveCandidates = liveList(store, "candidates");
    List<Vote> liveVotes = liveList(store, "votes");
    int candidatesBefore = liveCandidates.size();
    int votesBefore = liveVotes.size();
    Iterator<Candidate> candIt = liveCandidates.iterator();
    Iterator<Vote> voteIt = liveVotes.iterator();
    candIt.next();
    voteIt.next();

    store.purge(ITIN);

    assertThatCode(candIt::next).doesNotThrowAnyException();
    assertThatCode(voteIt::next).doesNotThrowAnyException();
    assertThat(liveCandidates).hasSize(candidatesBefore);
    assertThat(liveVotes).hasSize(votesBefore);
    assertThat(store.findCandidates(ITIN)).isEmpty();
    assertThat(store.findCandidates(OTHER)).extracting(Candidate::name).containsExactly("外滩");
    assertThat(store.findVotes(ITIN)).isEmpty();
  }

  @Test
  @DisplayName("CollaborationStore.purge：留言与修改记录列表都不得就地改动")
  void purgeCollaborationCopiesBothLists() throws Exception {
    Path dir = com.travelassistant.server.TestDataDirs.sub("cow-purge-collab");
    CollaborationStore store = new CollaborationStore(new ObjectMapper().findAndRegisterModules(),
        dir.toString());
    store.init();
    store.addComment(Comment.create(ITIN, "alice", "去西湖"));
    store.addComment(Comment.create(ITIN, "bob", "也行"));
    store.addComment(Comment.create(OTHER, "alice", "外滩呢"));
    store.addChange(ChangeEntry.of(ITIN, "alice", "PLAN", "创建行程", 1L));
    store.addChange(ChangeEntry.of(ITIN, "bob", "DAILY", "更新方案", 2L));

    List<Comment> liveComments = liveList(store, "comments");
    List<ChangeEntry> liveChanges = liveList(store, "changes");
    int commentsBefore = liveComments.size();
    int changesBefore = liveChanges.size();
    Iterator<Comment> commentIt = liveComments.iterator();
    Iterator<ChangeEntry> changeIt = liveChanges.iterator();
    commentIt.next();
    changeIt.next();

    store.purge(ITIN);

    assertThatCode(commentIt::next)
        .as("清留言不得就地改动读线程正在迭代的留言列表")
        .doesNotThrowAnyException();
    assertThatCode(changeIt::next)
        .as("清修改记录不得就地改动读线程正在迭代的记录列表")
        .doesNotThrowAnyException();
    assertThat(liveComments).hasSize(commentsBefore);
    assertThat(liveChanges).hasSize(changesBefore);
    assertThat(store.findComments(ITIN)).isEmpty();
    assertThat(store.findComments(OTHER)).hasSize(1);
    assertThat(store.findChanges(ITIN, 50)).isEmpty();
  }

  private DecisionStore newDecisionStore(String name) throws Exception {
    Path dir = com.travelassistant.server.TestDataDirs.sub(name);
    DecisionStore store = new DecisionStore(new ObjectMapper().findAndRegisterModules(),
        dir.toString());
    store.init();
    return store;
  }

  private Candidate addCandidate(DecisionStore store, String itineraryId, String name) {
    return store.addCandidate(
        Candidate.create(itineraryId, CandidateKind.ATTRACTION, name, "杭州", null, "", "alice"));
  }

  /** 取出写操作前的活列表引用——正是并发读线程 {@code stream()} 会捕获的那个对象。 */
  @SuppressWarnings("unchecked")
  private static <T> List<T> liveList(Object store, String field) throws Exception {
    Field f = store.getClass().getDeclaredField(field);
    f.setAccessible(true);
    return (List<T>) f.get(store);
  }
}