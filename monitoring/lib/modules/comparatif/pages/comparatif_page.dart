import 'package:excel/excel.dart' as xlsx;
import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../data/models/daily_comparison_report.dart';
import '../controllers/comparatif_controller.dart';

const double _dateColumnWidth = 130;
const double _siteColumnWidth = 230;

class ComparatifPage extends GetView<ComparatifController> {
  const ComparatifPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      if (controller.isLoading.value && controller.report.value.sites.isEmpty) {
        return const LoadingState(label: 'Chargement comparatif ARI...');
      }

      final error = controller.errorMessage.value;
      if (error != null && controller.report.value.sites.isEmpty) {
        return ErrorState(message: error, onRetry: controller.load);
      }

      final report = controller.report.value;

      return Scaffold(
        backgroundColor: AppColors.softWhite,
        body: SafeArea(
          child: RefreshIndicator(
            onRefresh: controller.refreshData,
            child: ListView(
              padding: const EdgeInsets.all(24),
              children: [
                _Header(report: report, controller: controller),
                const SizedBox(height: 18),
                _SiteSelectorCard(controller: controller),
                const SizedBox(height: 18),
                if (report.sites.isEmpty)
                  const EmptyState(
                    title: 'Aucun site sélectionné',
                    message: 'Sélectionne au moins un site ARI puis clique sur Appliquer.',
                    icon: Icons.check_box_outline_blank_rounded,
                  )
                else ...[
                  _InfoStrip(report: report),
                  const SizedBox(height: 18),
                  _ComparisonTableCard(
                    title: 'Tableau historique original',
                    subtitle: 'Valeurs journalières telles qu’elles existent dans l’historique collecté, sans estimation.',
                    rows: report.historicalRows,
                    totalRow: report.historicalTotals,
                    sites: report.sites,
                  ),
                  const SizedBox(height: 18),
                  _ComparisonTableCard(
                    title: 'Tableau avec valeurs estimables',
                    subtitle: 'Estime seulement les jours 0, null ou absents : (moyenne réelle du mois + théorique du jour) / 2, sinon 70% du théorique.',
                    rows: report.estimatedRows,
                    totalRow: report.estimatedTotals,
                    sites: report.sites,
                  ),
                ],
              ],
            ),
          ),
        ),
      );
    });
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.report, required this.controller});

  final DailyComparisonReport report;
  final ComparatifController controller;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                'Comparatif',
                style: TextStyle(
                  color: AppColors.midnightNavy,
                  fontSize: 28,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(height: 6),
              Text(
                '${_siteNames(report.sites)} · du ${_displayIsoDate(report.startDate)} au ${_displayIsoDate(report.endDate)} · jour présent exclu',
                style: const TextStyle(
                  color: AppColors.mutedText,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        ),
        Obx(
          () => OutlinedButton.icon(
            onPressed: controller.isRefreshing.value ? null : controller.refreshData,
            icon: controller.isRefreshing.value
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh_rounded),
            label: const Text('Actualiser'),
          ),
        ),
        const SizedBox(width: 10),
        FilledButton.icon(
          onPressed: report.sites.isEmpty ? null : () => _exportExcel(report),
          icon: const Icon(Icons.file_download_outlined, size: 18),
          label: const Text('Télécharger Excel'),
        ),
      ],
    );
  }
}

class _InfoStrip extends StatelessWidget {
  const _InfoStrip({required this.report});

  final DailyComparisonReport report;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: report.sites.map((site) {
        return Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
          decoration: BoxDecoration(
            color: AppColors.white,
            borderRadius: BorderRadius.circular(18),
            border: Border.all(color: AppColors.borderGray),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.solar_power_rounded, color: AppColors.deepTeal, size: 18),
              const SizedBox(width: 8),
              Text(
                site.label,
                style: const TextStyle(
                  color: AppColors.midnightNavy,
                  fontWeight: FontWeight.w900,
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '${site.powerLabel} · swap: ${_displayIsoDate(site.swapDate)}',
                style: const TextStyle(
                  color: AppColors.mutedText,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ],
          ),
        );
      }).toList(),
    );
  }
}


class _SiteSelectorCard extends StatelessWidget {
  const _SiteSelectorCard({required this.controller});

