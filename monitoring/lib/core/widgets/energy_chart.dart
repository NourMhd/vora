import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../../app/theme/app_colors.dart';
import '../../data/models/history_point.dart';
import '../utils/formatters.dart';
import 'loading_state.dart';

class EnergyLineChart extends StatelessWidget {
  const EnergyLineChart({
    super.key,
    required this.points,
    required this.mode,
    this.height = 280,
  });

  final List<HistoryPoint> points;
  final String mode;
  final double height;

  bool get _isDayMode => mode == 'day';

  @override
  Widget build(BuildContext context) {
    if (points.isEmpty) {
      return SizedBox(
        height: height,
        child: const EmptyState(
          title: 'Aucun historique',
          message: 'Aucun point n’est disponible pour cette période.',
          icon: Icons.show_chart_rounded,
        ),
      );
    }

    final spots = <FlSpot>[];

    for (var i = 0; i < points.length; i++) {
      spots.add(FlSpot(i.toDouble(), points[i].value));
    }

    final maxY = points.fold<double>(
      0,
      (max, point) => point.value > max ? point.value : max,
    );

    return SizedBox(
      height: height,
      child: Padding(
        padding: const EdgeInsets.only(top: 12, right: 12),
        child: LineChart(
          LineChartData(
            minY: 0,
            maxY: maxY <= 0 ? 1 : maxY * 1.18,
            borderData: FlBorderData(show: false),
            gridData: FlGridData(
              show: true,
              drawVerticalLine: false,
              getDrawingHorizontalLine: (value) => FlLine(
                color: AppColors.borderGray.withOpacity(0.75),
                strokeWidth: 1,
              ),
            ),
            titlesData: FlTitlesData(
              topTitles: const AxisTitles(
                sideTitles: SideTitles(showTitles: false),
              ),
              rightTitles: const AxisTitles(
                sideTitles: SideTitles(showTitles: false),
              ),
              leftTitles: AxisTitles(
                sideTitles: SideTitles(
                  showTitles: true,
                  reservedSize: 52,
                  getTitlesWidget: (value, meta) {
                    return Text(
                      _axisLabelFor(value, mode),
                      style: const TextStyle(
                        color: AppColors.mutedText,
                        fontSize: 10,
                      ),
                    );
                  },
                ),
              ),
              bottomTitles: AxisTitles(
                sideTitles: SideTitles(
                  showTitles: true,
                  reservedSize: 34,
                  interval: _bottomInterval(points.length),
                  getTitlesWidget: (value, meta) {
                    final index = value.round();

                    if (index < 0 || index >= points.length) {
                      return const SizedBox.shrink();
                    }

                    return Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(
                        _labelFor(points[index], mode),
                        style: const TextStyle(
                          color: AppColors.mutedText,
                          fontSize: 10,
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
            lineTouchData: LineTouchData(
              handleBuiltInTouches: true,
              touchTooltipData: LineTouchTooltipData(
                tooltipRoundedRadius: 14,
                tooltipPadding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 10,
                ),
                getTooltipItems: (touchedSpots) {
                  return touchedSpots.map((spot) {
                    final index = spot.x.round();

                    if (index < 0 || index >= points.length) {
                      return null;
                    }

                    final point = points[index];

                    return LineTooltipItem(
                      '${_valueLabelFor(point.value, mode)}\n${_tooltipLabelFor(point, mode)}',
                      const TextStyle(
                        color: AppColors.white,
                        fontSize: 12,
                        fontWeight: FontWeight.w900,
                        height: 1.35,
                      ),
                    );
                  }).toList();
                },
              ),
            ),
            lineBarsData: [
              LineChartBarData(
                spots: spots,
                isCurved: true,
                curveSmoothness: 0.28,
                color: AppColors.deepTeal,
                barWidth: 3,
                isStrokeCapRound: true,
                dotData: const FlDotData(show: false),
                belowBarData: BarAreaData(
                  show: true,
                  color: AppColors.deepTeal.withOpacity(0.08),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  double _bottomInterval(int length) {
    if (length <= 8) return 1;
    if (length <= 18) return 3;
    return (length / 6).ceilToDouble();
  }

  String _axisLabelFor(double value, String mode) {
    if (mode == 'day') {
      return Formatters.decimal(value, decimals: 2);
    }

    return Formatters.decimal(value, decimals: 3);
  }

  String _valueLabelFor(double value, String mode) {
    if (mode == 'day') {
      return '${Formatters.decimal(value, decimals: 3)} kW';
    }

    return Formatters.energy(value);
  }

  String _labelFor(HistoryPoint point, String mode) {
    final date = point.dateTime;

    if (date == null) return '';

    switch (mode) {
      case 'month':
        return DateFormat('dd/MM').format(date);

      case 'year':
        return DateFormat('MMM').format(date);

      case 'all':
        return DateFormat('yyyy').format(date);

      case 'day':
      case 'custom':
      default:
        return DateFormat('HH:mm').format(date);
    }
  }

  String _tooltipLabelFor(HistoryPoint point, String mode) {
    final date = point.dateTime;

    if (date == null) return 'Période inconnue';

    switch (mode) {
      case 'month':
        return "Jour ${DateFormat('dd/MM/yyyy').format(date)}";

      case 'year':
        return "Mois ${DateFormat('MMM yyyy').format(date)}";

      case 'all':
        return "Année ${DateFormat('yyyy').format(date)}";

      case 'day':
      case 'custom':
      default:
        return "Heure ${DateFormat('dd/MM/yyyy HH:mm').format(date)}";
    }
  }
}

class StatusDonutChart extends StatefulWidget {
  const StatusDonutChart({
    super.key,
    required this.counts,
    required this.selectedStatus,
    required this.onStatusTap,
  });

  final Map<String, int> counts;
  final String? selectedStatus;
  final ValueChanged<String> onStatusTap;

  @override
  State<StatusDonutChart> createState() => _StatusDonutChartState();
}

class _StatusDonutChartState extends State<StatusDonutChart> {
  int? touchedIndex;

  @override
  Widget build(BuildContext context) {
    final entries = widget.counts.entries.toList();
    final total = widget.counts.values.fold<int>(
      0,
      (sum, value) => sum + value,
    );

    if (total == 0) {
      return const SizedBox(
        height: 260,
        child: EmptyState(
          title: 'Aucun statut',
          message: 'Aucun site disponible pour construire la répartition.',
          icon: Icons.donut_large_rounded,
        ),
      );
    }

    return SizedBox(
      height: 260,
      child: Row(
        children: [
          Expanded(
            child: PieChart(
              PieChartData(
                centerSpaceRadius: 56,
                sectionsSpace: 3,
                pieTouchData: PieTouchData(
                  touchCallback: (event, response) {
                    if (!event.isInterestedForInteractions ||
                        response?.touchedSection == null) {
                      setState(() => touchedIndex = null);
                      return;
                    }

                    final index =
                        response!.touchedSection!.touchedSectionIndex;

                    if (index < 0 || index >= entries.length) {
                      setState(() => touchedIndex = null);
                      return;
                    }

                    setState(() => touchedIndex = index);

                    if (event is FlTapUpEvent) {
                      widget.onStatusTap(entries[index].key);
                    }
                  },
                ),
                sections: List.generate(entries.length, (index) {
                  final entry = entries[index];
                  final selected =
                      widget.selectedStatus == entry.key || touchedIndex == index;
                  final color = _statusColor(entry.key);

                  return PieChartSectionData(
                    value: entry.value.toDouble(),
                    color: color,
                    radius: selected ? 64 : 56,
                    title: '${entry.value}',
                    titleStyle: const TextStyle(
                      color: AppColors.white,
                      fontSize: 14,
                      fontWeight: FontWeight.w900,
                    ),
                  );
                }),
              ),
              duration: const Duration(milliseconds: 650),
              curve: Curves.easeOutCubic,
            ),
          ),
          const SizedBox(width: 18),
          SizedBox(
            width: 132,
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: entries.map((entry) {
                final color = _statusColor(entry.key);
                final active = widget.selectedStatus == entry.key;

                return InkWell(
                  onTap: () => widget.onStatusTap(entry.key),
                  borderRadius: BorderRadius.circular(14),
                  child: Container(
                    margin: const EdgeInsets.only(bottom: 10),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 10,
                    ),
                    decoration: BoxDecoration(
                      color: active ? color.withOpacity(0.10) : Colors.transparent,
                      borderRadius: BorderRadius.circular(14),
                      border: Border.all(
                        color: active
                            ? color.withOpacity(0.22)
                            : AppColors.borderGray,
                      ),
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 9,
                          height: 9,
                          decoration: BoxDecoration(
                            color: color,
                            shape: BoxShape.circle,
                          ),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            _statusLabel(entry.key),
                            style: const TextStyle(
                              color: AppColors.slateGraphite,
                              fontWeight: FontWeight.w800,
                              fontSize: 12,
                            ),
                          ),
                        ),
                        Text(
                          entry.value.toString(),
                          style: const TextStyle(
                            color: AppColors.midnightNavy,
                            fontWeight: FontWeight.w900,
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
        ],
      ),
    );
  }

  Color _statusColor(String status) {
    switch (status) {
      case 'healthy':
        return AppColors.healthy;

      case 'faulty':
        return AppColors.faulty;

      default:
        return AppColors.offline;
    }
  }

  String _statusLabel(String status) {
    switch (status) {
      case 'healthy':
        return 'Healthy';

      case 'faulty':
        return 'Faulty';

      default:
        return 'Offline';
    }
  }
}