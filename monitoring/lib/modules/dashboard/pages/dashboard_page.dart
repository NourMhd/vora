import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/routes/app_routes.dart';
import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/energy_chart.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/metric_card.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../core/widgets/status_badge.dart';
import '../../../data/models/site_summary.dart';
import '../../shell/controllers/shell_controller.dart';
import '../../sites/controllers/sites_controller.dart';
import '../controllers/dashboard_controller.dart';

class DashboardPage extends GetView<DashboardController> {
  const DashboardPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && controller.sites.isEmpty) {
          return const LoadingState(label: 'Chargement du dashboard...');
        }

        if (controller.errorMessage.value != null && controller.sites.isEmpty) {
          return ErrorState(message: controller.errorMessage.value!, onRetry: controller.load);
        }

        return RefreshIndicator(
          onRefresh: controller.refreshData,
          child: CustomScrollView(
            physics: const AlwaysScrollableScrollPhysics(),
            slivers: [
              _DashboardHeader(controller: controller),
              SliverPadding(
                padding: const EdgeInsets.fromLTRB(26, 16, 26, 32),
                sliver: SliverList(
                  delegate: SliverChildListDelegate([
                    _KpiGrid(controller: controller),
                    const SizedBox(height: 22),
                    LayoutBuilder(
                      builder: (context, constraints) {
                        final twoColumns = constraints.maxWidth >= 1050;
                        final status = _StatusCard(controller: controller);
                        final insights = _TechnicianInsightsCard(controller: controller);
                        if (!twoColumns) {
                          return Column(children: [status, const SizedBox(height: 22), insights]);
                        }
                        return Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(width: 420, child: status),
                            const SizedBox(width: 22),
                            Expanded(child: insights),
                          ],
                        );
                      },
                    ),
                    const SizedBox(height: 22),
                    LayoutBuilder(
                      builder: (context, constraints) {
                        final twoColumns = constraints.maxWidth >= 1050;
                        final brands = _BrandMixCard(controller: controller);
                        final sites = _SitesRankingCard(controller: controller);
                        if (!twoColumns) {
                          return Column(children: [brands, const SizedBox(height: 22), sites]);
                        }
                        return Row(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            SizedBox(width: 420, child: brands),
                            const SizedBox(width: 22),
                            Expanded(child: sites),
                          ],
                        );
                      },
                    ),
                  ]),
                ),
              ),
            ],
          ),
        );
      }),
    );
  }
}

class _DashboardHeader extends StatelessWidget {
  const _DashboardHeader({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return SliverAppBar(
      pinned: true,
      automaticallyImplyLeading: false,
      expandedHeight: 128,
      toolbarHeight: 128,
      backgroundColor: AppColors.softWhite.withOpacity(0.96),
      surfaceTintColor: Colors.transparent,
      flexibleSpace: Padding(
        padding: const EdgeInsets.fromLTRB(26, 24, 26, 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.end,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text(
                    'Overview',
                    style: TextStyle(
                      color: AppColors.midnightNavy,
                      fontSize: 34,
                      fontWeight: FontWeight.w900,
                      letterSpacing: -1.1,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Obx(() => Text(
                        'Dernière mise à jour · auto 10s · ${Formatters.dateTime(controller.lastRefresh.value)}',
                        style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700),
                      )),
                ],
              ),
            ),
            Obx(() {
              final status = controller.health.value?.status.toUpperCase();
              final online = status == 'UP';
              return Container(
                padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                decoration: BoxDecoration(
                  color: AppColors.white,
                  borderRadius: BorderRadius.circular(999),
                  border: Border.all(color: AppColors.borderGray),
                ),
                child: Row(
                  children: [
                    Icon(
                      online ? Icons.cloud_done_rounded : Icons.cloud_off_rounded,
                      color: online ? AppColors.healthy : AppColors.warning,
                      size: 18,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      online ? 'Système connecté' : 'Statut système',
                      style: const TextStyle(fontWeight: FontWeight.w900, color: AppColors.midnightNavy),
                    ),
                  ],
                ),
              );
            }),
            const SizedBox(width: 12),
            Obx(() => IconButton.filledTonal(
                  onPressed: controller.isRefreshing.value ? null : controller.refreshData,
                  icon: controller.isRefreshing.value
                      ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.refresh_rounded),
                )),
          ],
        ),
      ),
    );
  }
}

