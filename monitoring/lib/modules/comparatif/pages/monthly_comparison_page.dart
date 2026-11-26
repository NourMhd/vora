import 'dart:ui' show PointerDeviceKind;

import 'package:excel/excel.dart' as xlsx;
import 'package:flutter/material.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/widgets/premium_card.dart';
import '../widgets/comparison_charts.dart';

typedef MonthlyLoader =
    Future<List<Map<String, dynamic>>> Function(
      String startMonth,
      String endMonth,
    );

enum _Metric {
  energy('kWh', 'actualKwh'),
  specific('kWh/kWc', 'kwhPerKwc'),
  daily('kWh/kWc/jour', 'kwhPerKwcDay'),
  variance('Écart au prévu (%)', 'differencePercent');

  const _Metric(this.label, this.field);
  final String label;
  final String field;
}

class MonthlyComparisonPage extends StatefulWidget {
  const MonthlyComparisonPage({
    super.key,
    required this.load,
    this.onOpenProduction,
  });

  final MonthlyLoader load;
  final VoidCallback? onOpenProduction;

  @override
  State<MonthlyComparisonPage> createState() => _MonthlyComparisonPageState();
}

class _MonthlyComparisonPageState extends State<MonthlyComparisonPage> {
  final now = DateTime.now();
  late final latest = DateTime(now.year, now.month);
  late final earliest = DateTime(now.year, now.month - 59);
  late DateTime start = DateTime(now.year, 1);
  late DateTime end = latest;

  late Future<List<_Site>> report;
  String? zone;
  String? governorate;
  final selectedSites = <String>{};
  final hiddenMonths = <String>{};
  _Metric metric = _Metric.energy;
  bool chartView = false;
  bool showFilters = false;
  int page = 0;
  static const pageSize = 25;
  final tableScroll = ScrollController();

  @override
  void dispose() {
    tableScroll.dispose();
    super.dispose();
  }

  void changePeriod(DateTime from, DateTime to) {
    setState(() {
      start = from;
      end = to;
      hiddenMonths.clear();
      reload();
    });
  }

  String monthLabel(String key) {
    const names = [
      'Janv.',
      'Févr.',
      'Mars',
      'Avr.',
      'Mai',
      'Juin',
      'Juil.',
      'Août',
      'Sept.',
      'Oct.',
      'Nov.',
      'Déc.',
    ];
    final date = DateTime.parse('$key-01');
    return '${names[date.month - 1]} ${date.year}';
  }

  @override
  void initState() {
    super.initState();
    reload();
  }

  void reload() {
    report = widget
        .load(monthKey(start), monthKey(end))
        .then((rows) => rows.map(_Site.new).toList());
  }

  String monthKey(DateTime date) =>
      '${date.year}-${date.month.toString().padLeft(2, '0')}';

  String format(num? value) => value == null
      ? 'Indisponible'
      : value.toStringAsFixed(2).replaceAll('.', ',');

  Widget monthPicker(
    String label,
    DateTime value,
    ValueChanged<DateTime> update,
    DateTime first,
    DateTime last,
  ) {
    return DropdownButton<DateTime>(
      value: value,
      hint: Text(label),
      items: [
        for (
          var date = last;
          !date.isBefore(first);
          date = DateTime(date.year, date.month - 1)
        )
          DropdownMenuItem(
            value: date,
            child: Text('$label · ${monthLabel(monthKey(date))}'),
          ),
      ],
      onChanged: (date) {
        if (date == null || date == value) return;
        setState(() {
          update(date);
          hiddenMonths.clear();
          reload();
        });
      },
    );
  }

  List<String> options(Iterable<String?> values) =>
      (values.whereType<String>().toSet().toList()..sort());

  Widget filter(
    String label,
    List<String> values,
    String? value,
    ValueChanged<String?> update,
  ) {
    return DropdownButton<String>(
      value: values.contains(value) ? value : '',
      items: [
        DropdownMenuItem(value: '', child: Text('$label · Tous')),
        for (final option in values)
          DropdownMenuItem(value: option, child: Text(option)),
      ],
      onChanged: (choice) =>
          setState(() => update(choice == '' ? null : choice)),
    );
  }

