import 'package:flutter_test/flutter_test.dart';
import 'package:excel/excel.dart' as xlsx;
import 'package:monitoring/core/utils/planning_excel_export.dart';

void main() {
  test('exports missions once, keeps sites separate and treats names as text', () {
    final bytes = PlanningExcelExport.build(tasks: [{
      'id': 'mission-001', 'status': 'Partiellement réalisé', 'teamName': 'Équipe A',
      'plannedDate': '2026-10-01', 'description': '=1+1',
      'sites': [
        {'siteName': 'Site manuel', 'siteCode': 'manual_001', 'brand': 'MANUAL'},
        {'siteName': 'Site Huawei', 'siteCode': 'NE=123', 'brand': 'HUAWEI'},
      ],
    }], filters: {'Statut': 'Partiellement réalisé'}, exportedAt: DateTime(2026, 11, 2));
    final book = xlsx.Excel.decodeBytes(bytes);
    expect(book.tables.keys, containsAll(['Interventions', 'Sites des missions', 'Récapitulatif']));
    final tasks = book['Interventions'];
    expect(tasks.rows.length, 6);
    expect(tasks.rows[5][0]!.value, isA<xlsx.IntCellValue>());
    expect(tasks.rows[5][11]!.value, isA<xlsx.TextCellValue>());
    expect((tasks.rows[5][11]!.value as xlsx.TextCellValue).value.toString(), '=1+1');
    expect(book['Sites des missions'].rows.length, 5);
  });
}