class _KpiGrid extends StatelessWidget {
  const _KpiGrid({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final summary = controller.summary.value;

      return LayoutBuilder(
        builder: (context, constraints) {
          final columns = constraints.maxWidth >= 1300
              ? 6
              : constraints.maxWidth >= 920
                  ? 3
                  : constraints.maxWidth >= 620
                      ? 2
                      : 1;

          final spacing = 16.0;
          final width = (constraints.maxWidth - spacing * (columns - 1)) / columns;

          final cards = [
            MetricCard(
              title: 'Total sites',
              value: Formatters.count(summary.totalSites),
              icon: Icons.domain_rounded,
              caption: 'Portfolio multi-brand',
            ),
            MetricCard(
              title: 'Installed power',
              value: Formatters.power(summary.totalInstalledPowerKw),
              icon: Icons.bolt_rounded,
              caption: 'Puissance totale',
            ),
            MetricCard(
              title: 'Energy today',
              value: Formatters.energy(summary.totalDayEnergyKwh),
              icon: Icons.wb_sunny_rounded,
              caption: 'Production du jour',
            ),
            MetricCard(
              title: 'Energy month',
              value: Formatters.energy(summary.totalMonthEnergyKwh),
              icon: Icons.calendar_view_month_rounded,
              caption: 'Mois courant',
            ),
            MetricCard(
              title: 'Energy year',
              value: Formatters.energy(summary.totalYearEnergyKwh),
              icon: Icons.stacked_line_chart_rounded,
              caption: 'Année courante',
            ),
            MetricCard(
              title: 'Total energy',
              value: Formatters.energy(summary.totalEnergyKwh),
              icon: Icons.all_inclusive_rounded,
              caption: 'Production cumulée',
            ),
          ];

          return Wrap(
            spacing: spacing,
            runSpacing: spacing,
            children: cards.map((card) => SizedBox(width: width, child: card)).toList(),
          );
        },
      );
    });
  }
}

class _StatusCard extends StatelessWidget {
  const _StatusCard({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Operational status',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: AppColors.midnightNavy),
          ),
          const SizedBox(height: 6),
          const Text(
            'Répartition des sites par état.',
            style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 18),
          Obx(() => StatusDonutChart(
                counts: controller.statusCounts,
                selectedStatus: controller.selectedStatus.value,
                onStatusTap: (status) {
                  controller.selectStatus(status);
                  if (Get.isRegistered<SitesController>()) {
                    Get.find<SitesController>().setStatusFilter(status);
                  }
                  if (Get.isRegistered<ShellController>()) {
                    Get.find<ShellController>().selectTab(1);
                  } else {
                    Get.toNamed(AppRoutes.sites, arguments: {'status': status});
                  }
                },
              )),
        ],
      ),
    );
  }
}

class _TechnicianInsightsCard extends StatelessWidget {
  const _TechnicianInsightsCard({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final summary = controller.summary.value;
      final best = controller.topProductiveSite;
      final lowest = controller.lowestDayEnergySite;
      return PremiumCard(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Vue technicien', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: AppColors.midnightNavy)),
            const SizedBox(height: 6),
            const Text('Indicateurs utiles dès la première vue.', style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w600)),
            const SizedBox(height: 18),
            LayoutBuilder(
              builder: (context, constraints) {
                final compact = constraints.maxWidth < 620;
                final cards = [
                  _InsightTile(
                    icon: Icons.trending_up_rounded,
                    label: 'Plus productif aujourd’hui',
                    value: best == null ? '—' : best.siteName,
                    caption: best == null ? 'Aucun site' : Formatters.energy(best.dayEnergyKwh),
                  ),
                  _InsightTile(
                    icon: Icons.search_rounded,
                    label: 'À vérifier',
                    value: lowest == null ? '—' : lowest.siteName,
                    caption: lowest == null ? 'Aucun site' : '${Formatters.energy(lowest.dayEnergyKwh)} aujourd’hui',
                  ),
                  _InsightTile(
                    icon: Icons.error_outline_rounded,
                    label: 'Sites à surveiller',
                    value: Formatters.count(summary.faultySites + summary.disconnectedSites),
                    caption: '${summary.faultySites} défaut · ${summary.disconnectedSites} offline',
                  ),
                  _InsightTile(
                    icon: Icons.all_inclusive_rounded,
                    label: 'Énergie totale',
                    value: Formatters.energy(summary.totalEnergyKwh),
                    caption: 'Cumul portefeuille',
                  ),
                ];
                if (compact) {
                  return Column(children: cards.map((card) => Padding(padding: const EdgeInsets.only(bottom: 12), child: card)).toList());
                }
                return Wrap(
                  spacing: 14,
                  runSpacing: 14,
                  children: cards.map((card) => SizedBox(width: (constraints.maxWidth - 14) / 2, child: card)).toList(),
                );
              },
            ),
          ],
        ),
      );
    });
  }
}

