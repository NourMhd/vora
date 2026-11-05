import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:monitoring/data/models/daily_comparison_report.dart';
import 'package:monitoring/modules/comparatif/widgets/site_selection_dialog.dart';
import 'package:monitoring/modules/comparatif/widgets/sticky_comparison_table.dart';

DailyComparisonSelectableSite site(int i) => DailyComparisonSelectableSite(
  brand: 'HUAWEI',
  siteCode: 'SITE_$i',
  siteName: 'Site $i',
  swapDate: null,
  gridConnectionDate: null,
  installedPowerKw: 2,
  governorate: i.isEven ? 'Tunis' : 'Ariana',
  zone: 'A',
);

void main() {
  testWidgets(
    'Recherche et sélection groupée : brouillon isolé et validation',
    (tester) async {
      final original = {'SITE_0'};
      Set<String>? result;
      await tester.pumpWidget(
        MaterialApp(
          home: Builder(
            builder: (context) => Scaffold(
              body: TextButton(
                onPressed: () async {
                  result = await showDialog<Set<String>>(
                    context: context,
                    builder: (_) => SiteSelectionDialog(
                      sites: List.generate(8, site),
                      selected: original,
                    ),
                  );
                },
                child: const Text('Ouvrir'),
              ),
            ),
          ),
        ),
      );
      await tester.tap(find.text('Ouvrir'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField), 'Ariana');
      await tester.pump();
      expect(find.text('4 résultats · 1 sélectionnés'), findsOneWidget);
      await tester.tap(find.text('Sélectionner les résultats'));
      await tester.pump();
      expect(original, {'SITE_0'});
      await tester.tap(find.text('Valider la sélection'));
      await tester.pumpAndSettle();
      expect(result, {'SITE_0', 'SITE_1', 'SITE_3', 'SITE_5', 'SITE_7'});
    },
  );

  testWidgets('Annuler préserve la sélection active', (tester) async {
    final original = {'SITE_0'};
    Set<String>? result;
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: TextButton(
              onPressed: () async {
                result = await showDialog<Set<String>>(
                  context: context,
                  builder: (_) => SiteSelectionDialog(
                    sites: List.generate(8, site),
                    selected: original,
                  ),
                );
              },
              child: const Text('Ouvrir'),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Ouvrir'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Tout désélectionner'));
    await tester.pump();
    expect(
      tester
          .widget<FilledButton>(
            find.widgetWithText(FilledButton, 'Valider la sélection'),
          )
          .onPressed,
      isNull,
    );
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    expect(result, isNull);
    expect(original, {'SITE_0'});
  });

  testWidgets(
    'En-tête épinglé sur la page, dates fixes et colonnes synchronisées',
    (tester) async {
      final sites = List.generate(8, site);
      final rows = List.generate(
        60,
        (i) => DailyComparisonRow(
          date: '2026-01-01',
          label: 'Jour $i',
          cells: sites
              .map(
                (s) => DailyComparisonCell(
                  siteCode: s.siteCode,
                  value: 10,
                  displayValue: 'Valeur ${s.siteCode}',
                  estimated: false,
                  status: 'REAL',
                ),
              )
              .toList(),
        ),
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: CustomScrollView(
              slivers: [
                const SliverToBoxAdapter(child: SizedBox(height: 200)),
                StickyComparisonTable(
                  title: 'Réel',
                  subtitle: 'Historique',
                  sites: sites,
                  rows: rows,
                  totalRow: null,
                ),
                const SliverToBoxAdapter(child: SizedBox(height: 700)),
              ],
            ),
          ),
        ),
      );
      await tester.drag(find.byType(CustomScrollView), const Offset(0, -500));
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(find.text('Réel')).dy, lessThan(60));
      final dateFinder = find.text('Jour 10');
      final dateX = tester.getTopLeft(dateFinder).dx;
      final valueFinder = find.text('Valeur SITE_2').first;
      final before = tester.getTopLeft(valueFinder).dx;
      await tester.tap(find.byTooltip('Colonnes suivantes'));
      await tester.pumpAndSettle();
      expect(tester.getTopLeft(dateFinder).dx, dateX);
      expect(tester.getTopLeft(valueFinder).dx, lessThan(before));
      expect(tester.takeException(), isNull);
      await tester.drag(find.byType(CustomScrollView), const Offset(0, -4000));
      await tester.pumpAndSettle();
      expect(find.text('Réel').hitTestable(), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );
}
