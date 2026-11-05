package com.travelassistant.server.interfaces.rest.dto;

/** 发表留言的请求体。内容校验在服务层做，以便返回具体的中文原因。 */
public record CommentRequest(String content) {
}