class _InsightTile extends StatelessWidget {
  const _InsightTile({required this.icon, required this.label, required this.value, required this.caption});

  final IconData icon;
  final String label;
  final String value;
  final String caption;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: AppColors.softWhite,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Row(
        children: [
          Container(
            width: 42,
            height: 42,
            decoration: BoxDecoration(color: AppColors.tealSoft, borderRadius: BorderRadius.circular(14)),
            child: Icon(icon, color: AppColors.deepTeal, size: 22),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.mutedText, fontSize: 11, fontWeight: FontWeight.w800)),
                const SizedBox(height: 4),
                Text(value, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.midnightNavy, fontSize: 16, fontWeight: FontWeight.w900)),
                const SizedBox(height: 3),
                Text(caption, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.mutedText, fontSize: 11, fontWeight: FontWeight.w700)),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _BrandMixCard extends StatelessWidget {
  const _BrandMixCard({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
     return Obx(() {
    final counts = controller.brandCounts;
    return PremiumCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Brand distribution',
            style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: AppColors.midnightNavy),
          ),
          const SizedBox(height: 6),
          const Text(
            'Pensé multi-marque dès le départ.',
            style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 18),
          if (counts.isEmpty)
            const EmptyState(title: 'Aucune marque', message: 'Les marques apparaîtront après la synchronisation.', icon: Icons.hub_rounded)
          else
            ...counts.entries.map((entry) {
              final total = counts.values.fold<int>(0, (sum, value) => sum + value);
              final ratio = total == 0 ? 0.0 : entry.value / total;
              return Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Text(entry.key, style: const TextStyle(fontWeight: FontWeight.w900, color: AppColors.midnightNavy)),
                        const Spacer(),
                        Text('${entry.value} sites', style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w700)),
                      ],
                    ),
                    const SizedBox(height: 8),
                    ClipRRect(
                      borderRadius: BorderRadius.circular(999),
                      child: LinearProgressIndicator(
                        value: ratio,
                        minHeight: 9,
                        backgroundColor: AppColors.borderGray,
                        valueColor: const AlwaysStoppedAnimation<Color>(AppColors.deepTeal),
                      ),
                    ),
                  ],
                ),
              );
            }),
       ],
      ),
    );
  });
}
}

class _SitesRankingCard extends StatelessWidget {
  const _SitesRankingCard({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          LayoutBuilder(
            builder: (context, constraints) {
              final compact = constraints.maxWidth < 760;
              final title = const Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Tous les sites', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w900, color: AppColors.midnightNavy)),
                  SizedBox(height: 6),
                  Text('Pagination de 10 sites, avec tri énergétique.', style: TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w600)),
                ],
              );
              final controls = Obx(() => Wrap(
                    spacing: 10,
                    runSpacing: 10,
                    children: [
                     _SmallDropdown(
  width: 160,
  value: controller.siteSortMetric.value,
  values: const ['day', 'month', 'year', 'total'],
  labels: const {
    'day': 'kWh day',
    'month': 'kWh month',
    'year': 'kWh year',
    'total': 'kWh total',
  },
  onChanged: (value) => controller.setSiteSortMetric(value ?? 'day'),
),
                      _SmallDropdown(
                        width: 150,
                        value: controller.siteSortDirection.value,
                        values: const ['desc', 'asc'],
                        labels: const {
                          'desc': 'Descendant',
                          'asc': 'Ascendant',
                        },
                        onChanged: (value) => controller.setSiteSortDirection(value ?? 'desc'),
                      ),
                    ],
                  ));
              if (compact) return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [title, const SizedBox(height: 14), controls]);
              return Row(children: [Expanded(child: title), controls]);
            },
          ),
          const SizedBox(height: 18),
          Obx(() {
            final sites = controller.pagedSites;
            if (sites.isEmpty) {
              return const EmptyState(title: 'Aucun site', message: 'Les sites apparaîtront après la prochaine mise à jour.', icon: Icons.domain_disabled_rounded);
            }
            return Column(
              children: [
                ...List.generate(sites.length, (index) {
                  final rank = controller.sitePage.value * DashboardController.pageSize + index + 1;
                  return Padding(
                    padding: EdgeInsets.only(bottom: index == sites.length - 1 ? 0 : 10),
                    child: _RankingSiteRow(site: sites[index], rank: rank),
                  );
                }),
                const SizedBox(height: 16),
                _SitesPagination(controller: controller),
              ],
            );
          }),
        ],
      ),
    );
  }
}