  final ComparatifController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Obx(() {
        final sites = controller.availableSites.toList();
        final selectedCount = controller.selectedSiteCodes.length;

        if (sites.isEmpty) {
          return const Text(
            'Aucun site ARI disponible pour la sélection.',
            style: TextStyle(
              color: AppColors.mutedText,
              fontWeight: FontWeight.w700,
            ),
          );
        }

        final grouped = _groupSelectableSites(sites);

        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Sites à comparer',
                        style: TextStyle(
                          color: AppColors.midnightNavy,
                          fontSize: 18,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      SizedBox(height: 4),
                      Text(
                        'Liste organisée par zone, gouvernorat, puis puissance crête décroissante.',
                        style: TextStyle(
                          color: AppColors.mutedText,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                ),
                Text(
                  '$selectedCount sélectionné(s)',
                  style: const TextStyle(
                    color: AppColors.deepTeal,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(width: 12),
                FilledButton.icon(
                  onPressed: controller.isRefreshing.value ? null : controller.applySelection,
                  icon: const Icon(Icons.check_rounded, size: 18),
                  label: const Text('Appliquer'),
                ),
              ],
            ),
            const SizedBox(height: 16),
            _DateRangeFilter(controller: controller),
            const SizedBox(height: 12),
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 360),
              child: Scrollbar(
                thumbVisibility: true,
                child: ListView(
                  shrinkWrap: true,
                  children: grouped.entries.map((zoneEntry) {
                    final zone = zoneEntry.key;
                    final governorates = zoneEntry.value;
                    return ExpansionTile(
                      initiallyExpanded: true,
                      tilePadding: EdgeInsets.zero,
                      title: Text(
                        zone,
                        style: const TextStyle(
                          color: AppColors.midnightNavy,
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      children: governorates.entries.map((govEntry) {
                        final governorate = govEntry.key;
                        final govSites = govEntry.value;
                        return ExpansionTile(
                          initiallyExpanded: true,
                          tilePadding: const EdgeInsets.only(left: 16),
                          childrenPadding: const EdgeInsets.only(left: 16),
                          title: Text(
                            governorate,
                            style: const TextStyle(
                              color: AppColors.slateGraphite,
                              fontWeight: FontWeight.w900,
                            ),
                          ),
                          children: govSites.map((site) {
                            return CheckboxListTile(
                              dense: true,
                              contentPadding: EdgeInsets.zero,
                              value: controller.isSelected(site.siteCode),
                              onChanged: (value) => controller.toggleSite(site.siteCode, value == true),
                              title: Text(
                                site.label,
                                style: const TextStyle(
                                  color: AppColors.midnightNavy,
                                  fontWeight: FontWeight.w800,
                                ),
                              ),
                              subtitle: Text(
                                '${site.powerLabel} · swap: ${_displayIsoDate(site.swapDate)}',
                                style: const TextStyle(
                                  color: AppColors.mutedText,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            );
                          }).toList(),
                        );
                      }).toList(),
                    );
                  }).toList(),
                ),
              ),
            ),
          ],
        );
      }),
    );
  }
}

class _DateRangeFilter extends StatelessWidget {
  const _DateRangeFilter({required this.controller});

  final ComparatifController controller;

