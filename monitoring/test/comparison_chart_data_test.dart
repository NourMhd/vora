import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:monitoring/data/models/daily_comparison_report.dart';
import 'package:monitoring/modules/comparatif/widgets/comparison_charts.dart';

void main() {
  testWidgets('Courbes : sélection > 5 sites, rendement et estimations', (
    tester,
  ) async {
    final sites = List.generate(
      8,
      (i) => DailyComparisonSite(
        brand: 'HUAWEI',
        siteCode: 'SITE_$i',
        siteName: 'Site $i',
        swapDate: null,
        gridConnectionDate: null,
        installedPowerKw: i == 0 ? null : 2,
        governorate: null,
        zone: null,
      ),
    );
    DailyComparisonRow row(String date, double? v, bool estimated) =>
        DailyComparisonRow(
          date: date,
          label: date,
          cells: sites
              .map(
                (s) => DailyComparisonCell(
                  siteCode: s.siteCode,
                  value: v,
                  displayValue: '$v',
                  estimated: estimated,
                  status: estimated ? 'ESTIMATED' : 'REAL',
                ),
              )
              .toList(),
        );
    final report = DailyComparisonReport(
      sites: sites,
      startDate: '2026-10-01',
      endDate: '2026-10-03',
      historicalRows: [
        row('2026-10-01', 10, false),
        row('2026-10-02', null, false),
        row('2026-10-03', 0, false),
      ],
      estimatedRows: [
        row('2026-10-01', 10, false),
        row('2026-10-02', 8, true),
        row('2026-10-03', 0, false),
      ],
      historicalTotals: null,
      estimatedTotals: null,
    );
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(child: ComparisonCharts(report: report)),
        ),
      ),
    );
    expect(find.text('8 sites sélectionnés'), findsOneWidget);
    await tester.ensureVisible(find.text('Détails par site'));
    await tester.tap(find.text('Détails par site'));
    await tester.pump();
    expect(find.text('10.00 kWh sur la période'), findsWidgets);
    expect(find.text('2/3 jours renseignés · 0 estimés'), findsWidgets);
    await tester.ensureVisible(find.text('Inclure les estimations'));
    await tester.tap(find.text('Inclure les estimations'));
    await tester.pump();
    expect(find.text('18.00 kWh sur la période'), findsWidgets);
    expect(find.text('3/3 jours renseignés · 1 estimés'), findsWidgets);
    await tester.ensureVisible(find.text('Rendement · kWh/kWc'));
    await tester.tap(find.text('Rendement · kWh/kWc'));
    await tester.pump();
    expect(find.text('Aucune valeur exploitable'), findsOneWidget);
    expect(find.text('9.00 kWh/kWc sur la période'), findsWidgets);
    expect(tester.takeException(), isNull);
  });
}
