import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/routes/app_routes.dart';
import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../core/widgets/status_badge.dart';
import '../../../data/models/alarm_report.dart';
import '../controllers/alarms_controller.dart';

class AlarmsPage extends GetView<AlarmsController> {
  const AlarmsPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && controller.report.value == null) {
          return const LoadingState(label: 'Chargement des alarmes...');
        }

        if (controller.errorMessage.value != null && controller.report.value == null) {
          return ErrorState(message: controller.errorMessage.value!, onRetry: controller.load);
        }

        return RefreshIndicator(
          onRefresh: controller.refreshData,
          child: CustomScrollView(
            slivers: [
              _AlarmsHeader(controller: controller),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(26, 16, 26, 26),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
  _GlobalSummaryCards(controller: controller),
  const SizedBox(height: 16),
  _SectionKpiOverview(controller: controller),
  const SizedBox(height: 20),
  ...controller.sections.map(
    (section) => _ReactiveAlarmSectionCard(
      controller: controller,
      section: section,
    ),
  ),
  _ReactiveAlarmHistoryCard(controller: controller),
]),
                ),
              ),
            ],
          ),
        );
      }),
    );
  }
}

class _AlarmsHeader extends StatelessWidget {
  const _AlarmsHeader({required this.controller});

  final AlarmsController controller;

