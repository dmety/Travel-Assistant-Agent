import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:travel_mobile/models/chat_message.dart';
import 'package:travel_mobile/models/plan_view.dart';
import 'package:travel_mobile/theme/app_theme.dart';
import 'package:travel_mobile/widgets/cards.dart';
import 'package:travel_mobile/widgets/fold.dart';
import 'package:travel_mobile/widgets/message_bubble.dart';
import 'package:travel_mobile/widgets/qa_card.dart';

/// 组件级冒烟：不依赖真机与后端，验证关键卡片能构建、关键文案在。
/// 视觉问题仍要靠真机截图判断，这里只兜「渲染即崩」与「字段读错」两类。
PlanView _sampleView() => const PlanView(
      advice: null,
      notices: ['半径已按 1000 米取档'],
      sources: ['kb'],
      questions: [],
      checks: [
        PlanCheck(
          code: 'C1',
          severity: 'HIGH',
          message: '第 2 天景点跨城较远',
          suggestion: '建议合并到第 1 天',
        ),
        PlanCheck(
          code: 'C2',
          severity: 'LOW',
          message: '低优先项不该进正文',
          suggestion: '',
        ),
      ],
      candidates: [
        PlanCandidate(
          name: '武当山风景区',
          city: '十堰',
          level: '5A景区',
          rating: '4.7',
          ticket: '成人240元',
          openTime: '08:00-17:00',
        ),
        PlanCandidate(
          name: '五龙河风景区',
          city: '十堰',
          level: '4A景区',
          rating: '4.6',
          ticket: '80元',
          openTime: '08:00-18:00',
        ),
        PlanCandidate(
          name: '郧阳恐龙蛋化石群',
          city: '十堰',
          level: '4A景区',
          rating: '4.4',
          ticket: '',
          openTime: '',
        ),
        PlanCandidate(
          name: '丹江口太极湖',
          city: '十堰',
          level: '4A景区',
          rating: '4.3',
          ticket: '60元',
          openTime: '',
        ),
      ],
      dailySchedules: [
        PlanDay(dayIndex: 1, date: '2026-11-14', note: '', items: [
          PlanDayItem(
            name: '武当山风景区',
            city: '十堰',
            ticket: '成人240元',
            openTime: '08:00-17:00',
            unknowns: [],
          ),
        ]),
      ],
      dayScope: DayScope(requestedDays: 3, renderedDays: 1, trimmedDays: 2),
      status: 'OK',
      providerId: 'builtin.planning',
    );

Widget _host(Widget child) => MaterialApp(
      theme: buildAppTheme(),
      home: Scaffold(body: SingleChildScrollView(child: child)),
    );

