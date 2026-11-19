package com.travelassistant.server.infrastructure.jumdata;

import com.travelassistant.server.domain.attraction.Attraction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Normalized scenic-spot facts returned by the Jumdata marketplace API. */
public record JumdataScenicSpot(
    String id,
    String name,
    String province,
    String city,
    String district,
    String address,
    String level,
    String openTime,
    String description,
    Double lat,
    Double lon,
    List<TicketPrice> ticketOptions,
    Instant retrievedAt,
    String informationUpdatedAt) {

  public static final String SOURCE = "聚美智数全国景点查询 API";

  public JumdataScenicSpot {
    ticketOptions = ticketOptions == null ? List.of() : List.copyOf(ticketOptions);
  }

  /** Only a ticket explicitly labelled for adults can be presented as an adult ticket. */
  public List<TicketPrice> adultTickets(LocalDate date) {
    return ticketOptions.stream()
        .filter(TicketPrice::isAdultTicket)
        .filter(ticket -> !"expired".equals(ticket.validity(date)))
        .filter(ticket -> !"not_started".equals(ticket.validity(date)))
        .toList();
  }

  public String adultTicketSummary(LocalDate date) {
    List<TicketPrice> adults = adultTickets(date);
    if (adults.isEmpty()) {
      return null;
    }
    return String.join("；", adults.stream().limit(4).map(t -> t.display(date)).toList());
  }

  public String adultTicketStatusText(LocalDate date) {
    String current = adultTicketSummary(date);
    if (current != null) {
      return current;
    }
    boolean expiredAdult = ticketOptions.stream().anyMatch(ticket -> ticket.isAdultTicket()
        && "expired".equals(ticket.validity(date)));
    return expiredAdult
        ? "当前有效成人票未返回；接口返回的成人票记录已过期"
        : "未返回/未核验";
  }

  public String ticketSummary(LocalDate date) {
    if (ticketOptions.isEmpty()) {
      return null;
    }
    return String.join("；", ticketOptions.stream().limit(5)
        .map(ticket -> ticket.display(date)).toList());
  }

  public String ticketStatus(LocalDate date) {
    if (ticketOptions.isEmpty()) {
      return "pending";
    }
    if (!adultTickets(date).isEmpty()) {
      return "unverified";
    }
    return ticketOptions.stream().anyMatch(t -> t.isAdultTicket()
        && "expired".equals(t.validity(date))) ? "expired" : "unverified";
  }

  public Attraction toAttraction() {
    return toAttraction(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
  }

  public Attraction toAttraction(LocalDate today) {
    String adultTicket = adultTicketSummary(today);
    return new Attraction(
        id == null || id.isBlank() ? "jumdata:" + name : "jumdata:" + id,
        name, city, province, "景点", normalizeLevel(level), null,
        clean(openTime), adultTicket, clean(address), null, null, null,
        clean(description), lat, lon, SOURCE, List.of(SOURCE),
        adultTicket == null ? ticketStatus(today) : "unverified",
        clean(openTime) == null ? "pending" : "unverified", null,
        retrievedAt == null ? null : retrievedAt.toString(), informationUpdatedAt);
  }

  /** A response row for chat tools; provenance and missing update time remain explicit. */
  public Map<String, Object> toFact() {
    LocalDate today = LocalDate.now();
    Map<String, Object> row = new LinkedHashMap<>();
    put(row, "id", id);
    put(row, "name", name);
    put(row, "province", province);
    put(row, "city", city);
    put(row, "district", district);
    put(row, "address", address);
    put(row, "level", normalizeLevel(level));
    put(row, "openTime", clean(openTime));
    put(row, "description", clean(description));
    put(row, "lat", lat);
    put(row, "lon", lon);
    put(row, "ticket", ticketSummary(today));
    put(row, "adultTicket", adultTicketStatusText(today));
    row.put("ticketStatus", ticketStatus(today));
    row.put("openTimeStatus", clean(openTime) == null ? "pending" : "unverified");
    row.put("levelStatus", clean(level) == null ? "pending" : "unverified");
    row.put("ticketOptions", ticketOptions.stream().map(t -> t.toFact(today)).toList());
    row.put("source", SOURCE);
    row.put("sources", List.of(SOURCE));
    row.put("ticketSource", ticketOptions.isEmpty() ? null : SOURCE);
    row.put("openTimeSource", clean(openTime) == null ? null : SOURCE);
    row.put("levelSource", clean(level) == null ? null : SOURCE);
    row.put("addressSource", clean(address) == null ? null : SOURCE);
    row.put("retrievedAt", retrievedAt == null ? null : retrievedAt.toString());
    row.put("informationUpdatedAt", informationUpdatedAt);
    row.put("informationTimeStatus", informationUpdatedAt == null ? "未返回" : "接口返回");
    return row;
  }

  private static String normalizeLevel(String raw) {
    String value = clean(raw);
    if (value == null) {
      return null;
    }
    String compact = value.toUpperCase().replace(" ", "");
    if (compact.contains("5A") || compact.contains("AAAAA")) {
      return "5A";
    }
    if (compact.contains("4A") || compact.contains("AAAA")) {
      return "4A";
    }
    if (compact.contains("3A") || compact.contains("AAA")) {
      return "3A";
    }
    return value;
  }

  private static String clean(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String trimmed = value.strip();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static void put(Map<String, Object> target, String key, Object value) {
    target.put(key, value);
  }

  public record TicketPrice(
      String category,
      String name,
      String facePrice,
      String channelPrice,
      String beginDate,
      String endDate) {

    public boolean isAdultTicket() {
      return containsAdult(category) || containsAdult(name);
    }

    public String validity(LocalDate today) {
      LocalDate begin = parseDate(beginDate);
      LocalDate end = parseDate(endDate);
      if ((beginDate != null && begin == null) || (endDate != null && end == null)) {
        return "unknown";
      }
      if (end != null && end.isBefore(today)) {
        return "expired";
      }
      if (begin != null && begin.isAfter(today)) {
        return "not_started";
      }
      return begin == null && end == null ? "unknown" : "current";
    }

    public String display(LocalDate today) {
      List<String> parts = new ArrayList<>();
      if (category != null && !category.isBlank()) {
        parts.add(category.strip());
      }
      if (name != null && !name.isBlank()
          && (category == null || !name.strip().equals(category.strip()))) {
        parts.add(name.strip());
      }
      String label = parts.isEmpty() ? "票种未标注" : String.join(" ", parts);
      List<String> prices = new ArrayList<>();
      if (facePrice != null && !facePrice.isBlank()) {
        prices.add("票面价 " + facePrice.strip() + " 元");
      }
      if (channelPrice != null && !channelPrice.isBlank()) {
        prices.add("渠道价 " + channelPrice.strip() + " 元");
      }
      if (!prices.isEmpty()) {
        label += "：" + String.join("，", prices);
      }
      if (beginDate != null || endDate != null) {
        label += "；有效期 " + (beginDate == null ? "未返回" : beginDate)
            + " 至 " + (endDate == null ? "未返回" : endDate);
      } else {
        label += "；有效期未返回";
      }
      String status = validity(today);
      if ("expired".equals(status)) {
        label += "（已过期，不作为当前票价）";
      } else if ("not_started".equals(status)) {
        label += "（尚未生效）";
      } else if ("unknown".equals(status)) {
        label += "（有效期未核验）";
      }
      return label;
    }

    private Map<String, Object> toFact(LocalDate today) {
      Map<String, Object> row = new LinkedHashMap<>();
      put(row, "category", category);
      put(row, "name", name);
      put(row, "facePrice", facePrice);
      put(row, "channelPrice", channelPrice);
      put(row, "beginDate", beginDate);
      put(row, "endDate", endDate);
      row.put("validity", validity(today));
      row.put("adultTicket", isAdultTicket());
      return row;
    }

    private static boolean containsAdult(String value) {
      return value != null && value.contains("成人");
    }

    private static LocalDate parseDate(String raw) {
      if (raw == null || raw.isBlank()) {
        return null;
      }
      try {
        return LocalDate.parse(raw.strip(), DateTimeFormatter.ofPattern("yyyy-M-d"));
      } catch (DateTimeParseException e) {
        return null;
      }
    }
  }
}
