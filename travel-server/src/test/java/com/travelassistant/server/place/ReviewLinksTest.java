package com.travelassistant.server.place;

import static org.assertj.core.api.Assertions.assertThat;

import com.travelassistant.server.application.place.ReviewLinks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 评价入口只认地图官方域名：评论正文不在本系统，也不能按景区名猜一个官网出来。
 */
@DisplayName("评价官方入口 ReviewLinks")
class ReviewLinksTest {

  @Test
  @DisplayName("高德真实 POI id 拼官方详情页；脏 id 一律不给链接")
  void amapPlaceOnlyForRealIds() {
    assertThat(ReviewLinks.amapPlace("B02C900F7B"))
        .isEqualTo("https://www.amap.com/place/B02C900F7B");
    assertThat(ReviewLinks.amapPlace(null)).isNull();
    assertThat(ReviewLinks.amapPlace("")).isNull();
    assertThat(ReviewLinks.amapPlace("B001")).isNull();
    assertThat(ReviewLinks.amapPlace("javascript:alert(1)")).isNull();
    assertThat(ReviewLinks.amapPlace("https://evil.example/B02C900F7B")).isNull();
  }

  @Test
  @DisplayName("百度官方 detail_url 升 https 并保留参数；非官方域名拒绝")
  void baiduDetailOnlyOfficial() {
    assertThat(ReviewLinks.baiduDetail(
        "http://api.map.baidu.com/place/detail?uid=35debf292fc0c444a6da3922&output=html&source=placeapi_v3"))
        .isEqualTo("https://api.map.baidu.com/place/detail?uid=35debf292fc0c444a6da3922"
            + "&output=html&source=placeapi_v3");
    assertThat(ReviewLinks.baiduDetail("https://map.baidu.com/poi/?uid=abc&ugc_type=3"))
        .isEqualTo("https://map.baidu.com/poi/?uid=abc&ugc_type=3");
    assertThat(ReviewLinks.baiduDetail("https://evil.example/reviews")).isNull();
    assertThat(ReviewLinks.baiduDetail("javascript:alert(1)")).isNull();
    assertThat(ReviewLinks.baiduDetail(null)).isNull();
    assertThat(ReviewLinks.baiduDetail("不是网址")).isNull();
  }

  @Test
  @DisplayName("知识库条目只给官方搜索页，中文参数编码且不猜官网")
  void amapSearchIsOfficialAndEncoded() {
    String url = ReviewLinks.amapSearch("五祖寺", "黄冈市");
    assertThat(url).startsWith("https://uri.amap.com/search?keyword=")
        .contains("%E4%BA%94%E7%A5%96%E5%AF%BA")
        .contains("&city=");
    assertThat(ReviewLinks.official(url)).isEqualTo(url);
    assertThat(ReviewLinks.amapSearch("", "北京")).isNull();
  }

  @Test
  @DisplayName("白名单只含地图官方域名，拒绝任意 http 与形似域名的第三方")
  void hostAllowlist() {
    assertThat(ReviewLinks.OFFICIAL_HOSTS).contains("www.amap.com", "map.baidu.com")
        .doesNotContain("example.com", "evil.test");
    assertThat(ReviewLinks.official("http://www.amap.com/place/B02C900F7B"))
        .isEqualTo("https://www.amap.com/place/B02C900F7B");
    assertThat(ReviewLinks.official("ftp://www.amap.com/x")).isNull();
    assertThat(ReviewLinks.official("https://www.amap.com.evil.test/x")).isNull();
  }
}