  @override
  Widget build(BuildContext context) {
    final yesterday = controller.yesterday;
    final startDate = controller.selectedStartDate.value;
    final endDate = controller.selectedEndDate.value;

    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.softWhite,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Période de comparaison',
            style: TextStyle(
              color: AppColors.midnightNavy,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            'Par défaut, le comparatif commence à la date de swap la plus ancienne des sites sélectionnés et se termine hier.',
            style: TextStyle(
              color: AppColors.mutedText,
              fontSize: 12,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              _NullableDatePickerButton(
                width: 190,
                label: 'Date début',
                value: startDate,
                placeholder: 'Auto: swap le + ancien',
                firstDate: DateTime(2000, 1, 1),
                lastDate: endDate ?? yesterday,
                fallbackDate: endDate ?? yesterday,
                onSelected: controller.setStartDate,
              ),
              _NullableDatePickerButton(
                width: 190,
                label: 'Date fin',
                value: endDate,
                placeholder: 'Auto: hier',
                firstDate: startDate ?? DateTime(2000, 1, 1),
                lastDate: yesterday,
                fallbackDate: yesterday,
                onSelected: controller.setEndDate,
              ),
              OutlinedButton.icon(
                onPressed: startDate == null && endDate == null ? null : controller.clearDateRange,
                icon: const Icon(Icons.restart_alt_rounded, size: 18),
                label: const Text('Réinitialiser période'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _NullableDatePickerButton extends StatelessWidget {
  const _NullableDatePickerButton({
    required this.width,
    required this.label,
    required this.value,
    required this.placeholder,
    required this.firstDate,
    required this.lastDate,
    required this.fallbackDate,
    required this.onSelected,
  });

  final double width;
  final String label;
  final DateTime? value;
  final String placeholder;
  final DateTime firstDate;
  final DateTime lastDate;
  final DateTime fallbackDate;
  final ValueChanged<DateTime> onSelected;

  @override
  Widget build(BuildContext context) {
    final initialDate = _clampDate(value ?? fallbackDate, firstDate, lastDate);

    return SizedBox(
      width: width,
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () async {
          final selected = await showDatePicker(
            context: context,
            initialDate: initialDate,
            firstDate: firstDate,
            lastDate: lastDate,
          );

          if (selected != null) {
            onSelected(selected);
          }
        },
        child: InputDecorator(
          decoration: InputDecoration(
            labelText: label,
            suffixIcon: const Icon(Icons.calendar_today_rounded, size: 18),
          ),
          child: Text(
            value == null ? placeholder : Formatters.date(value),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: value == null ? AppColors.mutedText : AppColors.midnightNavy,
              fontWeight: FontWeight.w800,
            ),
          ),
        ),
      ),
    );
  }
}

DateTime _clampDate(DateTime value, DateTime firstDate, DateTime lastDate) {
  if (value.isBefore(firstDate)) return firstDate;
  if (value.isAfter(lastDate)) return lastDate;
  return value;
}

class _ComparisonTableCard extends StatelessWidget {
  const _ComparisonTableCard({
    required this.title,
    required this.subtitle,
    required this.rows,
    required this.totalRow,
    required this.sites,
  });

  final String title;
  final String subtitle;
  final List<DailyComparisonRow> rows;
  final DailyComparisonRow? totalRow;
  final List<DailyComparisonSite> sites;

  @override
  Widget build(BuildContext context) {
    final allRows = [
      ...rows,
      if (totalRow != null) totalRow!,
    ];

    return PremiumCard(
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(22, 22, 22, 12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontSize: 20,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  subtitle,
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1, color: AppColors.borderGray),
          if (allRows.isEmpty)
            const SizedBox(
              height: 180,
              child: EmptyState(
                title: 'Aucune ligne',
                message: 'Aucune journée complète disponible pour cette période.',
                icon: Icons.table_rows_outlined,
              ),
            )
          else
            Scrollbar(
              thumbVisibility: true,
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.only(bottom: 12),
                child: DataTable(
                  headingRowColor: MaterialStateProperty.all(AppColors.softWhite),
                  dataRowMinHeight: 44,
                  dataRowMaxHeight: 66,
                  columnSpacing: 24,
                  horizontalMargin: 22,
                  columns: [
                    const DataColumn(
                      label: SizedBox(
                        width: _dateColumnWidth,
                        child: _HeaderLabel('Date'),
                      ),
                    ),
                    ...sites.map(
                      (site) => DataColumn(
                        label: SizedBox(
                          width: _siteColumnWidth,
                          child: _HeaderLabel(site.headerLabel),
                        ),
                      ),
                    ),
                  ],
                  rows: allRows.map((row) {
                    final isTotal = row.label.toUpperCase() == 'TOTAL';
                    return DataRow(
                      color: isTotal ? MaterialStateProperty.all(AppColors.tealSoft) : null,
                      cells: [
                        DataCell(
                          SizedBox(
                            width: _dateColumnWidth,
                            child: Text(
                              row.label,
                              style: TextStyle(
                                color: AppColors.midnightNavy,
                                fontWeight: isTotal ? FontWeight.w900 : FontWeight.w800,
                              ),
                            ),
                          ),
                        ),
                        ...sites.map((site) {
                          final cell = _cellFor(row, site.siteCode);
                          return DataCell(
                            SizedBox(
                              width: _siteColumnWidth,
                              child: _ValueText(cell: cell, isTotal: isTotal),
                            ),
                          );
                        }),
                      ],
                    );
                  }).toList(),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _HeaderLabel extends StatelessWidget {
  const _HeaderLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      maxLines: 2,
      overflow: TextOverflow.visible,
      softWrap: true,
      style: const TextStyle(
        color: AppColors.midnightNavy,
        fontWeight: FontWeight.w900,
      ),
    );
  }
}

class _ValueText extends StatelessWidget {
  const _ValueText({required this.cell, required this.isTotal});

  final DailyComparisonCell? cell;
  final bool isTotal;

  @override
  Widget build(BuildContext context) {
    final value = cell?.displayValue ?? '—';
    final estimated = cell?.estimated == true;
    final status = cell?.status ?? '';
    final notSwapped = status == 'NOT_SWAPPED_YET';

    return Text(
      value,
      textAlign: TextAlign.left,
      maxLines: 2,
      overflow: TextOverflow.visible,
      softWrap: true,
      style: TextStyle(
        color: isTotal
            ? AppColors.midnightNavy
            : estimated
                ? AppColors.warning
                : notSwapped
                    ? AppColors.mutedText
                    : AppColors.slateGraphite,
        fontWeight: isTotal || estimated ? FontWeight.w900 : FontWeight.w700,
        fontStyle: notSwapped ? FontStyle.italic : FontStyle.normal,
      ),
    );
  }
}

DailyComparisonCell? _cellFor(DailyComparisonRow row, String siteCode) {
  for (final cell in row.cells) {
    if (cell.siteCode == siteCode) return cell;
  }
  return null;
}

String _siteNames(List<DailyComparisonSite> sites) {
  if (sites.isEmpty) return 'Sites ARI';
  if (sites.length == 1) return sites.first.label;
  final labels = sites.map((site) => site.label).toList();
  return '${labels.take(labels.length - 1).join(', ')} et ${labels.last}';
}


Map<String, Map<String, List<DailyComparisonSelectableSite>>> _groupSelectableSites(
  List<DailyComparisonSelectableSite> sites,
) {
  final result = <String, Map<String, List<DailyComparisonSelectableSite>>>{};
  for (final site in sites) {
    final zone = site.zone ?? 'Zone inconnue';
    final governorate = site.governorate ?? 'Gouvernorat inconnu';
    result.putIfAbsent(zone, () => <String, List<DailyComparisonSelectableSite>>{});
    result[zone]!.putIfAbsent(governorate, () => <DailyComparisonSelectableSite>[]);
    result[zone]![governorate]!.add(site);
  }

  for (final zoneEntry in result.entries) {
    for (final govEntry in zoneEntry.value.entries) {
      govEntry.value.sort((left, right) {
        final powerCompare = (right.installedPowerKw ?? 0).compareTo(left.installedPowerKw ?? 0);
        if (powerCompare != 0) return powerCompare;
        return left.label.compareTo(right.label);
      });
    }
  }

  return result;
}

String _excelSiteHeader(DailyComparisonSite site) => '${site.label} (${site.powerLabel})';

String _displayIsoDate(String? value) {
  if (value == null || value.trim().isEmpty) return '—';
  final parsed = DateTime.tryParse(value);
  if (parsed == null) return value;
  return Formatters.date(parsed);
}

Future<void> _exportExcel(DailyComparisonReport report) async {
  if (report.sites.isEmpty) {
    Get.snackbar(
      'Export impossible',
      'Aucune donnée à exporter.',
      snackPosition: SnackPosition.BOTTOM,
    );
    return;
  }

  try {
    final book = xlsx.Excel.createExcel();
    const sheetName = 'Comparatif';
    final sheet = book[sheetName];
    book.delete('Sheet1');

    _appendTable(
      sheet,
      'Tableau historique original',
      report.sites,
      report.historicalRows,
      report.historicalTotals,
    );

    sheet.appendRow(<xlsx.CellValue>[]);
    sheet.appendRow(<xlsx.CellValue>[]);

    _appendTable(
      sheet,
      'Tableau avec valeurs estimables',
      report.sites,
      report.estimatedRows,
      report.estimatedTotals,
    );

    final now = DateTime.now();
    final stamp = '${now.year}'
        '${now.month.toString().padLeft(2, '0')}'
        '${now.day.toString().padLeft(2, '0')}_'
        '${now.hour.toString().padLeft(2, '0')}'
        '${now.minute.toString().padLeft(2, '0')}';

    book.save(fileName: 'comparatif_ari_$stamp.xlsx');

    Get.snackbar(
      'Export terminé',
      'Le fichier Excel contient les deux tableaux.',
      snackPosition: SnackPosition.BOTTOM,
    );
  } catch (error) {
    Get.snackbar(
      'Erreur export',
      error.toString(),
      snackPosition: SnackPosition.BOTTOM,
    );
  }
}

void _appendTable(
  xlsx.Sheet sheet,
  String title,
  List<DailyComparisonSite> sites,
  List<DailyComparisonRow> rows,
  DailyComparisonRow? totalRow,
) {
  sheet.appendRow([xlsx.TextCellValue(title)]);
  sheet.appendRow([
    xlsx.TextCellValue('Date'),
    ...sites.map((site) => xlsx.TextCellValue(_excelSiteHeader(site))),
  ]);

  for (final row in rows) {
    sheet.appendRow([
      xlsx.TextCellValue(row.label),
      ...sites.map((site) => _excelCell(_cellFor(row, site.siteCode))),
    ]);
  }

  if (totalRow != null) {
    sheet.appendRow([
      xlsx.TextCellValue('TOTAL'),
      ...sites.map((site) => _excelCell(_cellFor(totalRow, site.siteCode), forceNumber: true)),
    ]);
  }
}

xlsx.CellValue _excelCell(DailyComparisonCell? cell, {bool forceNumber = false}) {
  if (cell == null) return xlsx.TextCellValue('—');
  if ((forceNumber || !cell.estimated) && cell.value != null) {
    return xlsx.DoubleCellValue(cell.value!);
  }
  return xlsx.TextCellValue(cell.displayValue);
}