  Future<void> chooseSites(List<_Site> sites) async {
    final next = {...selectedSites};
    final limit = chartView ? 12 : sites.length;
    final result = await showDialog<Set<String>>(
      context: context,
      builder: (context) {
        var query = '';
        return StatefulBuilder(
          builder: (context, update) => AlertDialog(
            title: const Text('Sites à comparer'),
            content: SizedBox(
              width: 480,
              height: 420,
              child: Column(
                children: [
                  TextField(
                    decoration: const InputDecoration(
                      labelText: 'Rechercher un site',
                      prefixIcon: Icon(Icons.search),
                    ),
                    onChanged: (value) =>
                        update(() => query = value.toLowerCase().trim()),
                  ),
                  Expanded(
                    child: ListView(
                      children: [
                        for (final site in sites.where(
                          (site) => '${site.name} ${site.code}'
                              .toLowerCase()
                              .contains(query),
                        ))
                          CheckboxListTile(
                            title: Text(site.label),
                            value: next.contains(site.key),
                            onChanged:
                                next.length >= limit && !next.contains(site.key)
                                ? null
                                : (checked) => update(() {
                                    if (checked == true) {
                                      next.add(site.key);
                                    } else {
                                      next.remove(site.key);
                                    }
                                  }),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => update(next.clear),
                child: const Text('Tous les sites'),
              ),
              TextButton(
                onPressed: () => Navigator.pop(context),
                child: const Text('Annuler'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, next),
                child: const Text('Appliquer'),
              ),
            ],
          ),
        );
      },
    );
    if (mounted && result != null) {
      setState(() {
        page = 0;
        selectedSites
          ..clear()
          ..addAll(result);
      });
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    backgroundColor: AppColors.softWhite,
    appBar: AppBar(
      title: const Text('Analyse mensuelle'),
      backgroundColor: AppColors.white,
      surfaceTintColor: AppColors.white,
    ),
    body: ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Container(
          padding: const EdgeInsets.all(22),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(20),
            gradient: const LinearGradient(
              colors: [AppColors.midnightNavy, AppColors.deepTeal],
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Production mensuelle',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 26,
                  fontWeight: FontWeight.w700,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                '${monthLabel(monthKey(start))}  —  ${monthLabel(monthKey(end))}',
                style: const TextStyle(color: Color(0xFFD9EBEC)),
              ),
              if (widget.onOpenProduction != null) ...[
                const SizedBox(height: 14),
                FilledButton.icon(
                  onPressed: widget.onOpenProduction,
                  style: FilledButton.styleFrom(
                    backgroundColor: AppColors.white,
                    foregroundColor: AppColors.deepTeal,
                  ),
                  icon: const Icon(Icons.compare_arrows),
                  label: const Text('Comparer la production'),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 12),
        PremiumCard(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Wrap(
            spacing: 14,
            runSpacing: 8,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              monthPicker('Du', start, (date) => start = date, earliest, end),
              monthPicker('Au', end, (date) => end = date, start, latest),
              OutlinedButton(
                onPressed: () => changePeriod(
                  DateTime(latest.year, latest.month - 2),
                  latest,
                ),
                child: const Text('3 derniers mois'),
              ),
              OutlinedButton(
                onPressed: () => changePeriod(DateTime(latest.year), latest),
                child: const Text('Année en cours'),
              ),
              IconButton(
                onPressed: () => setState(reload),
                tooltip: 'Actualiser',
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
        ),
        const SizedBox(height: 16),
        FutureBuilder<List<_Site>>(
          future: report,
          builder: (context, snapshot) {
            if (snapshot.connectionState != ConnectionState.done) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return Center(
                child: TextButton(
                  onPressed: () => setState(reload),
                  child: const Text('Chargement impossible · Réessayer'),
                ),
              );
            }
            return results(snapshot.data ?? const []);
          },
        ),
      ],
    ),
  );

  Widget results(List<_Site> sites) {
    final months = [
      for (
        var month = start;
        !month.isAfter(end);
        month = DateTime(month.year, month.month + 1)
      )
        monthKey(month),
    ];
    final visibleMonths = months
        .where((month) => !hiddenMonths.contains(month))
        .toList();
    final scopedSites = sites
        .where(
          (site) =>
              (zone == null || site.zone == zone) &&
              (governorate == null || site.governorate == governorate),
        )
        .toList();
    final visibleSites = scopedSites
        .where(
          (site) => selectedSites.isEmpty || selectedSites.contains(site.key),
        )
        .toList();
    final lastPage = visibleSites.isEmpty
        ? 0
        : (visibleSites.length - 1) ~/ pageSize;
    final currentPage = page.clamp(0, lastPage).toInt();
    final pageSites = visibleSites
        .skip(currentPage * pageSize)
        .take(pageSize)
        .toList();
    final chartSites = visibleSites.length > 12
        ? const <_Site>[]
        : visibleSites;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        PremiumCard(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(
                spacing: 20,
                runSpacing: 8,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  IconButton.outlined(
                    tooltip: showFilters
                        ? 'Masquer les filtres'
                        : 'Zone, gouvernorat et mois',
                    onPressed: () => setState(() => showFilters = !showFilters),
                    icon: Icon(
                      showFilters ? Icons.filter_list_off : Icons.filter_list,
                    ),
                  ),
                  OutlinedButton.icon(
                    onPressed: scopedSites.isEmpty
                        ? null
                        : () => chooseSites(scopedSites),
                    icon: const Icon(Icons.checklist),
                    label: Text(
                      selectedSites.isEmpty
                          ? 'Tous les sites'
                          : '${selectedSites.length} site(s)',
                    ),
                  ),
                  DropdownButton<_Metric>(
                    value: metric,
                    items: [
                      for (final item in _Metric.values)
                        DropdownMenuItem(value: item, child: Text(item.label)),
                    ],
                    onChanged: (value) => setState(() => metric = value!),
                  ),
                  OutlinedButton.icon(
                    onPressed: visibleSites.isEmpty
                        ? null
                        : () => export(visibleSites, visibleMonths),
                    icon: const Icon(Icons.download),
                    label: const Text('Exporter Excel'),
                  ),
                ],
              ),
              if (showFilters) ...[
                const SizedBox(height: 8),
                Wrap(
                  spacing: 20,
                  children: [
                    filter(
                      'Zone',
                      options(sites.map((site) => site.zone)),
                      zone,
                      (value) {
                        zone = value;
                        governorate = null;
                        selectedSites.clear();
                        page = 0;
                      },
                    ),
                    filter(
                      'Gouvernorat',
                      options(
                        sites
                            .where((site) => zone == null || site.zone == zone)
                            .map((site) => site.governorate),
                      ),
                      governorate,
                      (value) {
                        governorate = value;
                        selectedSites.clear();
                        page = 0;
                      },
                    ),
                    TextButton(
                      onPressed: () => setState(() {
                        zone = null;
                        governorate = null;
                        hiddenMonths.clear();
                        selectedSites.clear();
                        page = 0;
                      }),
                      child: const Text('Réinitialiser les filtres'),
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  children: [
                    for (final month in months)
                      FilterChip(
                        label: Text(monthLabel(month)),
                        selected: !hiddenMonths.contains(month),
                        onSelected:
                            visibleMonths.length == 1 &&
                                visibleMonths.contains(month)
                            ? null
                            : (selected) => setState(() {
                                if (selected) {
                                  hiddenMonths.remove(month);
                                } else {
                                  hiddenMonths.add(month);
                                }
                              }),
                      ),
                  ],
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 16),
        if (visibleSites.isEmpty)
          const PremiumCard(child: Text('Aucun site pour ces filtres.'))
        else
          PremiumCard(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Wrap(
                  spacing: 12,
                  runSpacing: 8,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    ChoiceChip(
                      avatar: const Icon(Icons.table_chart_outlined, size: 18),
                      label: const Text('Tableau'),
                      selected: !chartView,
                      onSelected: (_) => setState(() => chartView = false),
                    ),
                    ChoiceChip(
                      avatar: const Icon(Icons.bar_chart, size: 18),
                      label: const Text('Graphique'),
                      selected: chartView,
                      onSelected: (_) => setState(() => chartView = true),
                    ),
                    Text(
                      '${visibleSites.length} sites · ${visibleMonths.length} mois',
                    ),
                  ],
                ),
                const SizedBox(height: 12),
                if (!chartView) ...[
                  table(pageSites, visibleMonths),
                  if (lastPage > 0)
                    Align(
                      alignment: Alignment.centerRight,
                      child: Wrap(
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(
                            '${currentPage * pageSize + 1}–${currentPage * pageSize + pageSites.length} sur ${visibleSites.length}',
                          ),
                          IconButton(
                            tooltip: 'Page précédente',
                            onPressed: currentPage == 0
                                ? null
                                : () => setState(() => page = currentPage - 1),
                            icon: const Icon(Icons.chevron_left),
                          ),
                          IconButton(
                            tooltip: 'Page suivante',
                            onPressed: currentPage == lastPage
                                ? null
                                : () => setState(() => page = currentPage + 1),
                            icon: const Icon(Icons.chevron_right),
                          ),
                        ],
                      ),
                    ),
                ] else if (chartSites.isEmpty)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: OutlinedButton.icon(
                      onPressed: () => chooseSites(scopedSites),
                      icon: const Icon(Icons.checklist),
                      label: const Text(
                        'Choisir jusqu’à 12 sites pour le graphique',
                      ),
                    ),
                  )
                else
                  chart(chartSites, visibleMonths),
              ],
            ),
          ),
      ],
    );
  }

  Widget table(List<_Site> sites, List<String> months) => LayoutBuilder(
    builder: (context, constraints) {
      final leftWidth = constraints.maxWidth < 560 ? 132.0 : 215.0;
      final monthWidth =
          ((constraints.maxWidth - leftWidth) / (months.length + 1))
              .clamp(108.0, 190.0)
              .toDouble();

      Widget header(String text, double width, {bool left = false}) =>
          Container(
            width: width,
            height: 46,
            color: AppColors.midnightNavy,
            alignment: left ? Alignment.centerLeft : Alignment.centerRight,
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Text(
              text,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: Colors.white,
                fontWeight: FontWeight.w600,
              ),
            ),
          );

      Color stripe(int index) =>
          index.isEven ? AppColors.white : AppColors.softWhite;
      return ClipRRect(
        borderRadius: BorderRadius.circular(12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(
              width: leftWidth,
              child: Column(
                children: [
                  header('Site / puissance', leftWidth, left: true),
                  for (final (index, site) in sites.indexed)
                    Container(
                      height: 52,
                      alignment: Alignment.centerLeft,
                      color: stripe(index),
                      padding: const EdgeInsets.symmetric(horizontal: 12),
                      child: Tooltip(
                        message: site.label,
                        child: Text(
                          site.label,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontWeight: FontWeight.w600,
                            color: AppColors.textPrimary,
                          ),
                        ),
                      ),
                    ),
                ],
              ),
            ),
            Expanded(
              child: ScrollConfiguration(
                behavior: ScrollConfiguration.of(context).copyWith(
                  scrollbars: false,
                  dragDevices: {
                    PointerDeviceKind.mouse,
                    PointerDeviceKind.touch,
                    PointerDeviceKind.trackpad,
                    PointerDeviceKind.stylus,
                  },
                ),
                child: Scrollbar(
                  controller: tableScroll,
                  thumbVisibility: true,
                  child: SingleChildScrollView(
                    controller: tableScroll,
                    scrollDirection: Axis.horizontal,
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            for (final month in months)
                              header(monthLabel(month), monthWidth),
                            header('Total kWh', monthWidth),
                          ],
                        ),
                        for (final (index, site) in sites.indexed)
                          Row(
                            children: [
                              for (final month in months)
                                tableValue(
                                  site.months[month]?[metric.field] as num?,
                                  monthWidth,
                                  stripe(index),
                                  onTap: () => detail(site, month),
                                ),
                              tableValue(
                                site.total(months),
                                monthWidth,
                                AppColors.tealSoft,
                              ),
                            ],
                          ),
                      ],
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      );
    },
  );

  Widget tableValue(
    num? value,
    double width,
    Color color, {
    VoidCallback? onTap,
  }) {
    final text = value == null ? '—' : format(value);
    return Material(
      color: color,
      child: InkWell(
        onTap: onTap,
        child: SizedBox(
          width: width,
          height: 52,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Align(
              alignment: Alignment.centerRight,
              child: Tooltip(
                message: value == null
                    ? 'Indisponible'
                    : (onTap == null ? '$text kWh' : 'Voir le détail'),
                child: Text(
                  text,
                  style: TextStyle(
                    color: value == null
                        ? AppColors.mutedText
                        : AppColors.textPrimary,
                    fontWeight: onTap == null
                        ? FontWeight.w700
                        : FontWeight.w500,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Future<void> detail(_Site site, String month) => showDialog<void>(
    context: context,
    builder: (context) {
      final cell = site.months[month] ?? const <String, dynamic>{};
      return AlertDialog(
        title: Text('${site.name} · ${monthLabel(month)}'),
        constraints: const BoxConstraints(maxWidth: 580),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                site.label,
                style: const TextStyle(color: AppColors.mutedText),
              ),
              const SizedBox(height: 12),
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: AppColors.tealSoft,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Text(
                  'Production retenue (kWh) · ${format(cell['actualKwh'] as num?)}',
                  style: const TextStyle(
                    color: AppColors.deepTeal,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Text(
                'Historique réel (kWh) · ${format(cell['historicalKwh'] as num?)}',
              ),
              Text('Prévu (kWh) · ${format(cell['expectedKwh'] as num?)}'),
              Text('Écart (kWh) · ${format(cell['differenceKwh'] as num?)}'),
              Text('Écart (%) · ${format(cell['differencePercent'] as num?)}'),
              const SizedBox(height: 12),
              Text(
                'Du ${cell['startDate'] ?? '—'} au ${cell['endDate'] ?? '—'}',
              ),
              Text('Jours concernés · ${cell['days'] ?? '—'}'),
              Text(
                'Jours avec historique positif · ${cell['positiveDays'] ?? '—'}',
              ),
              Text('Jours à compléter · ${cell['missingDays'] ?? '—'}'),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Fermer'),
          ),
        ],
      );
    },
  );

  Widget chart(List<_Site> sites, List<String> months) {
    var scale = 0.0;
    for (final site in sites) {
      for (final month in months) {
        final value = site.months[month]?[metric.field] as num?;
        if (value != null && value.abs() > scale)
          scale = value.abs().toDouble();
      }
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          'Graphique · ${metric.label}',
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        SizedBox(
          height: 280,
          child: ListView.builder(
            itemCount: months.length,
            itemBuilder: (context, index) => Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    monthLabel(months[index]),
                    style: const TextStyle(fontWeight: FontWeight.bold),
                  ),
                  for (final site in sites)
                    chartBar(
                      site,
                      site.months[months[index]]?[metric.field] as num?,
                      scale,
                    ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget chartBar(_Site site, num? value, double scale) {
    final ratio = scale == 0 || value == null ? 0.0 : value.abs() / scale;
    final color = comparisonSiteColor(site.key);
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        children: [
          Flexible(
            flex: 3,
            child: SizedBox(
              width: 220,
              child: Text(site.label, overflow: TextOverflow.ellipsis),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            flex: 5,
            child: Row(
              children: [
                if (metric == _Metric.variance)
                  Expanded(
                    child: Align(
                      alignment: Alignment.centerRight,
                      child: FractionallySizedBox(
                        widthFactor: value != null && value < 0 ? ratio : 0,
                        child: ColoredBox(
                          color: color,
                          child: const SizedBox(height: 10),
                        ),
                      ),
                    ),
                  ),
                if (metric == _Metric.variance)
                  const SizedBox(
                    width: 1,
                    height: 14,
                    child: ColoredBox(color: Colors.grey),
                  ),
                Expanded(
                  child: FractionallySizedBox(
                    alignment: Alignment.centerLeft,
                    widthFactor: value != null && value > 0 ? ratio : 0,
                    child: ColoredBox(
                      color: color,
                      child: const SizedBox(height: 10),
                    ),
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 94,
            child: Text(format(value), textAlign: TextAlign.end),
          ),
        ],
      ),
    );
  }

  void export(List<_Site> sites, List<String> months) {
    try {
      // Match the on-screen palette and keep the displayed values numeric.
      xlsx.ExcelColor color(Color value) => xlsx.ExcelColor.fromHexString(
        value.toARGB32().toRadixString(16).padLeft(8, '0').toUpperCase(),
      );
      xlsx.CellStyle style(
        Color background, {
        Color foreground = AppColors.textPrimary,
        bool bold = false,
        bool alignRight = false,
        bool numeric = false,
      }) => xlsx.CellStyle(
        fontFamily: 'Calibri',
        fontSize: 11,
        bold: bold,
        fontColorHex: color(foreground),
        backgroundColorHex: color(background),
        horizontalAlign: alignRight
            ? xlsx.HorizontalAlign.Right
            : xlsx.HorizontalAlign.Left,
        verticalAlign: xlsx.VerticalAlign.Center,
        numberFormat: numeric
            ? xlsx.NumFormat.defaultFloat
            : xlsx.NumFormat.standard_0,
      );

      final book = xlsx.Excel.createExcel();
      const sheetName = 'Analyse mensuelle';
      final sheet = book[sheetName];
      book.setDefaultSheet(sheetName);
      book.delete('Sheet1');

      void put(
        int row,
        int column,
        xlsx.CellValue value,
        xlsx.CellStyle style,
      ) {
        final cell = sheet.cell(
          xlsx.CellIndex.indexByColumnRow(columnIndex: column, rowIndex: row),
        );
        cell.value = value;
        cell.cellStyle = style;
      }

      final headers = [
        'Site / puissance',
        for (final month in months)
          metric == _Metric.energy
              ? monthLabel(month)
              : '${monthLabel(month)} · ${metric.label}',
        'Total kWh',
      ];
      final totalColumn = headers.length - 1;
      final siteHeader = style(
        AppColors.midnightNavy,
        foreground: AppColors.white,
        bold: true,
      );
      final valueHeader = style(
        AppColors.midnightNavy,
        foreground: AppColors.white,
        bold: true,
        alignRight: true,
      );
      for (final (column, label) in headers.indexed) {
        final width = column == 0
            ? 32.0
            : (label.length + 4).clamp(15, 40).toDouble();
        sheet.setColumnWidth(column, width);
        put(
          0,
          column,
          xlsx.TextCellValue(label),
          column == 0 ? siteHeader : valueHeader,
        );
      }
      sheet.setRowHeight(0, 30);

      final backgrounds = [AppColors.white, AppColors.softWhite];
      final siteStyles = [
        for (final background in backgrounds) style(background, bold: true),
      ];
      final monthStyles = [
        for (final background in backgrounds)
          style(background, alignRight: true, numeric: true),
      ];
      final totalStyle = style(
        AppColors.tealSoft,
        bold: true,
        alignRight: true,
        numeric: true,
      );
      xlsx.CellValue numericValue(num? value) => value == null
          ? xlsx.TextCellValue('—')
          : xlsx.DoubleCellValue(value.toDouble());

      for (final (index, site) in sites.indexed) {
        final row = index + 1;
        final stripe = index % 2;
        put(row, 0, xlsx.TextCellValue(site.label), siteStyles[stripe]);
        for (final (column, month) in months.indexed) {
          put(
            row,
            column + 1,
            numericValue(site.months[month]?[metric.field] as num?),
            monthStyles[stripe],
          );
        }
        put(row, totalColumn, numericValue(site.total(months)), totalStyle);
        sheet.setRowHeight(row, 29);
      }
      book.save(
        fileName: 'comparatif_mensuel_${monthKey(start)}_${monthKey(end)}.xlsx',
      );
    } catch (error) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('Export Excel impossible.')));
    }
  }
}

class _Site {
  _Site(Map<String, dynamic> json)
    : id = json['id'] as String?,
      code = json['siteCode'] as String? ?? '',
      brand = json['brand'] as String? ?? '',
      name = json['siteName'] as String? ?? json['siteCode'] as String? ?? '—',
      power = json['installedPowerKw'] as num?,
      zone = json['zone'] as String?,
      governorate = json['governorate'] as String?,
      months = {
        for (final month
            in (json['months'] as List).cast<Map<String, dynamic>>())
          month['month'] as String: month,
      };

  final String? id;
  final String code;
  final String brand;
  final String name;
  final num? power;
  final String? zone;
  final String? governorate;
  final Map<String, Map<String, dynamic>> months;

  String get key => id ?? '$brand:$code';
  String get label =>
      '$name · ${power == null || power! <= 0 ? '—' : power!.toStringAsFixed(2)} kWc';

  double? total(List<String> visibleMonths) {
    final values = visibleMonths
        .map((month) => months[month]?['actualKwh'])
        .whereType<num>();

    return values.isEmpty
        ? null
        : values.fold<double>(0.0, (sum, value) => sum + value.toDouble());
  }
}
