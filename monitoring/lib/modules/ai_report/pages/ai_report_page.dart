import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../data/models/ai_report.dart';
import '../controllers/ai_report_controller.dart';

class AiReportPage extends GetView<AiReportController> {
  const AiReportPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && !controller.hasReport) {
          return const LoadingState(label: 'Génération du rapport AI...');
        }
        final error = controller.errorMessage.value;
        return CustomScrollView(
          slivers: [
            _Header(controller: controller),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(26, 16, 26, 26),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  _FiltersCard(controller: controller),
                  const SizedBox(height: 12),
                  _ReportTypeInfoCard(selectedType: controller.reportType.value),
                  const SizedBox(height: 18),
                  if (error != null) _InlineError(message: error),
                  if (controller.hasReport) ...[
                   
                    _KpiCards(report: controller.report.value),
                    const SizedBox(height: 18),
                    _NarrativeCard(report: controller.report.value),
                    const SizedBox(height: 18),
                    _SplitSection(report: controller.report.value),
                    const SizedBox(height: 18),
                    _RecommendationsCard(report: controller.report.value),
                    const SizedBox(height: 18),
                    _AppendixCard(report: controller.report.value),
                  ],
                ]),
              ),
            ),
          ],
        );
      }),
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.controller});
  final AiReportController controller;

  @override
  Widget build(BuildContext context) {
    return SliverAppBar(
      pinned: true,
      automaticallyImplyLeading: false,
      expandedHeight: 148,
      toolbarHeight: 148,
      backgroundColor: AppColors.softWhite,
      surfaceTintColor: Colors.transparent,
      flexibleSpace: Padding(
        padding: const EdgeInsets.fromLTRB(26, 24, 26, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            const Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Rapport AI',
                    style: TextStyle(
                      color: AppColors.midnightNavy,
                      fontSize: 34,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -1.1,
                    ),
                  ),
                  SizedBox(height: 6),
                  Text(
                    'Aperçu intelligent + rapport PDF téléchargeable selon start date / end date.',
                    style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700),
                  ),
                ],
              ),
            ),
            Obx(
              () => OutlinedButton.icon(
                onPressed: controller.isLoading.value ? null : controller.loadPreview,
                icon: controller.isLoading.value
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.auto_awesome_rounded),
                label: const Text('Générer'),
              ),
            ),
            const SizedBox(width: 10),
            Obx(
              () => FilledButton.icon(
                onPressed: controller.isDownloading.value || controller.isLoading.value || !controller.hasReport ? null : controller.downloadPdf,
                icon: controller.isDownloading.value
                    ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                    : const Icon(Icons.picture_as_pdf_rounded, size: 18),
                label: const Text('Télécharger PDF'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _FiltersCard extends StatelessWidget {
  const _FiltersCard({required this.controller});
  final AiReportController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Obx(
        () => Wrap(
          spacing: 14,
          runSpacing: 14,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            _DateButton(
              label: 'Start date',
              value: controller.startDate.value,
              onPicked: controller.setStartDate,
            ),
            _DateButton(
              label: 'End date',
              value: controller.endDate.value,
              onPicked: controller.setEndDate,
            ),
            _Dropdown(
              label: 'Périmètre',
              value: controller.scope.value,
              items: const {
                'ALL': 'Tout le parc',
                'ZONE': 'Zone',
                'GOVERNORATE': 'Gouvernorat',
                'SITE': 'Site',
              },
              onChanged: controller.setScope,
            ),
            if (controller.needsScopeValue)
              _ValueDropdown(
                label: 'Valeur',
                value: controller.scopeValue.value.isEmpty ? null : controller.scopeValue.value,
                items: controller.currentScopeValueOptions,
                width: controller.scope.value == 'SITE' ? 260 : 190,
                menuMaxHeight: controller.scope.value == 'SITE' ? 488 : 320,
                loading: controller.isLoadingScopeValues.value,
                onChanged: controller.setScopeValue,
              ),
            _Dropdown(
              label: 'Type rapport',
              value: controller.reportType.value,
              items: const {
                'SUMMARY': 'Résumé',
                'STANDARD': 'Standard',
              },
              onChanged: controller.setReportType,
            ),
          ],
        ),
      ),
    );
  }
}



class _ReportTypeInfoCard extends StatelessWidget {
  const _ReportTypeInfoCard({required this.selectedType});
  final String selectedType;

