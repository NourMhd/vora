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
    expect(row.hasMissingProductionDates, isTrue);
    expect(row.hasMissingData, isTrue);
    expect(row.expectedKwh, isNull);
    expect(row.capturedKwh, isNull);
    expect(row.estimatedMissingKwh, isNull);
    expect(row.actualKwh, isNull);
  });

  test('A single MES with allocated energy is not marked as missing dates', () {
    final row = PerformanceReportRow.fromJson({
      'gridConnectionDate': '2025-01-05',
      'swapDate': null,
      'productionStartDate': '2025-01-05',
      'capturedKwh': 100,
      'actualKwh': 100,
      'dataStatus': 'MISSING_EXPECTED_KWH',
    });
    expect(row.hasMissingOriginDate, isFalse);
    expect(row.hasMissingProductionDates, isFalse);
    expect(row.actualKwh, 100);
  });
}
