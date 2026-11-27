package com.travelassistant.server.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/** 按实体逐行保存；复杂聚合保留 JSON，标识及关联字段可索引查询。表名只接受固定白名单。 */
final class JdbcRecordTable<T> {
  static final Set<String> TABLES = Set.of("accounts", "itineraries", "invitations",
      "comments", "changelog", "candidates", "votes", "demo_ticket_orders");
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final Class<T> type;
  private final String table;

  JdbcRecordTable(JdbcTemplate jdbc, ObjectMapper mapper, Class<T> type, String table) {
    if (!TABLES.contains(table)) throw new IllegalArgumentException("Unknown table");
    this.jdbc = jdbc; this.mapper = mapper; this.type = type; this.table = "travel_" + table;
  }
  List<T> all() { return query("1=1"); }
  List<T> query(String predicate, Object... args) {
    return jdbc.query("SELECT payload FROM " + table + " WHERE " + predicate,
        (rs, row) -> decode(rs.getString(1)), args);
  }
  Optional<T> find(String id) { return query("id = ?", id).stream().findFirst(); }
  void insert(String id, String scope, String ref, String actor, T value) {
    jdbc.update("INSERT INTO " + table + " (id, scope_id, ref_id, actor, payload) VALUES (?, ?, ?, ?, ?)",
        id, scope, ref, actor, encode(value));
  }
  void save(String id, String scope, String ref, String actor, T value) {
    jdbc.update("INSERT INTO " + table + " (id, scope_id, ref_id, actor, payload) VALUES (?, ?, ?, ?, ?) "
        + "ON DUPLICATE KEY UPDATE id = VALUES(id), payload = VALUES(payload), scope_id = VALUES(scope_id), "
        + "ref_id = VALUES(ref_id), actor = VALUES(actor)", id, scope, ref, actor, encode(value));
  }
  int delete(String predicate, Object... args) {
    return jdbc.update("DELETE FROM " + table + " WHERE " + predicate, args);
  }
  private String encode(T value) {
    try { return mapper.writeValueAsString(value); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot serialize " + table); }
  }
  private T decode(String value) {
    try { return mapper.readerFor(type).without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(value); }
    catch (java.io.IOException e) { throw new IllegalStateException("Cannot deserialize " + table); }
  }
}