  @override
  Widget build(BuildContext context) {
    const types = [
      _ReportTypeInfo(
        keyName: 'SUMMARY',
        title: 'Résumé',
        pages: '1 à 2 pages',
        description: 'Vue rapide: KPI essentiels, sites critiques et recommandations courtes.',
        icon: Icons.speed_rounded,
      ),
      _ReportTypeInfo(
        keyName: 'STANDARD',
        title: 'Standard',
        pages: '4 à 8 pages',
        description: 'Rapport mensuel équilibré: production, qualité data, zones, gouvernorats, alertes et annexe courte.',
        icon: Icons.description_rounded,
      ),
    ];

    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _SectionTitle(icon: Icons.tune_rounded, title: 'Types de rapport disponibles'),
          const SizedBox(height: 12),
          LayoutBuilder(
            builder: (context, constraints) {
              final itemWidth = constraints.maxWidth >= 760
                  ? (constraints.maxWidth - 12) / 2
                  : constraints.maxWidth;
              return Wrap(
                spacing: 12,
                runSpacing: 12,
                children: types.map((type) {
                  final selected = type.keyName == selectedType;
                  return SizedBox(
                    width: itemWidth,
                    child: _ReportTypeTile(info: type, selected: selected),
                  );
                }).toList(),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _ReportTypeTile extends StatelessWidget {
  const _ReportTypeTile({required this.info, required this.selected});
  final _ReportTypeInfo info;
  final bool selected;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: selected ? AppColors.tealSoft : AppColors.white,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: selected ? AppColors.deepTeal : AppColors.borderGray, width: selected ? 1.6 : 1),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CircleAvatar(
            radius: 20,
            backgroundColor: selected ? AppColors.deepTeal : AppColors.deepTeal.withOpacity(0.08),
            foregroundColor: selected ? AppColors.white : AppColors.deepTeal,
            child: Icon(info.icon, size: 20),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        info.title,
                        style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900),
                      ),
                    ),
                    if (selected)
                      const Icon(Icons.check_circle_rounded, color: AppColors.deepTeal, size: 18),
                  ],
                ),
                const SizedBox(height: 3),
                Text(info.pages, style: const TextStyle(color: AppColors.deepTeal, fontWeight: FontWeight.w900, fontSize: 12)),
                const SizedBox(height: 6),
                Text(info.description, style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700, height: 1.3, fontSize: 12)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _ReportTypeInfo {
  const _ReportTypeInfo({
    required this.keyName,
    required this.title,
    required this.pages,
    required this.description,
    required this.icon,
  });
  final String keyName;
  final String title;
  final String pages;
  final String description;
  final IconData icon;
}

class _DateButton extends StatelessWidget {
  const _DateButton({required this.label, required this.value, required this.onPicked});
  final String label;
  final DateTime value;
  final ValueChanged<DateTime> onPicked;

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      onPressed: () async {
        final picked = await showDatePicker(
          context: context,
          initialDate: value,
          firstDate: DateTime(2020),
          lastDate: DateTime.now().add(const Duration(days: 365)),
        );
        if (picked != null) onPicked(picked);
      },
      icon: const Icon(Icons.event_rounded, size: 18),
      label: Text('$label · ${Formatters.date(value)}'),
    );
  }
}

class _Dropdown extends StatelessWidget {
  const _Dropdown({required this.label, required this.value, required this.items, required this.onChanged});
  final String label;
  final String value;
  final Map<String, String> items;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 180,
      child: DropdownButtonFormField<String>(
        value: value,
        decoration: InputDecoration(
          labelText: label,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
          isDense: true,
        ),
        items: items.entries.map((entry) => DropdownMenuItem(value: entry.key, child: Text(entry.value))).toList(),
        onChanged: (value) {
          if (value != null) onChanged(value);
        },
      ),
    );
  }
}


class _ValueDropdown extends StatelessWidget {
  const _ValueDropdown({
    required this.label,
    required this.value,
    required this.items,
    required this.onChanged,
    required this.width,
    required this.menuMaxHeight,
    required this.loading,
  });

  final String label;
  final String? value;
  final List<AiScopeValueOption> items;
  final ValueChanged<String> onChanged;
  final double width;
  final double menuMaxHeight;
  final bool loading;

  @override
  Widget build(BuildContext context) {
    final safeValue = items.any((item) => item.value == value) ? value : null;

    return SizedBox(
      width: width,
      child: DropdownButtonFormField<String>(
        value: safeValue,
        menuMaxHeight: menuMaxHeight,
        isExpanded: true,
        decoration: InputDecoration(
          labelText: label,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
          isDense: true,
          suffixIcon: loading
              ? const Padding(
                  padding: EdgeInsets.all(12),
                  child: SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                )
              : null,
        ),
        hint: Text(loading ? 'Chargement...' : 'Choisir une valeur'),
        items: items
            .map(
              (item) => DropdownMenuItem(
                value: item.value,
                child: Text(item.label, maxLines: 1, overflow: TextOverflow.ellipsis),
              ),
            )
            .toList(),
        onChanged: (value) {
          if (value != null) onChanged(value);
        },
      ),
    );
  }
}



