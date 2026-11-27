import 'package:flutter_test/flutter_test.dart';
import '../lib/data/models/performance_report.dart';

void main() {
  test('Missing both dates marks report values unavailable', () {
    final row = PerformanceReportRow.fromJson({
      'siteCode': 'TEST',
      'dataStatus': 'MISSING_ORIGIN_DATE',
      'expectedKwh': null,
      'capturedKwh': null,
      'estimatedMissingKwh': null,
      'actualKwh': null,
    });
    expect(row.hasMissingOriginDate, isTrue);
    expect(row.hasProductionOriginDate, isFalse);
    expect(row.hasMissingProductionDates, isTrue);
    expect(row.hasMissingData, isTrue);
    expect(row.expectedKwh, isNull);
    expect(row.capturedKwh, isNull);
    expect(row.estimatedMissingKwh, isNull);
    expect(row.actualKwh, isNull);
  });

  test('A single MES is a valid origin even without a swap', () {
    final row = PerformanceReportRow.fromJson({
      'gridConnectionDate': '2025-01-05',
      'swapDate': null,
      'productionStartDate': '2025-01-05',
      'capturedKwh': 0,
      'estimatedMissingKwh': 84,
      'expectedKwh': 100,
      'actualKwh': 84,
      'dataStatus': 'OK',
    });
    expect(row.hasMissingOriginDate, isFalse);
    expect(row.hasMissingProductionDates, isFalse);
    expect(row.hasProductionOriginDate, isTrue);
    expect(row.actualKwh, 84);
  });

  test('A single swap is a valid origin and a future origin still exists', () {
    final row = PerformanceReportRow.fromJson({
      'swapDate': '2027-01-01',
      'gridConnectionDate': null,
      'dataStatus': 'NO_PRODUCTION_IN_FILTER',
      'actualKwh': 0,
    });
    expect(row.hasProductionOriginDate, isTrue);
    expect(row.hasMissingProductionDates, isFalse);
  });
}
