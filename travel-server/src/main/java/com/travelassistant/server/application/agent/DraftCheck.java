package com.travelassistant.server.application.agent;

/**
 * 草案阶段的日期与预算检查结果。
 *
 * <p>与 {@code BudgetService} 的行程体检不同：这里检查的是**还没落库**的草案，
 * 输入只有用户填写的需求和知识库候选，没有任何已保存行程。所有结论都由规则算出，
 * 不含模型推断；信息不足时返回「无法判定」，而不是把未知当成「没问题」。
 *
 * @param code       稳定原因码，如 {@code RETURN_BEFORE_DEPART}
 * @param severity   HIGH / MEDIUM / INFO
 * @param field      关联的检查域：{@code date} 或 {@code budget}
 * @param message    给用户看的结论
 * @param suggestion 可执行的补救动作；没有时可为 {@code null}
 */
public record DraftCheck(
    String code,
    String severity,
    String field,
    String message,
    String suggestion) {
}
