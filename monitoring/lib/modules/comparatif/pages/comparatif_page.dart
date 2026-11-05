import 'package:excel/excel.dart' as xlsx;
import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../data/models/daily_comparison_report.dart';
import '../controllers/comparatif_controller.dart';
import '../widgets/comparison_charts.dart';
import '../widgets/site_selection_dialog.dart';
import '../widgets/sticky_comparison_table.dart';

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
            child: CustomScrollView(
              slivers: [
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(24, 24, 24, 18),
                  sliver: SliverList.list(
                    children: [
                      _Header(report: report, controller: controller),
                      const SizedBox(height: 18),
                      _SiteSelectorCard(controller: controller),
                      if (error != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 12),
                          child: Text(
                            error,
                            style: const TextStyle(color: Colors.red),
                          ),
                        ),
                      const SizedBox(height: 18),
                      if (report.sites.isEmpty)
                        const EmptyState(
                          title: 'Commencez par choisir vos sites',
                          message:
                              'Choisissez les sites, puis lancez la comparaison.',
                          icon: Icons.compare_arrows,
                        )
                      else
                        ComparisonCharts(report: report),
                    ],
                  ),
                ),
                if (report.sites.isNotEmpty) ...[
                  SliverPadding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    sliver: StickyComparisonTable(
                      title: 'Production réelle · kWh',
                      subtitle:
                          'Historique collecté, sans estimation. Les noms des sites et les dates restent visibles pendant le défilement.',
                      rows: report.historicalRows,
                      totalRow: report.historicalTotals,
                      sites: report.sites,
                    ),
                  ),
                  const SliverToBoxAdapter(child: SizedBox(height: 24)),
                  SliverPadding(
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    sliver: StickyComparisonTable(
                      title: 'Production avec estimation · kWh',
                      subtitle:
                          'Les valeurs estimées sont en orange. Calcul existant : (moyenne réelle du mois + théorique du jour) / 2 ; à défaut, 70 % du théorique.',
                      rows: report.estimatedRows,
                      totalRow: report.estimatedTotals,
                      sites: report.sites,
                    ),
                  ),
                ],
                const SliverToBoxAdapter(child: SizedBox(height: 32)),
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
  Widget build(BuildContext context) => Wrap(
    spacing: 18,
    runSpacing: 12,
    alignment: WrapAlignment.spaceBetween,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Comparer la production',
            style: TextStyle(
              color: AppColors.midnightNavy,
              fontSize: 26,
              fontWeight: FontWeight.w700,
            ),
          ),
          const SizedBox(height: 5),
          Text(
            report.sites.isEmpty
                ? 'Choisissez vos sites et votre période.'
                : '${report.sites.length} sites · ${_displayIsoDate(report.startDate)} — ${_displayIsoDate(report.endDate)}',
            style: const TextStyle(color: AppColors.mutedText),
          ),
        ],
      ),
      Wrap(
        spacing: 8,
        runSpacing: 8,
        children: [
          OutlinedButton.icon(
            onPressed: controller.isRefreshing.value
                ? null
                : controller.refreshData,
            icon: const Icon(Icons.refresh, size: 18),
            label: const Text('Actualiser'),
          ),
          OutlinedButton.icon(
            onPressed: report.sites.isEmpty ? null : () => _exportExcel(report),
            icon: const Icon(Icons.download, size: 18),
            label: const Text('Exporter Excel'),
          ),
        ],
      ),
    ],
  );
}

class _SiteSelectorCard extends StatelessWidget {
  const _SiteSelectorCard({required this.controller});
  final ComparatifController controller;
  Future<void> choose(BuildContext context) async {
    final result = await showDialog<Set<String>>(
      context: context,
      builder: (_) => SiteSelectionDialog(
        sites: controller.availableSites.toList(),
        selected: Set.of(controller.selectedSiteCodes),
      ),
    );
    if (result != null) controller.selectedSiteCodes.assignAll(result);
  }

