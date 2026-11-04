package com.travelassistant.server.domain.attraction;

import java.util.Set;

/**
 * 占位值规范化规则。
 *
 * <p>知识库里曾出现过"暂无 / 未知 / 无 / -"等伪值，它们**不代表真实信息**。
 * 离线构建脚本已做一次清洗，此处再做一次运行时兜底，防止手工改动或旧版库
 * 让占位值穿透到展示层。空串、纯空白同样视为缺失。
 */
public final class PlaceholderValues {

  /** 展示层统一的缺失文案。 */
  public static final String UNKNOWN_LABEL = "未知";

  private static final Set<String> PLACEHOLDERS = Set.of(
      "", "暂无", "暂无信息", "暂无数据", "未知", "无", "没有",
      "-", "--", "—", "——", "null", "none", "n/a", "na", "nan");

  private PlaceholderValues() {
  }

  /**
   * 规范化单个文本字段。
   *
   * @param value 原始值
   * @return 去空白后的有效值；占位值或空白返回 {@code null}
   */
  public static String clean(String value) {
    if (value == null) {
      return null;
    }
    String text = value.strip();
    if (text.isEmpty() || PLACEHOLDERS.contains(text.toLowerCase())) {
      return null;
    }
    return text;
  }

  /** 展示用文本：缺失时返回"未知"。 */
  public static String display(String value) {
    String cleaned = clean(value);
    return cleaned == null ? UNKNOWN_LABEL : cleaned;
  }

  /** 是否为占位值（含空白与 null）。 */
  public static boolean isPlaceholder(String value) {
    return clean(value) == null;
  }
}