class _KpiCards extends StatelessWidget {
  const _KpiCards({required this.report});
  final AiReportPreview report;

  @override
  Widget build(BuildContext context) {
    final cards = [
      _CardData('Sites', report.globalKpis.totalSites.toString(), Icons.domain_rounded),
      _CardData('Production affichée', Formatters.energy(report.productionSummary.displayedKwh), Icons.bolt_rounded),
      _CardData('Alertes actives', report.alarms.activeCount.toString(), Icons.notification_important_rounded),
      _CardData('Statut global', report.globalKpis.globalStatus, Icons.health_and_safety_rounded),
    ];
    return LayoutBuilder(
      builder: (context, constraints) {
        final width = constraints.maxWidth >= 980 ? (constraints.maxWidth - 30) / 4 : constraints.maxWidth >= 620 ? (constraints.maxWidth - 10) / 2 : constraints.maxWidth;
        return Wrap(
          spacing: 10,
          runSpacing: 10,
          children: cards.map((card) => _MetricCard(width: width, data: card)).toList(),
        );
      },
    );
  }
}

class _MetricCard extends StatelessWidget {
  const _MetricCard({required this.width, required this.data});
  final double width;
  final _CardData data;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      child: PremiumCard(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            CircleAvatar(backgroundColor: AppColors.deepTeal.withOpacity(0.1), foregroundColor: AppColors.deepTeal, child: Icon(data.icon)),
            const SizedBox(height: 12),
            Text(data.label, style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w800, fontSize: 12)),
            const SizedBox(height: 4),
            Text(data.value, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900, fontSize: 18)),
          ],
        ),
      ),
    );
  }
}

class _CardData {
  const _CardData(this.label, this.value, this.icon);
  final String label;
  final String value;
  final IconData icon;
}

class _NarrativeCard extends StatelessWidget {
  const _NarrativeCard({required this.report});
  final AiReportPreview report;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(child: _SectionTitle(icon: Icons.summarize_rounded, title: 'Résumé intelligent')),
              _AiStatusPill(status: report.aiStatus),
            ],
          ),
          const SizedBox(height: 12),
          Text(report.aiNarrative.executiveSummary, style: const TextStyle(color: AppColors.slateGraphite, fontWeight: FontWeight.w700, height: 1.55)),
          const SizedBox(height: 16),
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: report.aiNarrative.mainFindings.map((item) => _InsightChip(text: item)).toList(),
          ),
          const SizedBox(height: 16),
          const Text('Analyse risque', style: TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900)),
          const SizedBox(height: 6),
          Text(report.aiNarrative.riskAnalysis, style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700, height: 1.45)),
        ],
      ),
    );
  }
}


class _AiStatusPill extends StatelessWidget {
  const _AiStatusPill({required this.status});
  final String status;

  @override
  Widget build(BuildContext context) {
    final clean = status.trim().isEmpty ? 'GEMINI_PENDING' : status.trim();
    final isOk = clean == 'GEMINI_OK';
    final label = isOk ? 'Gemini OK' : clean;
    final icon = isOk ? Icons.check_circle_rounded : Icons.info_rounded;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
      decoration: BoxDecoration(
        color: (isOk ? AppColors.deepTeal : Colors.orange).withOpacity(0.12),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: (isOk ? AppColors.deepTeal : Colors.orange).withOpacity(0.25)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: isOk ? AppColors.deepTeal : Colors.orange.shade800),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              color: isOk ? AppColors.deepTeal : Colors.orange.shade900,
              fontWeight: FontWeight.w900,
              fontSize: 12,
            ),
          ),
        ],
      ),
    );
  }
}

class _InsightChip extends StatelessWidget {
  const _InsightChip({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      constraints: const BoxConstraints(maxWidth: 380),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(color: AppColors.tealSoft, borderRadius: BorderRadius.circular(16)),
      child: Text(text, style: const TextStyle(color: AppColors.deepTeal, fontWeight: FontWeight.w800)),
    );
  }
}

class _SplitSection extends StatelessWidget {
  const _SplitSection({required this.report});
  final AiReportPreview report;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      final desktop = constraints.maxWidth >= 980;
      if (desktop) {
        return Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(child: _RankingCard(title: 'Meilleurs sites', sites: report.bestSites, positive: true)),
            const SizedBox(width: 16),
            Expanded(child: _RankingCard(title: 'Sites à traiter', sites: report.worstSites, positive: false)),
          ],
        );
      }
      return Column(
        children: [
          _RankingCard(title: 'Meilleurs sites', sites: report.bestSites, positive: true),
          const SizedBox(height: 16),
          _RankingCard(title: 'Sites à traiter', sites: report.worstSites, positive: false),
        ],
      );
    });
  }
}