  @override
  Widget build(BuildContext context) => PremiumCard(
    padding: const EdgeInsets.all(18),
    child: Obx(() {
      final selected = controller.availableSites
          .where((s) => controller.isSelected(s.siteCode))
          .toList();
      final loaded = controller.report.value.sites
          .map((s) => s.siteCode)
          .toSet();
      final pending =
          loaded.length != selected.length ||
          selected.any((s) => !loaded.contains(s.siteCode)) ||
          (controller.selectedStartDate.value != null &&
              Formatters.apiDay(controller.selectedStartDate.value!) !=
                  controller.report.value.startDate) ||
          (controller.selectedEndDate.value != null &&
              Formatters.apiDay(controller.selectedEndDate.value!) !=
                  controller.report.value.endDate);
      return Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: 16,
            runSpacing: 10,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              const Text(
                '1. Sites à comparer',
                style: TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
              ),
              OutlinedButton.icon(
                onPressed: controller.isRefreshing.value
                    ? null
                    : () => choose(context),
                icon: const Icon(Icons.add_location_alt_outlined, size: 18),
                label: Text('Choisir les sites (${selected.length})'),
              ),
              if (pending)
                const Text(
                  'Filtres modifiés · lancez la comparaison',
                  style: TextStyle(color: AppColors.deepTeal, fontSize: 12),
                ),
            ],
          ),
          if (selected.isNotEmpty) ...[
            const SizedBox(height: 12),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                ...selected
                    .take(8)
                    .map(
                      (s) => InputChip(
                        label: Text(s.label),
                        avatar: Icon(
                          Icons.circle,
                          size: 9,
                          color: comparisonSiteColor(s.siteCode),
                        ),
                        onDeleted: controller.isRefreshing.value
                            ? null
                            : () => controller.toggleSite(s.siteCode, false),
                      ),
                    ),
                if (selected.length > 8)
                  ActionChip(
                    label: Text('+ ${selected.length - 8} sites'),
                    onPressed: () => choose(context),
                  ),
              ],
            ),
          ],
          const SizedBox(height: 16),
          _DateRangeFilter(controller: controller),
          const SizedBox(height: 14),
          Wrap(
            spacing: 16,
            runSpacing: 10,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              FilledButton.icon(
                onPressed: controller.isRefreshing.value || selected.isEmpty
                    ? null
                    : controller.applySelection,
                icon: controller.isRefreshing.value
                    ? const SizedBox(
                        width: 16,
                        height: 16,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : const Icon(Icons.compare_arrows, size: 18),
                label: Text(
                  controller.isRefreshing.value
                      ? 'Chargement…'
                      : 'Comparer ${selected.length} sites',
                ),
              ),
              const Text(
                'Journées terminées uniquement · aujourd’hui exclu',
                style: TextStyle(color: AppColors.mutedText, fontSize: 12),
              ),
            ],
          ),
        ],
      );
    }),
  );
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
            '2. Période',
            style: TextStyle(
              color: AppColors.midnightNavy,
              fontWeight: FontWeight.w900,
            ),
          ),
          const SizedBox(height: 4),
          const Text(
            'Choisissez une période ou conservez les dates automatiques.',
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
              ...[7, 30, 90].map(
                (days) => OutlinedButton(
                  onPressed: () {
                    controller.setEndDate(yesterday);
                    controller.setStartDate(
                      yesterday.subtract(Duration(days: days - 1)),
                    );
                  },
                  child: Text('$days jours'),
                ),
              ),
              _NullableDatePickerButton(
                width: 190,
                label: 'Date début',
                value: startDate,
                placeholder: 'Depuis le premier swap',
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
                onPressed: startDate == null && endDate == null
                    ? null
                    : controller.clearDateRange,
                icon: const Icon(Icons.restart_alt_rounded, size: 18),
                label: const Text('Automatique'),
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
              color: value == null
                  ? AppColors.mutedText
                  : AppColors.midnightNavy,
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

DailyComparisonCell? _cellFor(DailyComparisonRow row, String siteCode) {
  for (final cell in row.cells) {
    if (cell.siteCode == siteCode) return cell;
  }
  return null;
}

String _excelSiteHeader(DailyComparisonSite site) =>
    '${site.label} (${site.powerLabel})';

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
    final stamp =
        '${now.year}'
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
      ...sites.map(
        (site) =>
            _excelCell(_cellFor(totalRow, site.siteCode), forceNumber: true),
      ),
    ]);
  }
}

xlsx.CellValue _excelCell(
  DailyComparisonCell? cell, {
  bool forceNumber = false,
}) {
  if (cell == null) return xlsx.TextCellValue('—');
  if ((forceNumber || !cell.estimated) && cell.value != null) {
    return xlsx.DoubleCellValue(cell.value!);
  }
  return xlsx.TextCellValue(cell.displayValue);
}
