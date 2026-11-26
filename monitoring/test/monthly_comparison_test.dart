import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:monitoring/modules/comparatif/pages/monthly_comparison_page.dart';

void main() {
  testWidgets(
    'a monthly cell shows the retained production and collected history',
    (tester) async {
      await tester.binding.setSurfaceSize(const Size(1400, 1000));
      addTearDown(() => tester.binding.setSurfaceSize(null));
      await tester.pumpWidget(
        MaterialApp(
          home: MonthlyComparisonPage(
            load: (start, end) async => [
              {
                'siteCode': 'NE=1',
                'siteName': 'TUN_0001',
                'installedPowerKw': 5.0,
                'zone': 'Zone A',
                'governorate': 'TUN',
                'months': [
                  {
                    'month': start,
                    'actualKwh': 80.0,
                    'historicalKwh': 45.0,
                    'expectedKwh': 100.0,
                    'differenceKwh': -20.0,
                    'differencePercent': -20.0,
                    'days': 15,
                    'positiveDays': 8,
                    'missingDays': 7,
                  },
                ],
              },
            ],
          ),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.text('Tableau'), findsOneWidget);
      expect(find.text('Graphique · kWh'), findsNothing);
      expect(find.text('TUN_0001 · 5.00 kWc'), findsWidgets);
      final cell = find.text('80,00').first;
      await tester.ensureVisible(cell);
      await tester.tap(cell);
      await tester.pumpAndSettle();

      expect(find.text('Production retenue (kWh) · 80,00'), findsOneWidget);
      expect(find.text('Historique réel (kWh) · 45,00'), findsOneWidget);
      expect(find.text('Jours à compléter · 7'), findsOneWidget);

      await tester.tap(find.text('Fermer'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Graphique'));
      await tester.pumpAndSettle();
      expect(find.text('Graphique · kWh'), findsOneWidget);
    },
  );
}
