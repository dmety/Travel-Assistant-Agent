package com.travelassistant.common.api;
/** 客户端和后端共用这个响应，不用知道对方内部怎么实现。 */
public record HealthResponse(String status, String application) {}
