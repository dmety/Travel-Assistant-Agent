package com.travelassistant.server.application.agent;

/**
 * 草案阶段的**结构化补问**：缺什么、补到哪个字段、怎么补、补完调哪个接口重试。
 *
 * <p>原先缺项只写进 {@code notices} 的一段文字，客户端无法据此渲染表单，
 * 也没有「补完重试」的字段对应关系。这里把补问做成一等公民的数据，
 * 让缺项闭环可被程序消费，同时保留 notices 里的人类可读提示（老客户端不受影响）。
 *
 * @param code       稳定的原因码，如 {@code MISSING_BUDGET}
 * @param field      需要补全的请求字段名，与 {@link PlanningRequest} 的 JSON 字段同名
 * @param question   给用户看的问题
 * @param hint       合法取值与填写方式
 * @param retryWith  补完后重新调用的接口
 */
public record DraftQuestion(
    String code,
    String field,
    String question,
    String hint,
    String retryWith) {
}
