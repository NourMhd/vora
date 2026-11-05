import 'dart:ui' show PointerDeviceKind;
import 'package:flutter/material.dart';
import '../../../data/models/daily_comparison_report.dart';
import 'comparison_charts.dart';

/// The header is pinned by the page's main viewport, and pushed out at this
/// table's end. Horizontal scrolling moves headers and values together.
class StickyComparisonTable extends StatefulWidget {
  const StickyComparisonTable({
    super.key,
    required this.title,
    required this.subtitle,
    required this.rows,
    required this.totalRow,
    required this.sites,
  });
  final String title, subtitle;
  final List<DailyComparisonRow> rows;
  final DailyComparisonRow? totalRow;
  final List<DailyComparisonSite> sites;
  @override
  State<StickyComparisonTable> createState() => _StickyComparisonTableState();
}

class _StickyComparisonTableState extends State<StickyComparisonTable> {
  final horizontal = ScrollController();
  final offset = ValueNotifier<double>(0);
  static const dateWidth = 112.0, siteWidth = 190.0;
  @override
  void initState() {
    super.initState();
    horizontal.addListener(sync);
  }

  void sync() {
    offset.value = horizontal.offset;
  }

  @override
  void dispose() {
    horizontal.removeListener(sync);
    horizontal.dispose();
    offset.dispose();
    super.dispose();
  }

  void shift(double delta) {
    if (horizontal.hasClients) {
      horizontal.jumpTo(
        (horizontal.offset + delta)
            .clamp(0.0, horizontal.position.maxScrollExtent)
            .toDouble(),
      );
    }
  }

  Widget siteCell(Widget child) => SizedBox(
    width: siteWidth,
    child: Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: child,
    ),
  );
  Widget header() => Material(
    color: const Color(0xFFF1F5F9),
    elevation: 1,
    child: Column(
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(14, 8, 6, 2),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  widget.title,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontWeight: FontWeight.w700,
                    fontSize: 17,
                  ),
                ),
              ),
              Tooltip(
                message: widget.subtitle,
                child: const Icon(Icons.info_outline, size: 18),
              ),
              IconButton(
                onPressed: () => shift(-siteWidth * 2),
                tooltip: 'Colonnes précédentes',
                icon: const Icon(Icons.chevron_left),
              ),
              IconButton(
                onPressed: () => shift(siteWidth * 2),
                tooltip: 'Colonnes suivantes',
                icon: const Icon(Icons.chevron_right),
              ),
            ],
          ),
        ),
        Expanded(
          child: Row(
            children: [
              const SizedBox(
                width: dateWidth,
                child: Padding(
                  padding: EdgeInsets.only(left: 14),
                  child: Text(
                    'Date',
                    style: TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
              ),
              Expanded(
                child: ScrollConfiguration(
                  behavior: const MaterialScrollBehavior().copyWith(
                    dragDevices: {
                      PointerDeviceKind.mouse,
                      PointerDeviceKind.touch,
                      PointerDeviceKind.trackpad,
                    },
                  ),
                  child: Scrollbar(
                    controller: horizontal,
                    thumbVisibility: true,
                    child: SingleChildScrollView(
                      controller: horizontal,
                      scrollDirection: Axis.horizontal,
                      child: SizedBox(
                        height: 65,
                        child: Row(
                          children: widget.sites
                              .map(
                                (site) => siteCell(
                                  Row(
                                    children: [
                                      Icon(
                                        Icons.circle,
                                        size: 9,
                                        color: comparisonSiteColor(
                                          site.siteCode,
                                        ),
                                      ),
                                      const SizedBox(width: 7),
                                      Expanded(
                                        child: Tooltip(
                                          message:
                                              '${site.label}\n${site.powerLabel}',
                                          child: Text(
                                            '${site.label}\n${site.powerLabel}',
                                            maxLines: 2,
                                            overflow: TextOverflow.ellipsis,
                                            style: const TextStyle(
                                              fontWeight: FontWeight.w600,
                                              fontSize: 12,
                                            ),
                                          ),
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                              )
                              .toList(),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ],
    ),
  );
  Widget row(DailyComparisonRow row, int index) {
    final total = row.label.toUpperCase() == 'TOTAL';
    final cells = {for (final c in row.cells) c.siteCode: c};
    return Container(
      height: 48,
      decoration: BoxDecoration(
        color: total
            ? const Color(0xFFE2F3EF)
            : index.isEven
            ? Colors.white
            : const Color(0xFFF8FAFC),
        border: const Border(
          bottom: BorderSide(color: Color(0xFFE2E8F0), width: .5),
        ),
      ),
      child: Row(
        children: [
          SizedBox(
            width: dateWidth,
            child: Padding(
              padding: const EdgeInsets.only(left: 14),
              child: Text(
                row.label,
                style: TextStyle(
                  fontWeight: total ? FontWeight.w700 : FontWeight.w500,
                  fontSize: 12,
                ),
              ),
            ),
          ),
          Expanded(
            child: GestureDetector(
              onHorizontalDragUpdate: (event) => shift(-event.delta.dx),
              child: ClipRect(
                child: ValueListenableBuilder<double>(
                  valueListenable: offset,
                  builder: (context, value, _) => OverflowBox(
                    alignment: Alignment.centerLeft,
                    minWidth: widget.sites.length * siteWidth,
                    maxWidth: widget.sites.length * siteWidth,
                    child: Transform.translate(
                      offset: Offset(-value, 0),
                      child: Row(
                        children: widget.sites.map((site) {
                          final c = cells[site.siteCode];
                          return siteCell(
                            Tooltip(
                              message:
                                  '${site.label} · ${row.label}\n${c?.displayValue ?? '—'}${c?.estimated == true ? ' · estimé' : ''}',
                              child: Text(
                                c?.displayValue ?? '—',
                                maxLines: 1,
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(
                                  fontSize: 12,
                                  fontWeight: total
                                      ? FontWeight.w700
                                      : FontWeight.w500,
                                  color: c?.estimated == true
                                      ? const Color(0xFFB45309)
                                      : const Color(0xFF243449),
                                  fontStyle: c?.estimated == true
                                      ? FontStyle.italic
                                      : FontStyle.normal,
                                ),
                              ),
                            ),
                          );
                        }).toList(),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final allRows = [
      ...widget.rows,
      if (widget.totalRow != null) widget.totalRow!,
    ];
    return SliverMainAxisGroup(
      slivers: [
        SliverPersistentHeader(
          pinned: true,
          delegate: _TableHeader(child: header()),
        ),
        if (allRows.isEmpty)
          const SliverToBoxAdapter(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: Text('Aucune journée disponible.'),
            ),
          )
        else
          SliverList(
            delegate: SliverChildBuilderDelegate(
              (context, i) => row(allRows[i], i),
              childCount: allRows.length,
            ),
          ),
      ],
    );
  }
}

class _TableHeader extends SliverPersistentHeaderDelegate {
  _TableHeader({required this.child});
  final Widget child;
  @override
  double get minExtent => 126;
  @override
  double get maxExtent => 126;
  @override
  Widget build(
    BuildContext context,
    double shrinkOffset,
    bool overlapsContent,
  ) => child;
  @override
  bool shouldRebuild(covariant _TableHeader oldDelegate) => true;
}
