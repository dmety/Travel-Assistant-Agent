import 'dart:convert';

/// 契约信封的视图模型。
///
/// 字段名严格对齐 Web 端 `slimDraft()` 与 `draftFromEnvelope()`：
/// 后端景点 record 的 JSON 键是 `open_time`，而日程条目里是 `openTime`——
/// 这个不一致是既有契约，两个键都必须兼容，否则票价/开放时间会全空。
class PlanView {
  final String? advice;
  final List<String> notices;
  final List<String> sources;
  final List<PlanQuestion> questions;
  final List<PlanCheck> checks;
  final List<PlanCandidate> candidates;
  final List<PlanDay> dailySchedules;
  final DayScope? dayScope;
  final String? budgetEstimate;
  final String? generatedAt;

  // 编排层元信息
  final String providerId;
  final String status;
  final List<Attempt> attempts;

  const PlanView({
    this.advice,
    required this.notices,
    required this.sources,
    required this.questions,
    required this.checks,
    required this.candidates,
    required this.dailySchedules,
    this.dayScope,
    this.budgetEstimate,
    this.generatedAt,
    this.providerId = '',
    this.status = '',
    this.attempts = const [],
  });

  /// 是否“什么都没给”：编排层 REFUSED 且无正文无候选，此时不该渲染空卡片。
  bool get isRefused =>
      status == 'REFUSED' && (advice == null || advice!.isEmpty) && candidates.isEmpty;

  /// 排出来的实际天数（只算有内容的）。
  int get filledDays =>
      dailySchedules.where((d) => d.items.isNotEmpty).length;

  /// 收敛掉的天数：请求天数 - 实际排出天数，用于“未硬凑空天”那句说明。
  int get trimmedDays {
    final scope = dayScope;
    if (scope != null && scope.trimmedDays > 0) return scope.trimmedDays;
    return 0;
  }

  /// 从 `/api/agent/invoke` 的契约信封解析。
  static PlanView? fromEnvelope(Map<String, dynamic> env) {
    final result = env['result'];
    if (result is! Map) return null;
    final payload = Map<String, dynamic>.from(result);

    Map<String, dynamic>? draft;
    final facts = payload['facts'];
    if (facts is List) {
      for (final f in facts) {
        if (f is! Map) continue;
        if (f['type'] != 'CONTENT') continue;
        final v = f['value'];
        if (v is Map && v['category'] == 'PLAN_DRAFT') {
          draft = Map<String, dynamic>.from(v);
          break;
        }
      }
    }

    final v = draft;
    final view = PlanView(
      advice: _str(v?['advice']) ?? _str(payload['advice']),
      notices: _list(v?['notices'], 5),
      sources: _list(v?['sources'], 20),
      questions: _questions(v?['questions']),
      checks: _checks(v?['checks']),
      candidates: _candidates(v?['candidates']),
      dailySchedules: _days(v?['dailySchedules']),
      dayScope: DayScope.from(v?['dayScope']),
      budgetEstimate: _str(v?['budgetEstimate']),
      generatedAt: _str(v?['generatedAt']),
      providerId: _str(env['providerId']) ?? _str(payload['providerId']) ?? '',
      status: _str(env['status']) ?? _str(payload['status']) ?? '',
      attempts: _attempts(env['attempts']),
    );
    return view;
  }

  static String? _str(dynamic v) {
    if (v == null) return null;
    final s = v.toString();
    return s.isEmpty ? null : s;
  }

  static List<String> _list(dynamic v, int cap) {
    if (v is! List) return const [];
    return v.take(cap).map((e) => e.toString()).toList();
  }

  static List<PlanQuestion> _questions(dynamic v) {
    if (v is! List) return const [];
    return v.whereType<Map>().map((m) {
      final q = Map<String, dynamic>.from(m);
      return PlanQuestion(
        code: _str(q['code']) ?? '',
        field: _str(q['field']) ?? '',
        question: _str(q['question']) ?? '',
        hint: _str(q['hint']) ?? '',
      );
    }).toList();
  }

  static List<PlanCheck> _checks(dynamic v) {
    if (v is! List) return const [];
    return v.whereType<Map>().map((m) {
      final c = Map<String, dynamic>.from(m);
      return PlanCheck(
        code: _str(c['code']) ?? '',
        severity: _str(c['severity']) ?? '',
        message: _str(c['message']) ?? '',
        suggestion: _str(c['suggestion']) ?? '',
      );
    }).toList();
  }

  static List<PlanCandidate> _candidates(dynamic v) {
    if (v is! List) return const [];
    return v.take(8).whereType<Map>().map((m) {
      final a = Map<String, dynamic>.from(m);
      return PlanCandidate(
        name: _str(a['name']) ?? '',
        city: _str(a['city']) ?? '',
        level: _str(a['level']) ?? '',
        rating: a['rating']?.toString() ?? '',
        ticket: _str(a['ticket']) ?? '',
        // 候选 record 用 snake_case
        openTime: _str(a['open_time']) ?? _str(a['openTime']) ?? '',
      );
    }).toList();
  }

