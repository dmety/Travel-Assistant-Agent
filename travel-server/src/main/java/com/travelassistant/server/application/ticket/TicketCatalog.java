package com.travelassistant.server.application.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 字段级证据与运行规则同源；缺失或损坏时停用深度建议。 */
@Component
public class TicketCatalog {
  public record Fact(JsonNode value, String sourceUrl, java.time.LocalDate verifiedAt,
      java.time.LocalDate validFrom, java.time.LocalDate validTo, String note) {}
  public record Site(String id, String name, String kind, String officialUrl, Map<String, Fact> fields) {
    public JsonNode value(String key) {
      Fact f = fields.get(key);
      return f == null || f.sourceUrl() == null || f.verifiedAt() == null ? null : f.value();
    }
  }
  private final List<Site> sites;
  public TicketCatalog(ObjectMapper json,
      @Value("${travel.ticket-rule-file:${travel.data-directory}/ticket_rules.json}") String file) {
    List<Site> loaded;
    try {
      loaded = List.of(json.readValue(Path.of(file).toFile(), Site[].class));
      if (loaded.stream().anyMatch(s -> s == null || s.id() == null || s.name() == null
          || s.kind() == null || !java.util.Set.of("BADALING","CAPITAL","ZHONGSHAN").contains(s.kind())
          || s.fields() == null || s.fields().values().stream().anyMatch(java.util.Objects::isNull)
          || s.officialUrl() == null || !s.officialUrl().startsWith("https://"))
          || loaded.stream().map(Site::id).distinct().count() != loaded.size()) {
        loaded = List.of();
      }
    } catch (java.io.IOException | IllegalArgumentException ex) { loaded = List.of(); }
    sites = loaded;
  }
  public List<Site> all() { return sites; }
  public Site find(String name) {
    if (name == null || name.isBlank()) { return null; }
    return sites.stream().filter(s -> s.id().equals(name) || s.name().equals(name)
        || (s.kind().equals("BADALING") && name.equals("八达岭"))).findFirst().orElse(null);
  }
}
