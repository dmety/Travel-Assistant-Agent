package com.travelassistant.server.domain.budget;

/**
 * 一条体检提示。
 *
 * @param code    稳定代码，供前端/测试判断，不随文案改动（如 {@code RETURN_BEFORE_DEPART}）
 * @param field   相关字段路径（如 {@code plan.departDate}），无具体字段时为空串
 * @param message 面向用户的中文说明
 */
public record CheckWarning(String code, String field, String message) {
}
