import 'dart:typed_data';

import 'package:excel/excel.dart' as xlsx;
import 'package:intl/intl.dart';

class SitesExcelRow {
  const SitesExcelRow({
    required this.name,
    required this.code,
    required this.brand,
    required this.status,
    required this.zone,
    required this.governor,
  });
  final String name;
  final String code;
  final String brand;
  final String status;
  final String zone;
  final String governor;
}

/// Creates a workbook from a snapshot; never fetches or changes site data.
class SitesExcelExport {
  SitesExcelExport._();

  static String statusLabel(String status) => switch (status) {
    'healthy' => 'Connecté',
    'disconnected' => 'Hors ligne',
    'faulty' => 'En défaut',
    _ => 'Inconnu',
  };

  static Uint8List build({
    required List<SitesExcelRow> rows,
    required Map<String, String> filters,
    required DateTime exportedAt,
    DateTime? refreshedAt,
  }) {
    final book = xlsx.Excel.createExcel();
    final sites = book['Sites'];
    final recap = book['Récapitulatif'];
    book.setDefaultSheet('Sites');
    book.delete('Sheet1');
    final date = DateFormat('dd/MM/yyyy HH:mm:ss');
    final titleStyle = _style(
      background: '142D44',
      foreground: 'FFFFFF',
      bold: true,
      size: 20,
    );
    final headerStyle = _style(
      background: '087F83',
      foreground: 'FFFFFF',
      bold: true,
    );

    // A separate summary keeps the site table easy to select and reuse.
    _mergedText(sites, 0, 6, 'VORA · LISTE DES SITES', titleStyle);
    sites.setRowHeight(0, 38);
    _mergedText(
      sites,
      1,
      6,
      '${rows.length} sites · Export du ${date.format(exportedAt)}',
      _style(background: 'E8F4F4', bold: true),
    );
    sites.setRowHeight(1, 26);
    _mergedText(
      sites,
      2,
      6,
      'Filtres et répartition détaillés dans l’onglet Récapitulatif.',
      _style(foreground: '64748B', size: 10),
    );
    const headers = [
      'N°',
      'Nom du site',
      'État',
      'Zone',
      'Gouvernorat',
      'Marque',
      'Code du site',
    ];
    const widths = [8.0, 48.0, 20.0, 20.0, 20.0, 18.0, 34.0];
    for (var c = 0; c < headers.length; c++) {
      sites.setColumnWidth(c, widths[c]);
      _put(sites, 4, c, xlsx.TextCellValue(headers[c]), headerStyle);
    }
    sites.setRowHeight(4, 28);
    for (var i = 0; i < rows.length; i++) {
      final row = rows[i];
      final r = i + 5;
      final style = _style(background: i.isEven ? 'FFFFFF' : 'F1F5F9');
      final values = <xlsx.CellValue>[
        xlsx.IntCellValue(i + 1), xlsx.TextCellValue(row.name),
        xlsx.TextCellValue(statusLabel(row.status)),
        xlsx.TextCellValue(row.zone),
        xlsx.TextCellValue(row.governor), xlsx.TextCellValue(row.brand),
        // TextCellValue also preserves leading zeroes and literal formula-like names.
        xlsx.TextCellValue(row.code),
      ];
      for (var c = 0; c < values.length; c++) {
        _put(sites, r, c, values[c], c == 2 ? _statusStyle(row.status) : style);
      }
      sites.setRowHeight(r, 34);
    }

    recap.setColumnWidth(0, 30);
    recap.setColumnWidth(1, 68);
    _mergedText(recap, 0, 1, 'VORA · RÉCAPITULATIF', titleStyle);
    recap.setRowHeight(0, 38);
    var r = 2;
    void detail(String label, xlsx.CellValue value) {
      _put(
        recap,
        r,
        0,
        xlsx.TextCellValue(label),
        _style(background: 'E8F4F4', bold: true),
      );
      _put(recap, r, 1, value, _style());
      recap.setRowHeight(r++, 30);
    }

    detail('Date d’export', xlsx.TextCellValue(date.format(exportedAt)));
    detail(
      'Actualisation de la liste',
      xlsx.TextCellValue(
        refreshedAt == null ? 'Non disponible' : date.format(refreshedAt),
      ),
    );
    detail('Nombre de sites', xlsx.IntCellValue(rows.length));
    for (final state in ['healthy', 'disconnected', 'faulty', 'unknown']) {
      final count = rows
          .where(
            (row) => state == 'unknown'
                ? !['healthy', 'disconnected', 'faulty'].contains(row.status)
                : row.status == state,
          )
          .length;
      detail(statusLabel(state), xlsx.IntCellValue(count));
    }
    r++;
    _mergedText(recap, r++, 1, 'FILTRES APPLIQUÉS', headerStyle);
    for (final entry in filters.entries) {
      detail(entry.key, xlsx.TextCellValue(entry.value));
    }
    r++;
    detail(
      'Origine des états',
      xlsx.TextCellValue('États enregistrés dans VORA au moment de l’export.'),
    );
    detail(
      'Zones et gouvernorats',
      xlsx.TextCellValue(
        'Même classification que les filtres de la page Sites. Les valeurs absentes sont indiquées « Non renseigné(e) ».',
      ),
    );
    recap.setRowHeight(r - 1, 48);
    final bytes = book.encode();
    if (bytes == null || bytes.isEmpty)
      throw StateError('Excel encoding failed');
    return Uint8List.fromList(bytes);
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

  static xlsx.CellStyle _statusStyle(String status) => switch (status) {
    'healthy' => _style(background: 'DCFCE7', foreground: '166534', bold: true),
    'disconnected' => _style(
      background: 'E2E8F0',
      foreground: '475569',
      bold: true,
    ),
    'faulty' => _style(background: 'FEE2E2', foreground: '991B1B', bold: true),
    _ => _style(background: 'FEF3C7', foreground: '92400E', bold: true),
  };

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
