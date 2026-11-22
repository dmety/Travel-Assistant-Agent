package com.travelassistant.server.application.agent;

import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Conservative grammar for explicitly dated, weekly and split opening intervals. No date inference. */
public final class OpeningHours {
  private OpeningHours() {}
  public enum Status { OPEN, CLOSED, UNKNOWN }
  public record Window(int startMinute, int endMinute) {}
  public record Result(Status status, List<Window> windows, String reason) {
    /** Select one continuous window; closure gaps never count as visiting time or imply re-entry. */
    public OptionalInt findStart(int arrivalMinute, int durationMinutes) {
      if(status!=Status.OPEN || durationMinutes<=0) return OptionalInt.empty();
      return windows.stream().mapToInt(w -> Math.max(arrivalMinute,w.startMinute()))
          .filter(start -> windows.stream().anyMatch(w -> start>=w.startMinute() && start+durationMinutes<=w.endMinute()))
          .min();
    }
  }
  private record Rule(MonthDay from, MonthDay to, Set<DayOfWeek> weekdays, boolean closed, List<Window> windows) {
    boolean matches(LocalDate date) {
      MonthDay d=MonthDay.from(date);
      boolean season=from==null || (from.compareTo(to)<=0 ? d.compareTo(from)>=0 && d.compareTo(to)<=0 : d.compareTo(from)>=0 || d.compareTo(to)<=0);
      return season && (weekdays.isEmpty() || weekdays.contains(date.getDayOfWeek()));
    }
  }
  private static final Pattern SEASON=Pattern.compile("(\\d{1,2})月(\\d{1,2})日[-~至](?:次年)?(\\d{1,2})月(\\d{1,2})日");
  private static final Pattern WEEK=Pattern.compile("(?:周|星期)([一二三四五六日天])(?:[-~至](?:周|星期)?([一二三四五六日天]))?");
  private static final Pattern TIME=Pattern.compile("([0-2]?\\d):([0-5]\\d)[-~至]([0-2]?\\d):([0-5]\\d)");