  static List<PlanDay> _days(dynamic v) {
    if (v is! List) return const [];
    return v.whereType<Map>().map((m) {
      final d = Map<String, dynamic>.from(m);
      final items = d['items'];
      final list = <PlanDayItem>[];
      if (items is List) {
        for (final i in items) {
          if (i is! Map) continue;
          final it = Map<String, dynamic>.from(i);
          list.add(PlanDayItem(
            name: _str(it['name']) ?? '',
            city: _str(it['city']) ?? '',
            ticket: _str(it['ticket']) ?? '',
            // 日程条目的键是 camelCase
            openTime: _str(it['openTime']) ?? _str(it['open_time']) ?? '',
            unknowns: _list(it['unknowns'], 10),
          ));
        }
      }
      return PlanDay(
        dayIndex: (d['dayIndex'] is num) ? (d['dayIndex'] as num).toInt() : 0,
        date: _str(d['date']) ?? '',
        note: _str(d['note']) ?? '',
        items: list,
      );
    }).toList();
  }

  static List<Attempt> _attempts(dynamic v) {
    if (v is! List) return const [];
    return v.whereType<Map>().map((m) {
      final a = Map<String, dynamic>.from(m);
      return Attempt(
        providerId: _str(a['providerId']) ?? '',
        reason: _str(a['reason']) ?? '',
      );
    }).toList();
  }
}

class DayScope {
  final int requestedDays;
  final int renderedDays;
  final int trimmedDays;
  const DayScope({
    this.requestedDays = 0,
    this.renderedDays = 0,
    this.trimmedDays = 0,
  });

  static DayScope? from(dynamic v) {
    if (v is! Map) return null;
    int n(dynamic x) => (x is num) ? x.toInt() : 0;
    return DayScope(
      requestedDays: n(v['requestedDays']),
      renderedDays: n(v['renderedDays']),
      trimmedDays: n(v['trimmedDays']),
    );
  }
}

class PlanQuestion {
  final String code;
  final String field;
  final String question;
  final String hint;
  const PlanQuestion({
    required this.code,
    required this.field,
    required this.question,
    required this.hint,
  });
}

class PlanCheck {
  final String code;
  final String severity;
  final String message;
  final String suggestion;
  const PlanCheck({
    required this.code,
    required this.severity,
    required this.message,
    required this.suggestion,
  });

  bool get isHigh => severity == 'HIGH';
  bool get isMed => severity == 'MEDIUM';
  bool get isActionable => isHigh || isMed;
}

class PlanCandidate {
  final String name;
  final String city;
  final String level;
  final String rating;
  final String ticket;
  final String openTime;
  const PlanCandidate({
    required this.name,
    required this.city,
    required this.level,
    required this.rating,
    required this.ticket,
    required this.openTime,
  });
}

class PlanDay {
  final int dayIndex;
  final String date;
  final String note;
  final List<PlanDayItem> items;
  const PlanDay({
    required this.dayIndex,
    required this.date,
    required this.note,
    required this.items,
  });
}

class PlanDayItem {
  final String name;
  final String city;
  final String ticket;
  final String openTime;
  final List<String> unknowns;
  const PlanDayItem({
    required this.name,
    required this.city,
    required this.ticket,
    required this.openTime,
    required this.unknowns,
  });
}

class Attempt {
  final String providerId;
  final String reason;
  const Attempt({required this.providerId, required this.reason});
}

/// 美食推荐视图：`FOOD_RECOMMEND` 的 fact 是 `CONTENT` + `category=FOOD`。
class FoodView {
  final List<FoodItem> items;
  final String? advice;
  final List<String> notices;
  const FoodView({
    required this.items,
    this.advice,
    required this.notices,
  });

  static FoodView? fromEnvelope(Map<String, dynamic> env) {
    final result = env['result'];
    if (result is! Map) return null;
    final payload = Map<String, dynamic>.from(result);
    final items = <FoodItem>[];
    final facts = payload['facts'];
    if (facts is List) {
      for (final f in facts) {
        if (f is! Map || f['type'] != 'CONTENT') continue;
        final v = f['value'];
        if (v is! Map || v['category'] != 'FOOD') continue;
        items.add(FoodItem(
          name: (v['name'] ?? '').toString(),
          slot: (v['mealSlot'] ?? '').toString(),
          price: v['pricePerPersonCny']?.toString() ?? '',
          distance: v['distanceMeters'],
          rating: v['rating']?.toString() ?? '',
          tags: (v['tags'] is List)
              ? (v['tags'] as List).take(3).map((e) => e.toString()).toList()
              : const [],
          locality: (v['locality'] ?? '').toString(),
        ));
      }
    }
    final notices = payload['notices'];
    return FoodView(
      items: items,
      advice: (payload['advice'] as String?)?.trim().isEmpty ?? true
          ? null
          : payload['advice'] as String,
      notices: notices is List
          ? notices.take(5).map((e) => e.toString()).toList()
          : const [],
    );
  }
}

