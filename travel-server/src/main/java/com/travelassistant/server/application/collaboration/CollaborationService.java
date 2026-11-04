package com.travelassistant.server.application.collaboration;

import com.travelassistant.server.application.itinerary.ItineraryService;
import com.travelassistant.server.domain.collaboration.ChangeEntry;
import com.travelassistant.server.domain.collaboration.Comment;
import com.travelassistant.server.infrastructure.persistence.CollaborationStore;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 协作用例：留言与修改记录（V2 第 4 节）。
 *
 * <p>权限口径：能看行程的人就能看留言和修改记录，也能留言。
 * 不要求板块编辑权——被授权「只看」的成员也应该能参与讨论，
 * 而留言本身改不动方案，越权风险为零。写入权限已经由行程的板块授权单独控制。
 */
@Service
public class CollaborationService {

  private final ItineraryService itineraries;
  private final CollaborationStore store;

  public CollaborationService(ItineraryService itineraries, CollaborationStore store) {
    this.itineraries = itineraries;
    this.store = store;
  }

  /** 发表留言。作者取自服务端会话，不接受客户端自称。 */
  public Comment comment(String itineraryId, String user, String content) {
    itineraries.get(itineraryId, user); // 越权访问在这里抛 403
    String text = content == null ? "" : content.strip();
    if (text.isEmpty()) {
      throw new InvalidCommentException("留言内容不能为空");
    }
    if (text.length() > Comment.MAX_LENGTH) {
      throw new InvalidCommentException(
          "留言内容不能超过 " + Comment.MAX_LENGTH + " 字，当前 " + text.length() + " 字");
    }
    return store.addComment(Comment.create(itineraryId, user, text));
  }

  public List<Comment> comments(String itineraryId, String user) {
    itineraries.get(itineraryId, user);
    return store.findComments(itineraryId);
  }

  public List<ChangeEntry> changes(String itineraryId, String user, int limit) {
    itineraries.get(itineraryId, user);
    return store.findChanges(itineraryId, limit);
  }
}
