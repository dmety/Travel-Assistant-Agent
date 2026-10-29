package com.travelassistant.common.api;
/** Shared transport contract, independent of server implementation. */
public record HealthResponse(String status, String application) {}
