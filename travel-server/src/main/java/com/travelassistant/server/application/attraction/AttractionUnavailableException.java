package com.travelassistant.server.application.attraction;

/**
 * 景点知识库不可用（文件缺失、损坏或结构异常）。
 *
 * <p>抛出后由 {@link com.travelassistant.server.interfaces.rest.AttractionController}
 * 转成 HTTP 503，并把 {@link #getMessage()} 作为原因返回给调用方，
 * 让用户知道"查不到是因为库没加载成功"，而不是误以为"库里没有这个景点"。
 */
public class AttractionUnavailableException extends RuntimeException {

  public AttractionUnavailableException(String reason) {
    super(reason);
  }
}
