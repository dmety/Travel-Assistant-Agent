package com.travelassistant.server.application.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 「确认保存」请求：用户看过草案、补完缺项之后，显式要求把草案变成行程载荷。
 *
 * <p>请求里**不回传草案的结论**（票价、来源、未知项都不由客户端说了算）。
 * 服务端会按 {@code request} 重新跑一遍确定性检索，并按 {@code attractionIds}
 * 从知识库重新读取景点事实，杜绝客户端伪造景点 ID 或篡改票价。
 *
 * @param title         正式行程标题，必填
 * @param request       与生成草案时相同的需求（含补全后的字段）
 * @param attractionIds 用户选中的景点 ID，按希望安排的顺序；为空表示沿用草案里已分配的全部景点
 */
public record ConfirmDraftRequest(
    @JsonProperty("title") String title,
    @JsonProperty("request") PlanningRequest request,
    @JsonProperty("attractionIds") List<String> attractionIds) {

  @JsonCreator
  public ConfirmDraftRequest {
    attractionIds = attractionIds == null ? List.of() : List.copyOf(attractionIds);
  }
}
