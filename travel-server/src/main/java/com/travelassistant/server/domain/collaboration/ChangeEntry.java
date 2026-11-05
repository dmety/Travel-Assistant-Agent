package com.travelassistant.server.domain.collaboration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * 一条修改记录：谁、在什么时候、改了哪个板块、改成了什么（V2 第 4 节）。
 *
 * <p>由服务端在写操作成功后自动产生，**不接受客户端提交**。让客户端自报修改内容，
 * 等于让被审计的人自己写审计日志。
 *
 * @param section 板块代码（{@code PLAN}/{@code DAILY}/{@code TRANSPORT}/{@code LODGING}/
 *                {@code EXPENSE}/{@code MEMBER}/{@code COMMENT}），稳定值，供前端筛选
 * @param summary 中文摘要，说明本次改了什么
 * @param version 本次写入后的行程版本号
 */
public record ChangeEntry(
    @JsonProperty("id") String id,
    @JsonProperty("itineraryId") String itineraryId,
    @JsonProperty("actor") String actor,
    @JsonProperty("at") Instant at,
    @JsonProperty("section") String section,
    @JsonProperty("summary") String summary,
    @JsonProperty("version") long version) {

  @JsonCreator
  public ChangeEntry {
  }

  public static ChangeEntry of(String itineraryId, String actor, String section, String summary,
      long version) {
    return new ChangeEntry(UUID.randomUUID().toString(), itineraryId, actor, Instant.now(),
        section, summary, version);
  }
}