void main() {
  testWidgets('逐日草案卡：天数、D 标、票价小字都在', (tester) async {
    await tester.pumpWidget(_host(PlanCard(view: _sampleView())));

    expect(find.text('逐日草案'), findsOneWidget);
    expect(find.text('1 天'), findsOneWidget);
    expect(find.text('D1'), findsOneWidget);
    expect(find.textContaining('武当山风景区'), findsWidgets);
    // 收敛说明：请求 3 天只排 1 天，必须有一句交代
    expect(find.textContaining('另有 2 天没有可查景点'), findsOneWidget);
  });

  testWidgets('候选卡：默认只露 3 个，其余折叠', (tester) async {
    await tester.pumpWidget(
      _host(CandList(items: _sampleView().candidates)),
    );

    expect(find.text('候选景点 · 共 4 个'), findsOneWidget);
    expect(find.text('丹江口太极湖'), findsNothing); // 第 4 个被折叠
    expect(find.text('展开其余 1 个候选'), findsOneWidget);

    await tester.tap(find.text('展开其余 1 个候选'));
    await tester.pumpAndSettle();
    expect(find.text('丹江口太极湖'), findsOneWidget);
  });

  testWidgets('检查项：LOW 不进正文，HIGH 显示建议', (tester) async {
    final actionable = CheckList.actionable(_sampleView().checks);
    expect(actionable.length, 1);

    await tester.pumpWidget(_host(CheckList(checks: actionable)));
    expect(find.textContaining('第 2 天景点跨城较远'), findsOneWidget);
    expect(find.textContaining('建议合并到第 1 天'), findsOneWidget);
    expect(find.textContaining('低优先项'), findsNothing);
  });

  testWidgets('补问卡：选天数人数后拼出一句人话', (tester) async {
    String? sentText;
    Map<String, dynamic>? sentExtra;

    await tester.pumpWidget(_host(QaCard(
      questions: const [
        PlanQuestion(code: 'Q_DAYS', field: 'days', question: '玩几天', hint: ''),
        PlanQuestion(code: 'Q_PAX', field: 'travelers', question: '几个人', hint: ''),
      ],
      baseText: '十堰行程',
      onSubmit: (text, extra) {
        sentText = text;
        sentExtra = extra;
      },
      onSkip: () {},
    )));

    await tester.tap(find.text('3天'));
    await tester.pump();
    await tester.tap(find.text('2人'));
    await tester.pump();
    await tester.tap(find.text('按这些重排'));
    await tester.pump();

    expect(sentText, '十堰行程，3 天，2 人');
    expect(sentExtra?['days'], 3);
    expect(sentExtra?['travelers'], 2);
  });

  testWidgets('补问卡：二次提交就地改写，日期预算不重复堆砌', (tester) async {
    String? sentText;

    await tester.pumpWidget(_host(QaCard(
      questions: const [
        PlanQuestion(code: 'Q_PAX', field: 'travelers', question: '几个人', hint: ''),
      ],
      // 上一轮已经拼好的原话，里面已含日期与预算
      baseText: '武汉周末两天怎么玩，2026-11-12 出发，2026-11-13 返回，预算 2000',
      onSubmit: (text, extra) => sentText = text,
      onSkip: () {},
    )));

    await tester.tap(find.text('2人'));
    await tester.pump();
    await tester.tap(find.text('按这些重排'));
    await tester.pump();

    expect(sentText, '武汉周末两天怎么玩，2026-11-12 出发，2026-11-13 返回，预算 2000，2 人');
  });

  testWidgets('消息气泡：用户靠右、助手带卡片', (tester) async {
    const user = ChatMessage(
      id: 'u1',
      role: MsgRole.user,
      text: '十堰 3 天',
      time: '14:30',
    );
    final assistant = ChatMessage(
      id: 'a1',
      role: MsgRole.assistant,
      text: '已按景区知识库给出候选与逐日草案。',
      plan: _sampleView(),
      time: '14:30',
    );

    await tester.pumpWidget(_host(Column(
      children: [
        MessageBubble(
          message: user,
          onQaSubmit: (_, __) {},
          onQaSkip: () {},
        ),
        MessageBubble(
          message: assistant,
          onQaSubmit: (_, __) {},
          onQaSkip: () {},
        ),
      ],
    )));

    expect(find.text('十堰 3 天'), findsOneWidget);
    expect(find.textContaining('已按景区知识库'), findsOneWidget);
    expect(find.text('逐日草案'), findsOneWidget);
  });

  testWidgets('补问卡拼句以用户原话为基准，不把助手正文当地名', (tester) async {
    String? sent;
    final assistant = ChatMessage(
      id: 'a2',
      role: MsgRole.assistant,
      text: '已按景区知识库给出候选与逐日草案。',
      plan: const PlanView(
        advice: '已按景区知识库给出候选与逐日草案。',
        notices: [],
        sources: [],
        questions: [
          PlanQuestion(code: 'Q_DAYS', field: 'days', question: '玩几天', hint: ''),
        ],
        checks: [],
        candidates: [],
        dailySchedules: [],
      ),
      time: '15:00',
    );

    await tester.pumpWidget(_host(MessageBubble(
      message: assistant,
      baseText: '十堰 3 天',
      onQaSubmit: (text, extra) => sent = text,
      onQaSkip: () {},
    )));

    await tester.tap(find.text('2天'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('按这些重排'));
    await tester.pumpAndSettle();

    expect(sent, isNotNull);
    expect(sent, startsWith('十堰'));
    expect(sent, contains('2 天'));
    expect(sent, isNot(contains('已按景区知识库'))); // 助手正文不能再被当成人话拼进去
  });

  group('brief：窄屏截断不留半截语义', () {
    test('括号说明整段丢弃，剩下完整核心信息就不补省略号', () {
      expect(brief('免费（听涛、磨山、马鞍山森林）', 8), '免费');
      expect(brief('70元(武汉市2026政府定价)', 8), '70元');
    });

    test('时间段被切一半时回退到上一个分隔符', () {
      expect(brief('每天 08:30-18:00,19:30-22:00', 22), '每天 08:30-18:00…');
    });

    test('普通长文本按字数截断并补省略号', () {
      expect(brief('一二三四五六七八九十', 5), '一二三四五…');
      expect(brief('短', 5), '短');
    });
  });
}