  public static Result evaluate(String raw, LocalDate date) {
    if(date==null) return unknown("未提供游览日期，未套用当前日期");
    if(raw==null || raw.isBlank()) return unknown("开放时间缺失");
    String text=raw.replaceAll("[\\t \u3000]+","").replace('：',':').replace('—','-').replace('–','-').replace('－','-').replace('～','~');
    if(text.matches(".*(公告|节假|临时|例外|除外|另行|为准|可能|调整|停止入场|停止售票).*")) return unknown("原文含公告、例外或未支持的入场限制，需核实");
    text=text.replaceAll("[（）()【】]","");
    text=text.replaceAll("(闭馆|闭园|不开放|休息)[，,、](?=(?:周|星期|\\d{1,2}:))","$1;");
    String[] clauses=text.split("[;；\\n]+|[，,](?=(?:每)?(?:周|星期|\\d{1,2}月))");
    List<Rule> rules=new ArrayList<>();
    try {
      for(String clause:clauses) {
        if(clause.isBlank()) continue;
        MonthDay from=null,to=null;
        Matcher season=SEASON.matcher(clause); String rest=clause;
        if(season.find()) {
          from=MonthDay.of(Integer.parseInt(season.group(1)),Integer.parseInt(season.group(2)));
          to=MonthDay.of(Integer.parseInt(season.group(3)),Integer.parseInt(season.group(4)));
          rest=season.replaceFirst("");
          if(SEASON.matcher(rest).find()) return unknown("一个条件内有多个日期范围，未猜测对应关系");
          rest=rest.replace("旺季","").replace("淡季","").replace("夏季","").replace("冬季","");
        }
        Set<DayOfWeek> weekdays=EnumSet.noneOf(DayOfWeek.class);
        Matcher week=WEEK.matcher(rest);
        while(week.find()) {
          int start=weekday(week.group(1)),end=week.group(2)==null?start:weekday(week.group(2));
          for(int i=start;;i=i%7+1) { weekdays.add(DayOfWeek.of(i)); if(i==end) break; }
        }
        rest=week.replaceAll("");
        boolean closed=rest.matches(".*(闭馆|闭园|不开放|休息).*");
        rest=rest.replaceAll("闭馆|闭园|不开放|休息","");
        List<Window> windows=new ArrayList<>();
        if(rest.contains("全天")||rest.contains("24小时")) {
          windows.add(new Window(0,1440)); rest=rest.replace("全天","").replace("24小时","");
        }
        Matcher times=TIME.matcher(rest);
        while(times.find()) {
          int start=Integer.parseInt(times.group(1))*60+Integer.parseInt(times.group(2));
          int end=Integer.parseInt(times.group(3))*60+Integer.parseInt(times.group(4));
          if(start>=1440 || end>1440 || end==start) return unknown("营业时刻无效");
          if(end<start) end+=1440;
          windows.add(new Window(start,end));
        }
        rest=times.replaceAll("").replaceAll("开放时间|营业时间|开放时段|营业时段|每日|每天|每年|全年|每|开放|营业|时间|时段|[、，,:：/]+","");
        if(!rest.isEmpty() || closed && !windows.isEmpty() || !closed && windows.isEmpty()) return unknown("开放原文存在未能解析的条件");
        rules.add(new Rule(from,to,Set.copyOf(weekdays),closed,List.copyOf(windows)));
      }
    } catch(DateTimeException|NumberFormatException e) { return unknown("日期/时刻无效，未作推断"); }
    List<Rule> matching=rules.stream().filter(rule -> rule.matches(date)).toList();
    if(matching.stream().anyMatch(Rule::closed)) return new Result(Status.CLOSED,List.of(),"原文明示此游览日闭馆/闭园");
    if(matching.isEmpty()) return unknown("原文没有覆盖此游览日的规则，不能认定闭馆或全天开放");
    if(matching.stream().filter(r -> !r.closed()).map(Rule::windows).distinct().count()>1) return unknown("多条适用开放规则不一致，未合并为更宽的时段");
    List<Window> windows=matching.stream().flatMap(r -> r.windows().stream()).sorted(Comparator.comparingInt(Window::startMinute)).toList();
    // Duplicate compatible rules are harmless; overlapping different windows are unioned, closure gaps remain.
    List<Window> merged=new ArrayList<>();
    for(Window w:windows) {
      if(!merged.isEmpty() && w.startMinute()<=merged.getLast().endMinute()) {
        Window prev=merged.removeLast(); merged.add(new Window(prev.startMinute(),Math.max(prev.endMinute(),w.endMinute())));
      } else merged.add(w);
    }
    return new Result(Status.OPEN,List.copyOf(merged),"按原文明示的游览日规则解析，尚非官方实时核验");
  }
  public static Boolean covers(String raw,LocalDate date,int startMinute,int durationMinutes) {
    if(date==null) return null;
    int dayOffset=Math.floorDiv(startMinute,1440),start=Math.floorMod(startMinute,1440);
    LocalDate actual=date.plusDays(dayOffset);
    Result today=evaluate(raw,actual);
    if(today.status()==Status.UNKNOWN) return null;
    if(today.status()==Status.CLOSED) return false;
    if(today.windows().stream().anyMatch(w -> start>=w.startMinute() && start+durationMinutes<=w.endMinute())) return true;
    Result yesterday=evaluate(raw,actual.minusDays(1));
    if(yesterday.status()==Status.UNKNOWN) return null;
    return yesterday.windows().stream().anyMatch(w -> w.endMinute()>1440 && start+1440>=w.startMinute() && start+1440+durationMinutes<=w.endMinute());
  }
  private static int weekday(String day) { return switch(day) { case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5; case "六" -> 6; default -> 7; }; }
  private static Result unknown(String reason) { return new Result(Status.UNKNOWN,List.of(),reason); }
}
