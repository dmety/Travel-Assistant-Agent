package com.travelassistant.server.domain.collaboration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * 行程留言（V2 第 4 节「协作记录与留言」）。
 *
 * <p>留言独立落盘，不进入行程聚合，因此**发表留言不会改变行程版本号**——
 * 留言是讨论，不是对方案的编辑；如果它让版本号 +1，正在编辑的人会被迫处理冲突。
 *
 * <p>不提供编辑与删除：V2 只要求「留言讨论」，已有留言被改写会让讨论记录失去意义。
 * 内容长度上限在服务层校验，这里只负责数据形态。
 */
public record Comment(
    @JsonProperty("id") String id,
    @JsonProperty("itineraryId") String itineraryId,
    @JsonProperty("author") String author,
    @JsonProperty("content") String content,
    @JsonProperty("createdAt") Instant createdAt) {

  /** 单条留言的字符上限。 */
  public static final int MAX_LENGTH = 2000;

  @JsonCreator
  public Comment {
  }

  public static Comment create(String itineraryId, String author, String content) {
    return new Comment(UUID.randomUUID().toString(), itineraryId, author, content, Instant.now());
  }
}
