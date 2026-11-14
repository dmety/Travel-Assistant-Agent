import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/tokens.dart';

/// 游小满 v4.1 正脸差分资源前缀。
///
/// 与 `design/export/png/`、`static/mascot/` 同源；五帧用于 appbar 活头像，
/// `front-bust` 用于 AI 回复的署名头像。改素材时三处要一起换。
const String _kMascotAsset = 'assets/mascot/mascot-v4-front-';

/// 点击情绪循环：平常 → 害羞 → 不耐烦 → 生气 → 平常。
enum MascotMood {
  idle('平常', 'idle'),
  shy('害羞', 'shy'),
  impatient('不耐烦', 'impatient'),
  angry('生气', 'angry');

  const MascotMood(this.label, this.frame);
  final String label;
  final String frame;
}

/// AI 回复气泡里的静态正脸头像。
///
/// 设计稿要求「头像一律正脸」（`kanban.html` §04c），侧脸只留给登录页。
/// 这里用 `front-bust` 裁切，不复用 appbar 的活头像，避免每条消息都挂一套计时器。
class MascotBust extends StatelessWidget {
  const MascotBust({super.key, this.size = 28});

  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: size,
      height: size,
      decoration: const BoxDecoration(
        color: T.pineSoft,
        shape: BoxShape.circle,
      ),
      clipBehavior: Clip.antiAlias,
      child: Image.asset(
        '${_kMascotAsset}bust.png',
        fit: BoxFit.cover,
        filterQuality: FilterQuality.medium,
        errorBuilder: (context, error, stack) => Icon(
          Icons.person_outline,
          size: size * 0.6,
          color: T.pine,
        ),
      ),
    );
  }
}

/// appbar 上的活头像。
///
/// 对齐 `design/mascot-rig.js` 的契约：呼吸、随机眨眼（2.6–5.4 秒、停 130ms）、
/// 点击情绪循环（生气抖 500ms）、8 秒无操作自动平复，帧间 240ms 交叉淡入。
/// 发送成功后把 [nodToken] 加一，头像会点一次头——与设计稿「发送后点头应一声」一致。
///
/// 只当对话化身与语气层，不占主内容区、不盖地图点位（`kanban.html` §06b）。
class MascotAvatar extends StatefulWidget {
  const MascotAvatar({
    super.key,
    this.size = 46,
    this.nodToken = 0,
    this.onMoodChanged,
  });

  final double size;

  /// 自增即可触发一次点头；值本身无意义，只看变化。
  final int nodToken;
  final ValueChanged<MascotMood>? onMoodChanged;

  @override
  State<MascotAvatar> createState() => _MascotAvatarState();
}

class _MascotAvatarState extends State<MascotAvatar>
    with TickerProviderStateMixin {
  static const List<String> _frames = [
    'idle',
    'blink',
    'shy',
    'impatient',
    'angry',
  ];

  late final AnimationController _breathe;
  late final AnimationController _nod;
  late final AnimationController _shake;

  Timer? _blinkTimer;
  Timer? _blinkOffTimer;
  Timer? _resetTimer;

  bool _blinking = false;
  MascotMood _mood = MascotMood.idle;
  final math.Random _random = math.Random();

  @override
  void initState() {
    super.initState();
    _breathe = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 2800),
    )..repeat(reverse: true);
    _nod = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 420),
    );
    _shake = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 500),
    );
    _scheduleBlink();
  }

  @override
  void didUpdateWidget(covariant MascotAvatar oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.nodToken != widget.nodToken) {
      _nod.forward(from: 0);
    }
  }

  @override
  void dispose() {
    _blinkTimer?.cancel();
    _blinkOffTimer?.cancel();
    _resetTimer?.cancel();
    _breathe.dispose();
    _nod.dispose();
    _shake.dispose();
    super.dispose();
  }

  void _scheduleBlink() {
    _blinkTimer?.cancel();
    final delayMs = 2600 + _random.nextInt(2800);
    _blinkTimer = Timer(Duration(milliseconds: delayMs), () {
      if (!mounted) return;
      setState(() => _blinking = true);
      _blinkOffTimer?.cancel();
      _blinkOffTimer = Timer(const Duration(milliseconds: 130), () {
        if (!mounted) return;
        setState(() => _blinking = false);
        _scheduleBlink();
      });
    });
  }

  void _poke() {
    MascotMood next;
    switch (_mood) {
      case MascotMood.idle:
        next = MascotMood.shy;
        break;
      case MascotMood.shy:
        next = MascotMood.impatient;
        break;
      case MascotMood.impatient:
        next = MascotMood.angry;
        break;
      case MascotMood.angry:
        next = MascotMood.idle;
        break;
    }
    setState(() => _mood = next);
    widget.onMoodChanged?.call(next);
    if (next == MascotMood.angry) {
      _shake.forward(from: 0);
    }
    _resetTimer?.cancel();
    if (next != MascotMood.idle) {
      _resetTimer = Timer(const Duration(seconds: 8), () {
        if (!mounted) return;
        setState(() => _mood = MascotMood.idle);
        widget.onMoodChanged?.call(MascotMood.idle);
      });
    }
  }

  String get _currentFrame =>
      _blinking ? 'blink' : _mood.frame;

  @override
  Widget build(BuildContext context) {
    final avatar = Container(
      width: widget.size,
      height: widget.size,
      decoration: const BoxDecoration(
        color: T.pineSoft,
        shape: BoxShape.circle,
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(
        fit: StackFit.expand,
        children: [
          for (final frame in _frames)
            AnimatedOpacity(
              opacity: frame == _currentFrame ? 1 : 0,
              duration: const Duration(milliseconds: 240),
              child: Image.asset(
                '$_kMascotAsset$frame.png',
                fit: BoxFit.cover,
                filterQuality: FilterQuality.medium,
                errorBuilder: (context, error, stack) => const SizedBox.shrink(),
              ),
            ),
        ],
      ),
    );

    return Semantics(
      button: true,
      label: '看板娘 游小满 · ${_mood.label}（点一下换表情）',
      child: GestureDetector(
        onTap: _poke,
        child: AnimatedBuilder(
          animation: Listenable.merge([_breathe, _nod, _shake]),
          builder: (context, child) {
            final scale = 1.0 + 0.02 * _breathe.value;
            final nodY = 3.0 * math.sin(math.pi * _nod.value);
            final shakeX = _shake.isAnimating
                ? 2.0 *
                    math.sin(_shake.value * math.pi * 6) *
                    (1 - _shake.value)
                : 0.0;
            return Transform.translate(
              offset: Offset(shakeX, nodY),
              child: Transform.scale(scale: scale, child: child),
            );
          },
          child: avatar,
        ),
      ),
    );
  }
}
