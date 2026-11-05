import 'dart:typed_data';
import 'package:excel/excel.dart' as xlsx;
import 'package:intl/intl.dart';

/// An immutable filtered snapshot, one mission per row, no network access.
class PlanningExcelExport {
  PlanningExcelExport._();
  static Uint8List build({required List<Map<String, dynamic>> tasks,
    required Map<String, String> filters, required DateTime exportedAt}) {
    final book = xlsx.Excel.createExcel();
    final main = book['Interventions'];
    final detail = book['Sites des missions'];
    final summary = book['Récapitulatif'];
    book.setDefaultSheet('Interventions');
    book.delete('Sheet1');
    final title = _style(background: '142D44', foreground: 'FFFFFF', bold: true, size: 18);
    final header = _style(background: '087F83', foreground: 'FFFFFF', bold: true);
    const headers = ['N°', 'Référence tâche', 'Date prévue', 'Sites', 'Codes sites',
      'Équipe', 'Responsable', 'Donneur d’ordre', 'Projet', 'Zone', 'Type de travaux',
      'Description', 'Priorité', 'Statut', 'Date de réalisation', 'Date de report',
      'Écart (jours)', 'Motif', 'Commentaire', 'Action', 'Date de création'];
    const widths = [7.0, 32.0, 16.0, 48.0, 44.0, 25.0, 25.0, 27.0, 27.0, 18.0,
      29.0, 55.0, 15.0, 25.0, 20.0, 18.0, 16.0, 40.0, 45.0, 40.0, 23.0];
    _mergedText(main, 0, headers.length - 1, 'VORA · PLANNING TERRAIN', title);
    main.setRowHeight(0, 38);
    _mergedText(main, 1, headers.length - 1,
      '${tasks.length} tâche(s) · Export du ${DateFormat('dd/MM/yyyy HH:mm').format(exportedAt)} · Toutes les pages des résultats filtrés',
      _style(background: 'E8F4F4', bold: true));
    _mergedText(main, 2, headers.length - 1, 'Dates : AAAA-MM-JJ. Écart = réalisation − date prévue ; pour une tâche ouverte en retard, date de l’export − date prévue.', _style(size: 10));
    for (var c = 0; c < headers.length; c++) {
      main.setColumnWidth(c, widths[c]);
      _put(main, 4, c, xlsx.TextCellValue(headers[c]), header);
    }
    const detailHeaders = ['Référence tâche', 'N° mission', 'Nom du site', 'Code du site', 'Marque / origine', 'État de la tâche'];
    _mergedText(detail, 0, 5, 'VORA · SITES DES MISSIONS', title);
    detail.setRowHeight(0, 38);
    for (var c = 0; c < detailHeaders.length; c++) {
      detail.setColumnWidth(c, c == 2 ? 45 : 30);
      _put(detail, 2, c, xlsx.TextCellValue(detailHeaders[c]), header);
    }
    var detailRow = 3;
    final states = <String, int>{};
    final teamCounts = <String, int>{};
    var late = 0;
    for (var i = 0; i < tasks.length; i++) {
      final t = tasks[i];
      final sites = _sites(t);
      final state = _text(t['status']);
      states[state] = (states[state] ?? 0) + 1;
      final team = _text(t['teamName']);
      teamCounts[team] = (teamCounts[team] ?? 0) + 1;
      final planned = _day(t['plannedDate']);
      final completed = _day(t['completedDate']);
      final today = DateTime(exportedAt.year, exportedAt.month, exportedAt.day);
      final closed = state == 'Réalisé';
      final isLate = !closed && planned != null && planned.isBefore(today);
      if (isLate) late++;
      int? gap;
      if (planned != null && (completed != null || isLate)) {
        final end = closed ? completed : today;
        if (end != null) gap = DateTime.utc(end.year, end.month, end.day)
          .difference(DateTime.utc(planned.year, planned.month, planned.day)).inDays;
      }
      final values = <xlsx.CellValue>[
        xlsx.IntCellValue(i + 1), xlsx.TextCellValue(_text(t['id'])),
        xlsx.TextCellValue(_date(t['plannedDate'])),
        xlsx.TextCellValue(sites.map((s) => _text(s['siteName'])).join('\n')),
        xlsx.TextCellValue(sites.map((s) => _text(s['siteCode'])).join('\n')),
        for (final key in ['teamName', 'manager', 'missionIssuer', 'project', 'zone', 'type', 'description', 'priority', 'status'])
          xlsx.TextCellValue(_text(t[key])),
        xlsx.TextCellValue(_date(t['completedDate'])), xlsx.TextCellValue(_date(t['postponedDate'])),
        if (gap != null) xlsx.IntCellValue(gap) else xlsx.TextCellValue(''),
        for (final key in ['reason', 'comment', 'action']) xlsx.TextCellValue(_text(t[key])),
        xlsx.TextCellValue(_text(t['createdAt'])),
      ];
      final stripe = _style(background: i.isEven ? 'FFFFFF' : 'F1F5F9');
      for (var c = 0; c < values.length; c++) {
        _put(main, i + 5, c, values[c], c == 13 ? _statusStyle(state) : stripe);
      }
      main.setRowHeight(i + 5, sites.length > 2 ? 80 : 54);
      for (final site in sites) {
        final vals = <xlsx.CellValue>[xlsx.TextCellValue(_text(t['id'])), xlsx.IntCellValue(i + 1),
          xlsx.TextCellValue(_text(site['siteName'])), xlsx.TextCellValue(_text(site['siteCode'])),
          xlsx.TextCellValue(site['brand'] == 'MANUAL' ? 'Manuel · Planning' : _text(site['brand'])), xlsx.TextCellValue(state)];
        for (var c = 0; c < vals.length; c++) { _put(detail, detailRow, c, vals[c], stripe); }
        detail.setRowHeight(detailRow++, 30);
      }
    }
    summary.setColumnWidth(0, 35); summary.setColumnWidth(1, 65);
    _mergedText(summary, 0, 1, 'VORA · RÉCAPITULATIF', title);
    summary.setRowHeight(0, 38);
    var row = 2;
    void entry(String label, xlsx.CellValue value) {
      _put(summary, row, 0, xlsx.TextCellValue(label), _style(bold: true));
      _put(summary, row++, 1, value, _style());
    }
    entry('Export', xlsx.TextCellValue(DateFormat('dd/MM/yyyy HH:mm:ss').format(exportedAt)));
    entry('Nombre de tâches', xlsx.IntCellValue(tasks.length));
    entry('Tâches ouvertes en retard', xlsx.IntCellValue(late));
    for (final filter in filters.entries) { entry('Filtre · ${filter.key}', xlsx.TextCellValue(filter.value)); }
    row++;
    for (final state in states.entries) { entry('Statut · ${state.key}', xlsx.IntCellValue(state.value)); }
    row++;
    for (final team in teamCounts.entries) { entry('Équipe · ${team.key}', xlsx.IntCellValue(team.value)); }
    final bytes = book.encode();
    if (bytes == null || bytes.isEmpty) throw StateError('Encodage Excel impossible');
    return Uint8List.fromList(bytes);
  }
  static String _text(dynamic value) => value?.toString() ?? '';
  static DateTime? _day(dynamic value) => DateTime.tryParse(_text(value));
  static String _date(dynamic value) {
    final d = _day(value);
    return d == null ? '' : DateFormat('yyyy-MM-dd').format(d);
  }
  static List<Map<String, dynamic>> _sites(Map<String, dynamic> task) {
    final value = task['sites'];
    if (value is List && value.isNotEmpty) return value.whereType<Map>().map((s) => Map<String, dynamic>.from(s)).toList();
    final legacy = _text(task['site']);
    return legacy.isEmpty ? [] : [{'siteName': legacy, 'siteCode': '', 'brand': ''}];
  }
  static xlsx.CellStyle _statusStyle(String state) {
    if (state == 'Réalisé') return _style(background: 'DCFCE7', foreground: '166534', bold: true);
    if (state == 'Bloqué' || state == 'Non réalisé') return _style(background: 'FEE2E2', foreground: '991B1B', bold: true);
    return _style(background: 'FEF3C7', foreground: '92400E', bold: true);
  }
  static xlsx.CellStyle _style({
    String background = 'FFFFFF',
    String foreground = '142D44',
    bool bold = false,
    int size = 11,
  }) => xlsx.CellStyle(
    fontFamily: 'Calibri',
    fontSize: size,
    bold: bold,
    fontColorHex: xlsx.ExcelColor.fromHexString('FF$foreground'),
    backgroundColorHex: xlsx.ExcelColor.fromHexString('FF$background'),
    verticalAlign: xlsx.VerticalAlign.Center,
    textWrapping: xlsx.TextWrapping.WrapText,
  );

  static void _put(
    xlsx.Sheet sheet,
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

  static void _mergedText(
    xlsx.Sheet sheet,
    int row,
    int lastColumn,
    String text,
    xlsx.CellStyle style,
  ) {
    for (var c = 0; c <= lastColumn; c++) {
      _put(sheet, row, c, xlsx.TextCellValue(c == 0 ? text : ''), style);
    }
    sheet.merge(
      xlsx.CellIndex.indexByColumnRow(columnIndex: 0, rowIndex: row),
      xlsx.CellIndex.indexByColumnRow(columnIndex: lastColumn, rowIndex: row),
    );
  }
}
