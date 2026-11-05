package com.travelassistant.server.domain.budget;

/** 未填写金额的费用条目（V1：未填写费用标记为待填写）。 */
public record PendingExpense(String id, String category, String note) {
}
