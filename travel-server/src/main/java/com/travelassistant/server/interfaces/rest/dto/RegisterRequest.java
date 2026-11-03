package com.travelassistant.server.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;

/** 注册请求体。 */
public record RegisterRequest(
    @NotBlank(message = "用户名不能为空") String username,
    @NotBlank(message = "密码不能为空") String password
) {
}
