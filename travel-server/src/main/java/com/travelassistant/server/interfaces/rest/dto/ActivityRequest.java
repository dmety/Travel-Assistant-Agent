package com.travelassistant.server.interfaces.rest.dto;

import java.time.LocalDate;

/**
 * 追加一项安排（V3「确认候选」与手工登记共用）。
 *
 * <p>字段极简：只描述「某天要做什么」。起止时间可以留空——很多安排（"逛逛老城区"）
 * 本来就没有确定时刻，强制必填只会让人随便填一个。
 *
 * @param date     目标日期，必填
 * @param title    安排名称，必填
 * @param location 地点，可为空
 * @param note     备注，可为空
 */
public record ActivityRequest(LocalDate date, String title, String location, String note) {
}