class _RankingSiteRow extends StatelessWidget {
  const _RankingSiteRow({required this.site, required this.rank});

  final SiteSummary site;
  final int rank;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: () => Get.toNamed(AppRoutes.siteDetailsPath(brand: site.brand, siteCode: site.siteCode)),
      borderRadius: BorderRadius.circular(18),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.softWhite,
          borderRadius: BorderRadius.circular(18),
          border: Border.all(color: AppColors.borderGray),
        ),
        child: LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxWidth < 680;
            final identity = Row(
              children: [
                Container(
                  width: 38,
                  height: 38,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(color: AppColors.tealSoft, borderRadius: BorderRadius.circular(14)),
                  child: Text('#$rank', style: const TextStyle(color: AppColors.deepTeal, fontWeight: FontWeight.w900, fontSize: 12)),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(site.siteName, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900)),
                      const SizedBox(height: 4),
                      Text('${site.brand} · ${site.siteCode}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: AppColors.mutedText, fontSize: 11, fontWeight: FontWeight.w700)),
                    ],
                  ),
                ),
              ],
            );
           final metrics = Wrap(
  spacing: 8,
  runSpacing: 8,
  alignment: WrapAlignment.end,
  children: [
    _EnergyPill(label: 'Day', value: Formatters.energy(site.dayEnergyKwh)),
    _EnergyPill(label: 'Month', value: Formatters.energy(site.monthEnergyKwh)),
    _EnergyPill(label: 'Year', value: Formatters.energy(site.yearEnergyKwh)),
    _EnergyPill(label: 'Total', value: Formatters.energy(site.totalEnergyKwh)),
    StatusBadge(status: site.status, compact: true),
  ],
);
            if (compact) {
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [identity, const SizedBox(height: 12), metrics]);
            }
            return Row(children: [Expanded(child: identity), const SizedBox(width: 14), metrics]);
          },
        ),
      ),
    );
  }
}

class _SitesPagination extends StatelessWidget {
  const _SitesPagination({required this.controller});

  final DashboardController controller;

  @override
  Widget build(BuildContext context) {
    return Obx(() => Wrap(
          spacing: 12,
          runSpacing: 8,
          crossAxisAlignment: WrapCrossAlignment.center,
          alignment: WrapAlignment.end,
          children: [
            Text(
'${controller.pageStartIndex}-${controller.pageEndIndex} / ${controller.totalFilteredSites}',
              style: const TextStyle(color: AppColors.mutedText, fontWeight: FontWeight.w800),
            ),
            IconButton.filledTonal(
              onPressed: controller.sitePage.value == 0 ? null : controller.previousSitePage,
              icon: const Icon(Icons.chevron_left_rounded),
            ),
            Text(
              '${controller.sitePage.value + 1} / ${controller.totalSitePages}',
              style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900),
            ),
            IconButton.filledTonal(
              onPressed: controller.sitePage.value >= controller.totalSitePages - 1 ? null : controller.nextSitePage,
              icon: const Icon(Icons.chevron_right_rounded),
            ),
          ],
        ));
  }
}

class _EnergyPill extends StatelessWidget {
  const _EnergyPill({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
      decoration: BoxDecoration(
        color: AppColors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(label, style: const TextStyle(color: AppColors.mutedText, fontSize: 10, fontWeight: FontWeight.w800)),
          const SizedBox(height: 2),
          Text(value, style: const TextStyle(color: AppColors.midnightNavy, fontWeight: FontWeight.w900, fontSize: 12)),
        ],
      ),
    );
  }
}

class _SmallDropdown extends StatelessWidget {
  const _SmallDropdown({required this.width, required this.value, required this.values, required this.onChanged, this.labels = const {}});

  final double width;
  final String value;
  final List<String> values;
  final Map<String, String> labels;
  final ValueChanged<String?> onChanged;

  @override
  Widget build(BuildContext context) {
    final normalized = values.toSet().toList();
    final safeValue = normalized.contains(value) ? value : normalized.first;
    return SizedBox(
      width: width,
      child: DropdownButtonFormField<String>(
        value: safeValue,
        items: normalized
            .map((item) => DropdownMenuItem<String>(
                  value: item,
                  child: Text(labels[item] ?? item, overflow: TextOverflow.ellipsis),
                ))
            .toList(),
        onChanged: onChanged,
        decoration: const InputDecoration(isDense: true),
      ),
    );
  }
}