class FoodItem {
  final String name;
  final String slot;
  final String price;
  final dynamic distance;
  final String rating;
  final List<String> tags;
  final String locality;
  const FoodItem({
    required this.name,
    required this.slot,
    required this.price,
    required this.distance,
    required this.rating,
    required this.tags,
    required this.locality,
  });

  String get distanceText {
    final d = distance;
    if (d is num) return '${d.round()} 米';
    if (d != null) return d.toString();
    return '';
  }
}

/// 闲聊/工具卡片（`POST /api/chat` 的 `cards[]`）。
class ToolCard {
  final String type; // weather / places / vote / envelope
  final String intent;
  final dynamic data;
  const ToolCard({required this.type, required this.intent, required this.data});

  static List<ToolCard> listFrom(dynamic v) {
    if (v is! List) return const [];
    return v.whereType<Map>().map((m) {
      return ToolCard(
        type: (m['type'] ?? '').toString(),
        intent: (m['intent'] ?? '').toString(),
        data: m['data'],
      );
    }).toList();
  }
}

/// `POST /api/intent/parse` 的解析结果。
class IntentView {
  final String text;
  final String intent;
  final String? destination;
  final bool resolved;
  final String? city;
  final String? spot;
  final String? note;
  final List<String> alternatives;
  final int? days;
  final int? travelers;
  final num? budget;
  final List<String> preferences;
  final String? departDate;
  final String? returnDate;
  final String? reply;
  final String source;

  const IntentView({
    required this.text,
    required this.intent,
    this.destination,
    required this.resolved,
    this.city,
    this.spot,
    this.note,
    required this.alternatives,
    this.days,
    this.travelers,
    this.budget,
    required this.preferences,
    this.departDate,
    this.returnDate,
    this.reply,
    this.source = '',
  });

  static IntentView from(Map<String, dynamic> m) {
    int? asInt(dynamic v) => (v is num) ? v.toInt() : null;
    String? asStr(dynamic v) {
      if (v == null) return null;
      final s = v.toString();
      return s.isEmpty ? null : s;
    }

    return IntentView(
      text: (m['text'] ?? '').toString(),
      intent: (m['intent'] ?? 'PLAN_DRAFT').toString(),
      destination: asStr(m['destination']),
      resolved: m['resolved'] == true,
      city: asStr(m['city']),
      spot: asStr(m['spot']),
      note: asStr(m['note']),
      alternatives: (m['alternatives'] is List)
          ? (m['alternatives'] as List).map((e) => e.toString()).toList()
          : const [],
      days: asInt(m['days']),
      travelers: asInt(m['travelers']),
      budget: (m['budget'] is num) ? m['budget'] as num : null,
      preferences: (m['preferences'] is List)
          ? (m['preferences'] as List).map((e) => e.toString()).toList()
          : const [],
      departDate: asStr(m['departDate']),
      returnDate: asStr(m['returnDate']),
      reply: asStr(m['reply']),
      source: (m['source'] ?? '').toString(),
    );
  }

  /// 解析结果 → `/api/agent/invoke` 的 context。
  /// 点选补问卡带来的结构化字段优先于解析值（与 Web 的 applyExtra 同义）。
  Map<String, dynamic> toContext({Map<String, dynamic>? extra}) {
    final ctx = <String, dynamic>{};
    if (city != null) ctx['destination'] = city;
    if (days != null) ctx['days'] = days;
    if (travelers != null) ctx['travelers'] = travelers;
    if (budget != null) ctx['budget'] = budget;
    if (departDate != null) ctx['departDate'] = departDate;
    if (returnDate != null) ctx['returnDate'] = returnDate;
    if (preferences.isNotEmpty) ctx['preferences'] = preferences;
    if (extra != null) {
      for (final e in extra.entries) {
        if (e.value == null || e.value == '') continue;
        ctx[e.key] = e.value;
      }
    }
    return ctx;
  }

  String toJson() => jsonEncode({
        'text': text,
        'intent': intent,
        'resolved': resolved,
        'city': city,
      });
}

/// `GET /api/account/me` 的最小视图。
class AccountView {
  final String username;
  final List<String> roles;
  const AccountView({required this.username, required this.roles});

  static AccountView? from(dynamic v) {
    if (v is! Map) return null;
    final name = v['username']?.toString();
    if (name == null || name.isEmpty) return null;
    return AccountView(
      username: name,
      roles: (v['roles'] is List)
          ? (v['roles'] as List).map((e) => e.toString()).toList()
          : const [],
    );
  }
}
