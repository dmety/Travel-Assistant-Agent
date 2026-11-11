package com.travelassistant.server.application.place;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 景点「评价」的官方跳转链接。
 *
 * <p>前提：高德 / 百度 Web 服务 API 只返回评分与评价数，<b>不返回评论正文</b>
 * （实测 75 条 POI，高德 {@code featured_reviews} 全为空数组；百度只有
 * {@code overall_rating} 与 {@code comment_num}）。平台条款也不允许抓取 UGC 评论，
 * 因此卡片不能内联「别人的评论文字」，更不能生成几条假评价。点击评价数改为
 * 跳到地图官方 POI 详情页查看真实评论。
 *
 * <p>链接只有三种来源，全部是 API 原文或官方公开 URI，绝不按景区名猜官网：
 * <ol>
 *   <li>高德 POI 的 {@code id} → {@code https://www.amap.com/place/<id>}（实测 200）；</li>
 *   <li>百度 {@code detail_info.detail_url}（API 原文，实测 302 到
 *       {@code map.baidu.com/poi/...&ugc_type=3} 的 UGC 评论页）；</li>
 *   <li>知识库条目没有 POI id → 高德官方 URI 搜索页，UI 必须标注「搜索页」而非评论页。</li>
 * </ol>
 * 所有外链都过 {@link #official(String)} 的域名白名单，防止把任意 URL 塞进 href。
 */
public final class ReviewLinks {

  /** 允许出现在 href 里的地图官方域名；前端另有一份同样口径的校验。 */
  public static final Set<String> OFFICIAL_HOSTS = Set.of(
      "www.amap.com", "ditu.amap.com", "uri.amap.com",
      "map.baidu.com", "api.map.baidu.com");

  private static final Pattern POI_ID = Pattern.compile("[A-Za-z0-9]{6,64}");
  private static final String AMAP_PLACE = "https://www.amap.com/place/";
  private static final String AMAP_SEARCH = "https://uri.amap.com/search?keyword=";

  private ReviewLinks() {
  }

  /** 高德：POI id 是 API 返回的真实标识，拼官方详情页；id 不合法就不给链接。 */
  public static String amapPlace(String poiId) {
    if (poiId == null || !POI_ID.matcher(poiId).matches()) {
      return null;
    }
    return AMAP_PLACE + poiId;
  }

  /** 百度：只用 API 返回的 detail_url；http 升 https，非官方域名一律拒绝。 */
  public static String baiduDetail(String detailUrl) {
    return official(detailUrl);
  }

  /** 知识库条目没有 POI id：给官方地图搜索页（不是评论页，UI 必须标注）。 */
  public static String amapSearch(String name, String city) {
    if (name == null || name.isBlank()) {
      return null;
    }
    StringBuilder sb = new StringBuilder(AMAP_SEARCH).append(encode(name));
    if (city != null && !city.isBlank()) {
      sb.append("&city=").append(encode(city));
    }
    return sb.toString();
  }

  /** 官方域名白名单 + 强制 https；返回规范化 URL 或 null。 */
  public static String official(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      URI uri = URI.create(raw.trim());
      String scheme = uri.getScheme();
      String host = uri.getHost();
      if (host == null
          || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
          || !OFFICIAL_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
        return null;
      }
      StringBuilder sb = new StringBuilder("https://").append(host.toLowerCase(Locale.ROOT));
      if (uri.getRawPath() != null && !uri.getRawPath().isBlank()) {
        sb.append(uri.getRawPath());
      }
      if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
        sb.append('?').append(uri.getRawQuery());
      }
      return sb.toString();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String encode(String raw) {
    return URLEncoder.encode(raw, StandardCharsets.UTF_8);
  }
}
