package com.travelassistant.server.application.collaboration;

/**
 * 修改记录写入端口。
 *
 * <p>让 {@code ItineraryService} 能留下操作流水，而不必知道记录存在哪里，
 * 也避免它直接依赖查询侧服务（那会形成 服务 → 记录 → 服务 的循环依赖）。
 *
 * <p>实现必须是「尽力而为」的：记录失败不能把已经落盘的写入回滚掉，
 * 否则会因为一条日志写不进让人白白丢掉一次编辑。
 */
public interface ChangeLogRecorder {

  /**
   * 追加一条修改记录。
   *
   * @param itineraryId 行程 id
   * @param actor       操作者用户名（由服务端会话提供，不接受客户端自称）
   * @param section     板块代码，如 {@code PLAN}
   * @param summary     中文摘要，说明本次改了什么
   * @param version     写入后的行程版本号
   */
  void record(String itineraryId, String actor, String section, String summary, long version);

  /** 行程删除时清掉它的修改记录。 */
  void purge(String itineraryId);
}
