package com.travelassistant.server.domain.decision;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * 候选项：某位成员提出、等待大家表态的景点或住宿（V3 第 1 节）。
 *
 * <p>候选项独立落盘，**不进入行程聚合**。这是一条与 V2 留言一致的原则：
 * 候选只是「提议」，还没成为方案的一部分；如果加候选就推动行程版本号，
 * 一个人添加、另一个人正在改日程，就会撞上无意义的 409。
 * 候选真正进入行程必须由用户显式确认（{@code apply}），那一刻才写正式板块。
 *
 * <p>{@code attractionId} 可能为空：手写的地点不在知识库里，这很正常，
 * 不做强制关联——强行要求从库里选，等于禁止用户提库里没有的地方。
 */
public record Candidate(
    @JsonProperty("id") String id,
    @JsonProperty("itineraryId") String itineraryId,
    @JsonProperty("kind") CandidateKind kind,
    @JsonProperty("name") String name,
    @JsonProperty("city") String city,
    @JsonProperty("attractionId") String attractionId,
    @JsonProperty("note") String note,
    @JsonProperty("proposedBy") String proposedBy,
    @JsonProperty("createdAt") Instant createdAt) {

  /** 名称长度上限。 */
  public static final int MAX_NAME = 80;
  /** 备注长度上限。 */
  public static final int MAX_NOTE = 500;

  @JsonCreator
  public Candidate {
    kind = (kind == null) ? CandidateKind.ATTRACTION : kind;
  }

  public static Candidate create(String itineraryId, CandidateKind kind, String name, String city,
      String attractionId, String note, String proposedBy) {
    return new Candidate(UUID.randomUUID().toString(), itineraryId, kind, name, city,
        attractionId, note, proposedBy, Instant.now());
  }
}
