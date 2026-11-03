package com.travelassistant.server.domain.itinerary;

/** 行程内可授权的板块。成员权限按板块细分（更细粒度）。 */
public enum Section {
  PLAN,      // 方案
  DAILY,     // 每日安排
  TRANSPORT, // 交通
  LODGING,   // 住宿
  EXPENSE    // 费用
}
