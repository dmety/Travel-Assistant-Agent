package com.travelassistant.server.domain.decision;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * 一张选票（V3 第 1 节）。
 *
 * <p>同一成员对同一候选项只保留一张票：再次投票是**改票**，不是叠加。
 * 记录 {@code votedAt} 的同时保留 {@code updatedAt}，是为了在结果里能说明
 * 「这人最后改过主意」，而不是让早期表态凭空消失。
 */
public record Vote(
    @JsonProperty("id") String id,
    @JsonProperty("itineraryId") String itineraryId,
    @JsonProperty("candidateId") String candidateId,
    @JsonProperty("voter") String voter,
    @JsonProperty("choice") VoteChoice choice,
    @JsonProperty("votedAt") Instant votedAt,
    @JsonProperty("updatedAt") Instant updatedAt) {

  @JsonCreator
  public Vote {
    choice = (choice == null) ? VoteChoice.ABSTAIN : choice;
  }

  public static Vote create(String itineraryId, String candidateId, String voter,
      VoteChoice choice) {
    Instant now = Instant.now();
    return new Vote(UUID.randomUUID().toString(), itineraryId, candidateId, voter, choice,
        now, now);
  }

  /** 改票：保留首次投票时间，更新选项与更新时间。 */
  public Vote withChoice(VoteChoice newChoice) {
    return new Vote(id, itineraryId, candidateId, voter, newChoice, votedAt, Instant.now());
  }
}
