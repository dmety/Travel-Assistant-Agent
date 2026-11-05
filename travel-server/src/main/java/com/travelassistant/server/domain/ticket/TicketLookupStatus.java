package com.travelassistant.server.domain.ticket;

/** 购票辅助的查询结果状态。 */
public enum TicketLookupStatus {
  /** 找到配置中的官方入口，可据其跳转购买。 */
  CHANNEL_FOUND,
  /** 没有经核验的官方入口：如实告知用户自行前往官方渠道，不生成猜测链接。 */
  NO_VERIFIED_CHANNEL
}