  @override
  Widget build(BuildContext context) {
    return SliverAppBar(
      pinned: true,
      automaticallyImplyLeading: false,
      expandedHeight: 146,
      toolbarHeight: 146,
      backgroundColor: AppColors.softWhite,
      surfaceTintColor: Colors.transparent,
      flexibleSpace: Padding(
        padding: const EdgeInsets.fromLTRB(26, 24, 26, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Alarmes',
                    style: TextStyle(
                      color: AppColors.midnightNavy,
                      fontSize: 34,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -1.1,
                    ),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Vue globale, alarmes actives par condition et historique des alarmes résolues.',
                    style: TextStyle(
                      color: AppColors.mutedText,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Obx(
                    () => Text(
                      'Dernière mise à jour · auto · ${Formatters.dateTime(controller.lastRefresh.value)}',
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: AppColors.mutedText,
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Obx(
              () => IconButton.filledTonal(
                tooltip: 'Mettre à jour maintenant',
                onPressed: controller.isRefreshing.value ? null : controller.refreshData,
                icon: controller.isRefreshing.value
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.refresh_rounded),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GlobalSummaryCards extends StatelessWidget {
  const _GlobalSummaryCards({required this.controller});

  final AlarmsController controller;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth >= 780 ? (constraints.maxWidth - 24) / 3 : constraints.maxWidth;

        return Wrap(
          spacing: 12,
          runSpacing: 12,
          children: [
            SizedBox(
              width: width,
              child: Obx(
                () => _MetricCard(
                  icon: Icons.notification_important_rounded,
                  title: 'Alarmes actives',
                  value: controller.activeCount.toString(),
                  accent: AppColors.faulty,
                ),
              ),
            ),
            SizedBox(
              width: width,
              child: Obx(
                () => _MetricCard(
                  icon: Icons.history_rounded,
                  title: 'Historique résolu',
                  value: controller.historyCount.toString(),
                  accent: AppColors.deepTeal,
                ),
              ),
            ),
            SizedBox(
              width: width,
              child: const _MetricCard(
                icon: Icons.rule_rounded,
                title: 'Conditions surveillées',
                value: '3',
                accent: AppColors.warning,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _SectionKpiOverview extends StatelessWidget {
  const _SectionKpiOverview({required this.controller});

  final AlarmsController controller;

  @override
  Widget build(BuildContext context) {
    final activeTotal = controller.activeCount;
    final totalWithHistory = controller.activeCount + controller.historyCount;

    final cards = <Widget>[
      ...controller.sections.map(
        (section) {
          final ratio = activeTotal <= 0 ? 0.0 : section.count / activeTotal;
          return _SectionKpiCard(
            title: _shortSectionTitle(section.type, section.title),
            subtitle: 'Part des alarmes actives',
            count: section.count,
            ratio: ratio,
            icon: _typeIcon(section.type),
            accent: _typeAccent(section.type),
          );
        },
      ),
      _SectionKpiCard(
        title: 'Historique',
        subtitle: 'Alarmes résolues',
        count: controller.historyCount,
        ratio: totalWithHistory <= 0 ? 0.0 : controller.historyCount / totalWithHistory,
        icon: Icons.history_rounded,
        accent: AppColors.deepTeal,
      ),
    ];

    return PremiumCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Vue globale par condition',
            style: TextStyle(
              color: AppColors.midnightNavy,
              fontSize: 20,
              fontWeight: FontWeight.w900,
              letterSpacing: -0.3,
            ),
          ),
          const SizedBox(height: 6),
          const Text(
            'Chaque camembert montre le poids de la section par rapport aux alarmes actives.',
            style: TextStyle(
              color: AppColors.mutedText,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 16),
          LayoutBuilder(
            builder: (context, constraints) {
              final width = constraints.maxWidth >= 1100
                  ? (constraints.maxWidth - 36) / 4
                  : constraints.maxWidth >= 760
                      ? (constraints.maxWidth - 12) / 2
                      : constraints.maxWidth;

              return Wrap(
                spacing: 12,
                runSpacing: 12,
                children: cards.map((card) => SizedBox(width: width, child: card)).toList(),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _SectionKpiCard extends StatelessWidget {
  const _SectionKpiCard({
    required this.title,
    required this.subtitle,
    required this.count,
    required this.ratio,
    required this.icon,
    required this.accent,
  });

  final String title;
  final String subtitle;
  final int count;
  final double ratio;
  final IconData icon;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    final safeRatio = ratio.clamp(0.0, 1.0);
    final percent = safeRatio * 100;

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.white,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Row(
        children: [
          _MiniPieChart(
            ratio: safeRatio,
            color: accent,
            centerIcon: icon,
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  subtitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const SizedBox(height: 10),
                Row(
                  children: [
                    Text(
                      count.toString(),
                      style: TextStyle(
                        color: accent,
                        fontSize: 24,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        '${Formatters.decimal(percent, decimals: 1)}%',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: AppColors.slateGraphite,
                          fontSize: 12,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _MiniPieChart extends StatelessWidget {
  const _MiniPieChart({
    required this.ratio,
    required this.color,
    required this.centerIcon,
  });

  final double ratio;
  final Color color;
  final IconData centerIcon;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 64,
      height: 64,
      child: Stack(
        alignment: Alignment.center,
        children: [
          CustomPaint(
            size: const Size(64, 64),
            painter: _PiePainter(
              ratio: ratio,
              color: color,
            ),
          ),
          Container(
            width: 34,
            height: 34,
            decoration: const BoxDecoration(
              color: AppColors.white,
              shape: BoxShape.circle,
            ),
            child: Icon(centerIcon, color: color, size: 18),
          ),
        ],
      ),
    );
  }
}

class _PiePainter extends CustomPainter {
  const _PiePainter({
    required this.ratio,
    required this.color,
  });

  final double ratio;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final safeRatio = ratio.clamp(0.0, 1.0);
    final rect = Offset.zero & size;

    final backgroundPaint = Paint()
      ..color = AppColors.borderGray.withOpacity(0.65)
      ..style = PaintingStyle.fill;

    final valuePaint = Paint()
      ..color = color
      ..style = PaintingStyle.fill;

    canvas.drawCircle(size.center(Offset.zero), size.width / 2, backgroundPaint);

    if (safeRatio > 0) {
      canvas.drawArc(
        rect,
        -math.pi / 2,
        safeRatio * 2 * math.pi,
        true,
        valuePaint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant _PiePainter oldDelegate) {
    return oldDelegate.ratio != ratio || oldDelegate.color != color;
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({
    required this.icon,
    required this.title,
    required this.value,
    required this.accent,
  });

  final IconData icon;
  final String title;
  final String value;
  final Color accent;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Row(
        children: [
          Container(
            width: 46,
            height: 46,
            decoration: BoxDecoration(
              color: accent.withOpacity(0.10),
              borderRadius: BorderRadius.circular(16),
            ),
            child: Icon(icon, color: accent),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontSize: 12,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  value,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 26,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ReactiveAlarmSectionCard extends StatelessWidget {
  const _ReactiveAlarmSectionCard({
    required this.controller,
    required this.section,
  });

  final AlarmsController controller;
  final AlarmSection section;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final pageKey = controller.sectionPageKey(section);

      // Lecture directe d'une Rx dans le callback Obx.
      // C'est ça qui évite l'erreur GetX improper use.
      final page = controller.pageFor(pageKey);

      return Padding(
        key: ValueKey('alarm-section-${section.type}-$page-${section.alarms.length}'),
        padding: const EdgeInsets.only(bottom: 18),
        child: _AlarmSectionCard(
          controller: controller,
          section: section,
        ),
      );
    });
  }
}

class _ReactiveAlarmHistoryCard extends StatelessWidget {
  const _ReactiveAlarmHistoryCard({
    required this.controller,
  });

  final AlarmsController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final pageKey = controller.historyPageKey;

      // Lecture directe d'une Rx dans le callback Obx.
      final page = controller.pageFor(pageKey);
      final historyCount = controller.history.length;

      return _AlarmHistoryCard(
        key: ValueKey('alarm-history-$page-$historyCount'),
        controller: controller,
      );
    });
  }
}

class _AlarmSectionCard extends StatelessWidget {
  const _AlarmSectionCard({
    required this.controller,
    required this.section,
  });

  final AlarmsController controller;
  final AlarmSection section;

  @override
  Widget build(BuildContext context) {
    final pageKey = controller.sectionPageKey(section);
    final page = controller.pageFor(pageKey);
    final pagedAlarms = controller.pagedSectionAlarms(section);

    return PremiumCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: _typeAccent(section.type).withOpacity(0.10),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Icon(_typeIcon(section.type), color: _typeAccent(section.type)),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      section.title,
                      style: const TextStyle(
                        color: AppColors.midnightNavy,
                        fontSize: 20,
                        fontWeight: FontWeight.w900,
                        letterSpacing: -0.3,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      section.description,
                      style: const TextStyle(
                        color: AppColors.mutedText,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ],
                ),
              ),
              _CountPill(count: section.count),
            ],
          ),
          const SizedBox(height: 16),
          if (section.alarms.isEmpty)
            const _NoAlarmLine()
          else ...[
            ...pagedAlarms.map(
              (alarm) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _AlarmTile(alarm: alarm),
              ),
            ),
            _PaginationBar(
              page: page,
              pageSize: AlarmsController.pageSize,
              itemCount: section.alarms.length,
              startIndex: controller.startIndexFor(pageKey),
              endIndex: controller.endIndexFor(pageKey, section.alarms.length),
              onPrevious: () => controller.previousPage(pageKey),
              onNext: () => controller.nextPage(pageKey, section.alarms.length),
            ),
          ],
        ],
      ),
    );
  }
}

class _AlarmTile extends StatelessWidget {
  const _AlarmTile({required this.alarm, this.compact = false});

  final AlarmItem alarm;
  final bool compact;

  @override
  Widget build(BuildContext context) {
    final accent = _severityColor(alarm.severity);

    return Material(
      color: Colors.transparent,
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () => Get.toNamed(
          AppRoutes.siteDetailsPath(brand: alarm.brand, siteCode: alarm.siteCode),
          arguments: {'brand': alarm.brand, 'siteCode': alarm.siteCode},
        ),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            color: AppColors.softWhite,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: accent.withOpacity(0.22)),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: accent.withOpacity(0.12),
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Icon(_typeIcon(alarm.type), color: accent, size: 20),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          alarm.displaySite,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: AppColors.midnightNavy,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          alarm.message,
                          style: const TextStyle(
                            color: AppColors.slateGraphite,
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 10),
                  StatusBadge(status: alarm.siteStatus, compact: true),
                ],
              ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  _InfoChip(label: 'Gouv.', value: alarm.governorate),
                  if (alarm.metricValue != null)
                    _InfoChip(label: _metricLabel(alarm.type), value: _metricValue(alarm)),
                  if (alarm.bestSiteName != null && alarm.bestSiteName!.isNotEmpty)
                    _InfoChip(label: 'Meilleur', value: alarm.bestSiteName!),
                  if (alarm.monitoredDate != null)
                    _InfoChip(label: 'Jour', value: Formatters.date(alarm.monitoredDate)),
                  if (alarm.lastMonitoringAt != null)
                    _InfoChip(label: 'Dernier monitoring', value: Formatters.dateTime(alarm.lastMonitoringAt)),
                  if (!compact && alarm.firstDetectedAt != null)
                    _InfoChip(label: 'Début', value: Formatters.dateTime(alarm.firstDetectedAt)),
                  if (alarm.clearedAt != null)
                    _InfoChip(label: 'Résolu', value: Formatters.dateTime(alarm.clearedAt)),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _AlarmHistoryCard extends StatelessWidget {
  const _AlarmHistoryCard({required this.controller, required ValueKey<String> key});

  final AlarmsController controller;

  @override
  Widget build(BuildContext context) {
    final pageKey = controller.historyPageKey;
    final page = controller.pageFor(pageKey);
    final pagedHistory = controller.pagedHistory();

    return PremiumCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Historique des alarmes non actives',
            style: TextStyle(
              color: AppColors.midnightNavy,
              fontSize: 20,
              fontWeight: FontWeight.w900,
              letterSpacing: -0.3,
            ),
          ),
          const SizedBox(height: 6),
          const Text(
            'Une alarme résolue reste ici avec sa date de début et sa date de résolution.',
            style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 16),
          if (controller.history.isEmpty)
            const _NoAlarmLine(message: 'Aucune alarme résolue pour le moment.')
          else ...[
            ...pagedHistory.map(
              (alarm) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _AlarmTile(alarm: alarm, compact: true),
              ),
            ),
            _PaginationBar(
              page: page,
              pageSize: AlarmsController.pageSize,
              itemCount: controller.history.length,
              startIndex: controller.startIndexFor(pageKey),
              endIndex: controller.endIndexFor(pageKey, controller.history.length),
              onPrevious: () => controller.previousPage(pageKey),
              onNext: () => controller.nextPage(pageKey, controller.history.length),
            ),
          ],
        ],
      ),
    );
  }
}

class _PaginationBar extends StatelessWidget {
  const _PaginationBar({
    required this.page,
    required this.pageSize,
    required this.itemCount,
    required this.startIndex,
    required this.endIndex,
    required this.onPrevious,
    required this.onNext,
  });

  final int page;
  final int pageSize;
  final int itemCount;
  final int startIndex;
  final int endIndex;
  final VoidCallback onPrevious;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    if (itemCount <= pageSize) {
      return const SizedBox.shrink();
    }

    final maxPage = (itemCount - 1) ~/ pageSize;
    final canPrevious = page > 0;
    final canNext = page < maxPage;

    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        children: [
          Text(
            '${startIndex + 1}–$endIndex sur $itemCount',
            style: const TextStyle(
              color: AppColors.mutedText,
              fontSize: 12,
              fontWeight: FontWeight.w900,
            ),
          ),
          const Spacer(),
          Text(
            'Page ${page + 1}/${maxPage + 1}',
            style: const TextStyle(
              color: AppColors.slateGraphite,
              fontSize: 12,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(width: 10),
          IconButton.filledTonal(
            tooltip: 'Page précédente',
            onPressed: canPrevious ? onPrevious : null,
            icon: const Icon(Icons.chevron_left_rounded),
          ),
          const SizedBox(width: 6),
          IconButton.filledTonal(
            tooltip: 'Page suivante',
            onPressed: canNext ? onNext : null,
            icon: const Icon(Icons.chevron_right_rounded),
          ),
        ],
      ),
    );
  }
}

class _NoAlarmLine extends StatelessWidget {
  const _NoAlarmLine({this.message = 'Aucun site dans cette condition.'});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: AppColors.tealSoft,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.deepTeal.withOpacity(0.12)),
      ),
      child: Row(
        children: [
          const Icon(Icons.check_circle_rounded, color: AppColors.deepTeal),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: const TextStyle(color: AppColors.deepTeal, fontWeight: FontWeight.w900),
            ),
          ),
        ],
      ),
    );
  }
}

class _CountPill extends StatelessWidget {
  const _CountPill({required this.count});

  final int count;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: count > 0 ? AppColors.faulty.withOpacity(0.10) : AppColors.deepTeal.withOpacity(0.10),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        '$count site${count > 1 ? 's' : ''}',
        style: TextStyle(
          color: count > 0 ? AppColors.faulty : AppColors.deepTeal,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

class _InfoChip extends StatelessWidget {
  const _InfoChip({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: AppColors.white,
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Text(
        '$label: $value',
        style: const TextStyle(
          color: AppColors.slateGraphite,
          fontSize: 11,
          fontWeight: FontWeight.w900,
        ),
      ),
    );
  }
}

IconData _typeIcon(String type) {
  switch (type) {
    
    case 'NO_MONITORING_1D':
      return Icons.wifi_off_rounded;
    case 'STATUS_FAULT_OR_OFFLINE':
      return Icons.power_settings_new_rounded;
    case 'PERFORMANCE_GAP':
    default:
      return Icons.trending_down_rounded;
  }
}

Color _typeAccent(String type) {
  switch (type) {
    
    case 'NO_MONITORING_1D':
      return AppColors.warning;
    case 'STATUS_FAULT_OR_OFFLINE':
      return AppColors.faulty;
    case 'PERFORMANCE_GAP':
    default:
      return AppColors.deepTeal;
  }
}

Color _severityColor(String severity) {
  if (severity.toUpperCase() == 'CRITICAL') return AppColors.faulty;
  if (severity.toUpperCase() == 'WARNING') return AppColors.warning;
  return AppColors.deepTeal;
}

String _metricLabel(String type) {
  switch (type) {
    
    case 'NO_MONITORING_1D':
      return 'Sans monitoring';
    case 'PERFORMANCE_GAP':
      return 'Écart';
    default:
      return 'Valeur';
  }
}

String _metricValue(AlarmItem alarm) {
  final unit = alarm.unit ?? '';
  final value = Formatters.decimal(alarm.metricValue, decimals: 2);
  return unit.isEmpty ? value : '$value $unit';
}

String _shortSectionTitle(String type, String fallback) {
  switch (type) {
    case 'PERFORMANCE_GAP':
      return '-20% gouvernorat';
    
    case 'NO_MONITORING_1D':
      return 'Sans monitoring';
    case 'STATUS_FAULT_OR_OFFLINE':
      return 'Défaut / Offline';
    default:
      return fallback;
  }
}