class _RankingCard extends StatelessWidget {
  const _RankingCard({required this.title, required this.sites, required this.positive});
  final String title;
  final List<ReportSiteRanking> sites;
  final bool positive;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionTitle(icon: positive ? Icons.emoji_events_rounded : Icons.priority_high_rounded, title: title),
          const SizedBox(height: 12),
          if (sites.isEmpty)
            const Text('Aucune donnée disponible.', style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700))
          else
            ...sites.take(6).map((site) => _SiteLine(site: site, positive: positive)),
        ],
      ),
    );
  }
}

class _SiteLine extends StatelessWidget {
  const _SiteLine({required this.site, required this.positive});
  final ReportSiteRanking site;
  final bool positive;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.softWhite,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 18,
            backgroundColor: (positive ? AppColors.healthy : AppColors.faulty).withOpacity(0.1),
            foregroundColor: positive ? AppColors.healthy : AppColors.faulty,
            child: Icon(positive ? Icons.arrow_upward_rounded : Icons.arrow_downward_rounded, size: 18),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(site.siteName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900)),
              const SizedBox(height: 3),
              Text('${site.governorate} · ${site.status} · ${site.reason}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700, fontSize: 12)),
            ]),
          ),
          const SizedBox(width: 10),
          Text(_signedPercent(site.differencePercent), style: TextStyle(color: positive ? AppColors.healthy : AppColors.faulty, fontWeight: FontWeight.w900)),
        ],
      ),
    );
  }
}

class _RecommendationsCard extends StatelessWidget {
  const _RecommendationsCard({required this.report});
  final AiReportPreview report;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _SectionTitle(icon: Icons.task_alt_rounded, title: 'Plan d’action recommandé'),
          const SizedBox(height: 12),
          ...report.recommendations.map((rec) => _RecommendationTile(rec: rec)),
        ],
      ),
    );
  }
}

class _RecommendationTile extends StatelessWidget {
  const _RecommendationTile({required this.rec});
  final ReportRecommendation rec;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.white, borderRadius: BorderRadius.circular(16), border: Border.all(color: AppColors.borderGray)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Chip(label: Text(rec.priority), visualDensity: VisualDensity.compact),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('${rec.target} — ${rec.issue}', style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900)),
            const SizedBox(height: 5),
            Text(rec.action, style: const TextStyle(color: AppColors.slateGraphite, fontWeight: FontWeight.w700, height: 1.35)),
            if (rec.justification.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text(rec.justification, style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700, fontSize: 12)),
            ],
          ]),
        ),
      ]),
    );
  }
}

class _AppendixCard extends StatelessWidget {
  const _AppendixCard({required this.report});
  final AiReportPreview report;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const _SectionTitle(icon: Icons.table_chart_rounded, title: 'Annexe technique'),
          const SizedBox(height: 12),
          SizedBox(
            width: double.infinity,
            child: DataTable(
              columns: const [
                DataColumn(label: Text('Site')),
                DataColumn(label: Text('Gouv')),
                DataColumn(label: Text('Statut')),
                DataColumn(label: Text('Prod. affichée')),
                DataColumn(label: Text('Écart')),
                DataColumn(label: Text('Data')),
              ],
              rows: report.technicalAppendix.take(12).map((row) {
                return DataRow(cells: [
                  DataCell(Text(row.siteName)),
                  DataCell(Text(row.governorate)),
                  DataCell(Text(row.status)),
                  DataCell(Text(Formatters.energy(row.displayedKwh))),
                  DataCell(Text(_signedPercent(row.differencePercent))),
                  DataCell(Text(row.dataStatus)),
                ]);
              }).toList(),
            ),
          ),
          if (report.technicalAppendix.length > 12)
            Padding(
              padding: const EdgeInsets.only(top: 8),
              child: Text('+ ${report.technicalAppendix.length - 12} lignes dans le PDF.', style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700)),
            ),
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle({required this.icon, required this.title});
  final IconData icon;
  final String title;

  @override
  Widget build(BuildContext context) {
    return Row(children: [
      Icon(icon, color: AppColors.deepTeal),
      const SizedBox(width: 8),
      Text(title, style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900, fontSize: 18)),
    ]);
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message});
  final String message;
  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 18),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(color: AppColors.faulty.withOpacity(0.08), borderRadius: BorderRadius.circular(16)),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.error_rounded, color: AppColors.faulty),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              'Rapport non généré : $message',
              style: const TextStyle(color: AppColors.faulty, fontWeight: FontWeight.w800, height: 1.35),
            ),
          ),
        ],
      ),
    );
  }
}

String _signedPercent(num? value) {
  if (value == null) return '—';
  final sign = value > 0 ? '+' : '';
  return '$sign${Formatters.decimal(value, decimals: 2)} %';
}
