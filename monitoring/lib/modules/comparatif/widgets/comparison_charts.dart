import 'dart:math' as math;
import 'dart:ui' show PointerDeviceKind;
import 'package:flutter/material.dart';
import '../../../data/models/daily_comparison_report.dart';

Color comparisonSiteColor(String code) {
  var hash = 0;
  for (final c in code.codeUnits) {
    hash = (hash * 31 + c) & 0x7fffffff;
  }
  return HSLColor.fromAHSL(1, (hash * 137.508) % 360, .65, .40).toColor();
}

class ComparisonCharts extends StatefulWidget {
  const ComparisonCharts({super.key, required this.report});
  final DailyComparisonReport report;
  @override
  State<ComparisonCharts> createState() => _ComparisonChartsState();
}

class _ComparisonChartsState extends State<ComparisonCharts> {
  bool normalized = false, estimates = false, showDetails = false;
  final hidden = <String>{};
  final scroll = ScrollController();
  final indexedCells = <DailyComparisonRow, Map<String, DailyComparisonCell>>{};
  final maxima = <String, double>{};
  int? hovered;
  @override
  void didUpdateWidget(covariant ComparisonCharts oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.report, widget.report)) {
      hovered = null;
      indexedCells.clear();
      maxima.clear();
      hidden.removeWhere(
        (code) => !widget.report.sites.any((s) => s.siteCode == code),
      );
    }
  }

  @override
  void dispose() {
    scroll.dispose();
    super.dispose();
  }

  List<DailyComparisonRow> get rows =>
      estimates ? widget.report.estimatedRows : widget.report.historicalRows;
  String get unit => normalized ? 'kWh/kWc' : 'kWh';
  DailyComparisonCell? cell(DailyComparisonRow row, String code) {
    return indexedCells.putIfAbsent(
      row,
      () => {for (final c in row.cells) c.siteCode: c},
    )[code];
  }

  double? value(DailyComparisonRow row, DailyComparisonSite site) {
    final v = cell(row, site.siteCode)?.value;
    if (v == null || !v.isFinite || v < 0) return null;
    final p = site.installedPowerKw;
    if (normalized && (p == null || !p.isFinite || p <= 0)) return null;
    return normalized ? v / p! : v;
  }

  double get maximum {
    final key = '$normalized/$estimates';
    if (maxima.containsKey(key)) return maxima[key]!;
    var result = 0.0;
    for (final row in rows) {
      for (final site in widget.report.sites) {
        result = math.max(result, value(row, site) ?? 0);
      }
    }
    return maxima[key] = result > 0 ? result * 1.1 : 1;
  }

  void move(int direction) {
    if (!scroll.hasClients) return;
    scroll.animateTo(
      (scroll.offset + direction * 350)
          .clamp(0.0, scroll.position.maxScrollExtent)
          .toDouble(),
      duration: const Duration(milliseconds: 250),
      curve: Curves.easeOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    final sites = widget.report.sites;
    final data = rows;
    final max = maximum;
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              'Vue d’ensemble',
              style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 10,
              runSpacing: 8,
              children: [
                ChoiceChip(
                  label: const Text('Production · kWh'),
                  selected: !normalized,
                  onSelected: (_) => setState(() => normalized = false),
                ),
                ChoiceChip(
                  label: const Text('Rendement · kWh/kWc'),
                  selected: normalized,
                  onSelected: (_) => setState(() => normalized = true),
                ),
                FilterChip(
                  label: const Text('Inclure les estimations'),
                  selected: estimates,
                  onSelected: (v) => setState(() => estimates = v),
                ),
              ],
            ),
            const SizedBox(height: 12),
            const Text(
              'Réel : trait plein · Estimation : pointillés · Absence : coupure',
              style: TextStyle(fontSize: 12, color: Colors.blueGrey),
            ),
            const SizedBox(height: 12),
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 96),
              child: SingleChildScrollView(
                child: Wrap(
                  spacing: 6,
                  runSpacing: 4,
                  children: sites
                      .map(
                        (s) => FilterChip(
                          visualDensity: VisualDensity.compact,
                          avatar: Icon(
                            Icons.circle,
                            size: 9,
                            color: comparisonSiteColor(s.siteCode),
                          ),
                          label: Text(s.label),
                          selected: !hidden.contains(s.siteCode),
                          onSelected: (v) => setState(() {
                            v
                                ? hidden.remove(s.siteCode)
                                : hidden.add(s.siteCode);
                          }),
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
            if (hidden.isNotEmpty)
              TextButton(
                onPressed: () => setState(hidden.clear),
                child: Text('Réafficher ${hidden.length} courbes masquées'),
              ),
            if (data.isEmpty)
              const Padding(
                padding: EdgeInsets.all(30),
                child: Text('Aucune journée disponible pour cette période.'),
              )
            else ...[
              chart(
                sites.where((s) => !hidden.contains(s.siteCode)).toList(),
                data,
                max,
                310,
              ),
              Container(
                height: 84,
                margin: const EdgeInsets.only(top: 10),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: const Color(0xFFF8FAFC),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: hovered == null || hovered! >= data.length
                    ? const Center(
                        child: Text(
                          'Survolez ou touchez une date pour lire les valeurs.',
                          style: TextStyle(
                            color: Colors.blueGrey,
                            fontSize: 12,
                          ),
                        ),
                      )
                    : SingleChildScrollView(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              data[hovered!].label,
                              style: const TextStyle(
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            const SizedBox(height: 6),
                            Wrap(
                              spacing: 18,
                              runSpacing: 6,
                              children: sites
                                  .where((s) => !hidden.contains(s.siteCode))
                                  .map((s) {
                                    final c = cell(data[hovered!], s.siteCode);
                                    final v = value(data[hovered!], s);
                                    return Text(
                                      '${s.label} : ${v?.toStringAsFixed(2) ?? '—'} $unit${c?.estimated == true ? ' · estimé' : ''}',
                                      style: TextStyle(
                                        color: comparisonSiteColor(s.siteCode),
                                        fontSize: 12,
                                      ),
                                    );
                                  })
                                  .toList(),
                            ),
                          ],
                        ),
                      ),
              ),
              Row(
                children: [
                  Expanded(
                    child: Text(
                      '${sites.length} sites sélectionnés',
                      style: const TextStyle(fontWeight: FontWeight.bold),
                    ),
                  ),
                  TextButton.icon(
                    onPressed: () => setState(() => showDetails = !showDetails),
                    icon: Icon(
                      showDetails ? Icons.expand_less : Icons.expand_more,
                    ),
                    label: Text(
                      showDetails ? 'Masquer les détails' : 'Détails par site',
                    ),
                  ),
                  if (showDetails)
                    IconButton(
                      onPressed: () => move(-1),
                      tooltip: 'Sites précédents',
                      icon: const Icon(Icons.chevron_left),
                    ),
                  if (showDetails)
                    IconButton(
                      onPressed: () => move(1),
                      tooltip: 'Sites suivants',
                      icon: const Icon(Icons.chevron_right),
                    ),
                ],
              ),
              if (showDetails)
                LayoutBuilder(
                  builder: (context, constraints) {
                    final width = math.min(350.0, constraints.maxWidth);
                    return ScrollConfiguration(
                      behavior: const MaterialScrollBehavior().copyWith(
                        dragDevices: {
                          PointerDeviceKind.mouse,
                          PointerDeviceKind.touch,
                          PointerDeviceKind.trackpad,
                        },
                      ),
                      child: Scrollbar(
                        controller: scroll,
                        thumbVisibility: true,
                        child: SizedBox(
                          height: 320,
                          child: ListView.builder(
                            controller: scroll,
                            scrollDirection: Axis.horizontal,
                            itemExtent: width,
                            itemCount: sites.length,
                            itemBuilder: (context, index) {
                              final s = sites[index];
                              var total = 0.0, count = 0, estimatedCount = 0;
                              for (final r in data) {
                                final v = value(r, s);
                                if (v != null) {
                                  total += v;
                                  count++;
                                  if (cell(r, s.siteCode)?.estimated == true) {
                                    estimatedCount++;
                                  }
                                }
                              }
                              return Padding(
                                padding: const EdgeInsets.fromLTRB(
                                  0,
                                  8,
                                  12,
                                  18,
                                ),
                                child: Container(
                                  padding: const EdgeInsets.all(12),
                                  decoration: BoxDecoration(
                                    border: Border.all(
                                      color: comparisonSiteColor(
                                        s.siteCode,
                                      ).withValues(alpha: .35),
                                    ),
                                    borderRadius: BorderRadius.circular(14),
                                  ),
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        s.label,
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: TextStyle(
                                          fontWeight: FontWeight.bold,
                                          color: comparisonSiteColor(
                                            s.siteCode,
                                          ),
                                        ),
                                      ),
                                      Text(s.powerLabel),
                                      Text(
                                        count == 0
                                            ? 'Aucune valeur exploitable'
                                            : '${total.toStringAsFixed(2)} $unit sur la période',
                                      ),
                                      Text(
                                        '$count/${data.length} jours renseignés · $estimatedCount estimés',
                                      ),
                                      const SizedBox(height: 8),
                                      Expanded(
                                        child: chart(
                                          [s],
                                          data,
                                          max,
                                          double.infinity,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              );
                            },
                          ),
                        ),
                      ),
                    );
                  },
                ),
            ],
          ],
        ),
      ),
    );
  }

  Widget chart(
    List<DailyComparisonSite> sites,
    List<DailyComparisonRow> data,
    double max,
    double height,
  ) {
    return SizedBox(
      height: height,
      child: LayoutBuilder(
        builder: (context, constraints) {
          void select(Offset position) {
            final fraction =
                ((position.dx - 48) / math.max(1, constraints.maxWidth - 60))
                    .clamp(0.0, 1.0);
            final index = (fraction * (data.length - 1)).round();
            if (hovered != index) setState(() => hovered = index);
          }

          return MouseRegion(
            onHover: (event) => select(event.localPosition),
            child: GestureDetector(
              onTapDown: (event) => select(event.localPosition),
              child: CustomPaint(
                size: Size.infinite,
                painter: _ProductionPainter(
                  sites: sites,
                  rows: data,
                  maximum: max,
                  unit: unit,
                  hovered: hovered,
                  value: value,
                  cell: cell,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

class _ProductionPainter extends CustomPainter {
  _ProductionPainter({
    required this.sites,
    required this.rows,
    required this.maximum,
    required this.unit,
    required this.hovered,
    required this.value,
    required this.cell,
  });
  final List<DailyComparisonSite> sites;
  final List<DailyComparisonRow> rows;
  final double maximum;
  final String unit;
  final int? hovered;
  final double? Function(DailyComparisonRow, DailyComparisonSite) value;
  final DailyComparisonCell? Function(DailyComparisonRow, String) cell;
  void text(Canvas canvas, String label, Offset offset) {
    final tp = TextPainter(
      text: TextSpan(
        text: label,
        style: const TextStyle(fontSize: 10, color: Colors.blueGrey),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, offset);
  }

  @override
  void paint(Canvas canvas, Size size) {
    if (size.width <= 60 || size.height <= 50 || rows.isEmpty) return;
    final rect = Rect.fromLTRB(48, 22, size.width - 12, size.height - 30);
    double x(int index) =>
        rect.left +
        (rows.length == 1 ? .5 : index / (rows.length - 1)) * rect.width;
    Offset point(int index, double v) =>
        Offset(x(index), rect.bottom - v / maximum * rect.height);
    text(canvas, unit, const Offset(0, 0));
    for (var i = 0; i <= 4; i++) {
      final y = rect.bottom - i / 4 * rect.height;
      canvas.drawLine(
        Offset(rect.left, y),
        Offset(rect.right, y),
        Paint()..color = Colors.blueGrey.withValues(alpha: .15),
      );
      text(canvas, (maximum * i / 4).toStringAsFixed(1), Offset(0, y - 6));
    }
    final indices = {0, (rows.length - 1) ~/ 2, rows.length - 1};
    for (final i in indices) {
      final label = rows[i].date ?? rows[i].label;
      text(
        canvas,
        label,
        Offset(
          (x(i) - 25)
              .clamp(rect.left, math.max(rect.left, size.width - 65))
              .toDouble(),
          rect.bottom + 8,
        ),
      );
    }
    canvas.save();
    canvas.clipRect(rect.inflate(4));
    for (final site in sites) {
      final paint = Paint()
        ..color = comparisonSiteColor(site.siteCode)
        ..strokeWidth = 2;
      Offset? previous;
      var previousEstimated = false;
      for (var i = 0; i < rows.length; i++) {
        final v = value(rows[i], site);
        if (v == null) {
          previous = null;
          continue;
        }
        final p = point(i, v);
        final estimated = cell(rows[i], site.siteCode)?.estimated == true;
        if (previous != null) {
          if (estimated || previousEstimated) {
            final distance = (p - previous).distance;
            if (distance > 0) {
              for (var d = 0.0; d < distance; d += 9) {
                canvas.drawLine(
                  Offset.lerp(previous, p, d / distance)!,
                  Offset.lerp(
                    previous,
                    p,
                    math.min(d + 5, distance) / distance,
                  )!,
                  paint,
                );
              }
            }
          } else {
            canvas.drawLine(previous, p, paint);
          }
        }
        if (rows.length < 45 || hovered == i || previous == null) {
          canvas.drawCircle(
            p,
            hovered == i ? 4 : 2.5,
            estimated
                ? (Paint()
                    ..color = paint.color
                    ..style = PaintingStyle.stroke
                    ..strokeWidth = 1.5)
                : paint,
          );
        }
        previous = p;
        previousEstimated = estimated;
      }
    }
    if (hovered != null && hovered! < rows.length) {
      canvas.drawLine(
        Offset(x(hovered!), rect.top),
        Offset(x(hovered!), rect.bottom),
        Paint()
          ..color = Colors.blueGrey.withValues(alpha: .5)
          ..strokeWidth = 1,
      );
    }
    canvas.restore();
  }

  @override
  bool shouldRepaint(covariant _ProductionPainter oldDelegate) => true;
}
