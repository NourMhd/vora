import 'package:flutter/material.dart';
import 'package:get/get.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/utils/formatters.dart';
import '../../../core/widgets/loading_state.dart';
import '../../../core/widgets/premium_card.dart';
import '../../../data/models/site_swap_date.dart';
import '../controllers/swap_dates_controller.dart';

class SwapDatesPage extends GetView<SwapDatesController> {
  const SwapDatesPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.softWhite,
      body: Obx(() {
        if (controller.isLoading.value && controller.items.isEmpty) {
          return const LoadingState(label: 'Chargement des dates de swap...');
        }

        if (controller.errorMessage.value != null && controller.items.isEmpty) {
          return ErrorState(
            message: controller.errorMessage.value!,
            onRetry: () => controller.loadPage(controller.currentPage.value),
          );
        }

        return CustomScrollView(
          slivers: [
            _SwapDatesHeader(controller: controller),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(26, 16, 26, 26),
              sliver: SliverList(
                delegate: SliverChildListDelegate([
                  _ActionsCard(controller: controller),
                  const SizedBox(height: 18),
                  _SwapDatesContent(controller: controller),
                  const SizedBox(height: 16),
                  _PaginationCard(controller: controller),
                ]),
              ),
            ),
          ],
        );
      }),
    );
  }
}

class _SwapDatesHeader extends StatelessWidget {
  const _SwapDatesHeader({required this.controller});

  final SwapDatesController controller;

  @override
  Widget build(BuildContext context) {
    return SliverAppBar(
      pinned: true,
      automaticallyImplyLeading: false,
      expandedHeight: 285,
      toolbarHeight: 285,
      backgroundColor: AppColors.softWhite,
      surfaceTintColor: Colors.transparent,
      flexibleSpace: Padding(
        padding: const EdgeInsets.fromLTRB(26, 24, 26, 14),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.end,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.end,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'Swaps dates',
                        style: TextStyle(
                          color: AppColors.midnightNavy,
                          fontSize: 34,
                          fontWeight: FontWeight.w900,
                          letterSpacing: -1.1,
                        ),
                      ),
                      const SizedBox(height: 6),
                      const Text(
                        'Attribuez ou modifiez la date de swap de chaque site.',
                        style: TextStyle(
                          color: AppColors.mutedText,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Obx(
                        () => Text(
                          'Page ${controller.currentPage.value + 1}/${controller.totalPages.value == 0 ? 1 : controller.totalPages.value} · ${controller.totalElements.value} site(s) · ${Formatters.dateTime(controller.lastRefresh.value)}',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            color: AppColors.mutedText,
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                Obx(
                  () => IconButton.filledTonal(
                    tooltip: 'Rafraîchir',
                    onPressed: controller.isLoading.value ? null : controller.refreshCurrentPage,
                    icon: controller.isLoading.value
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.refresh_rounded),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 18),
            LayoutBuilder(
              builder: (context, constraints) {
                final compact = constraints.maxWidth < 900;

                final searchField = Obx(
                  () => TextField(
                    onChanged: controller.updateSearch,
                    decoration: InputDecoration(
                      hintText: 'Rechercher par nom du site...',
                      prefixIcon: const Icon(Icons.search_rounded),
                      suffixIcon: controller.searchQuery.value.isNotEmpty
                          ? IconButton(
                              tooltip: 'Effacer',
                              onPressed: controller.clearSearch,
                              icon: const Icon(Icons.close_rounded),
                            )
                          : null,
                      filled: true,
                      fillColor: AppColors.white,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                        borderSide: const BorderSide(color: AppColors.borderGray),
                      ),
                      enabledBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                        borderSide: const BorderSide(color: AppColors.borderGray),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(18),
                        borderSide: const BorderSide(
                          color: AppColors.deepTeal,
                          width: 1.4,
                        ),
                      ),
                    ),
                  ),
                );

                final filters = Obx(
                  () => Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      _SwapFilterChip(
                        label: 'Tous',
                        icon: Icons.apps_rounded,
                        selected: controller.swapFilter.value == 'all',
                        onTap: () => controller.updateSwapFilter('all'),
                      ),
                      _SwapFilterChip(
                        label: 'Swapé',
                        icon: Icons.check_circle_rounded,
                        selected: controller.swapFilter.value == 'swapped',
                        onTap: () => controller.updateSwapFilter('swapped'),
                      ),
                      _SwapFilterChip(
                        label: 'Non swapé',
                        icon: Icons.radio_button_unchecked_rounded,
                        selected: controller.swapFilter.value == 'unswapped',
                        onTap: () => controller.updateSwapFilter('unswapped'),
                      ),
                    ],
                  ),
                );

                if (compact) {
                  return Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        width: double.infinity,
                        child: searchField,
                      ),
                      const SizedBox(height: 12),
                      filters,
                    ],
                  );
                }

                return Row(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    Expanded(child: searchField),
                    const SizedBox(width: 12),
                    filters,
                  ],
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _ActionsCard extends StatelessWidget {
  const _ActionsCard({required this.controller});

  final SwapDatesController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.all(18),
      child: Obx(
        () => Wrap(
          spacing: 12,
          runSpacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            const _MetricPill(
              icon: Icons.calendar_month_rounded,
              label: 'Format',
              value: 'dd/mm/aaaa',
            ),
            _MetricPill(
              icon: Icons.edit_calendar_rounded,
              label: 'Modifs',
              value: controller.changedCount.value.toString(),
              active: controller.hasChanges.value,
            ),
            const _MetricPill(
              icon: Icons.view_week_rounded,
              label: 'Pagination',
              value: '${SwapDatesController.pageSize}/page',
            ),
            FilledButton.icon(
              onPressed: controller.hasChanges.value && !controller.isSaving.value ? controller.saveChanges : null,
              icon: controller.isSaving.value
                  ? const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.cloud_done_rounded),
              label: const Text('Appliquer les modifs'),
            ),
            OutlinedButton.icon(
              onPressed: controller.hasChanges.value && !controller.isSaving.value ? controller.cancelChanges : null,
              icon: const Icon(Icons.undo_rounded),
              label: const Text('Annuler'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SwapDatesContent extends StatelessWidget {
  const _SwapDatesContent({required this.controller});

  final SwapDatesController controller;

  @override
  Widget build(BuildContext context) {
    if (controller.items.isEmpty) {
      return const PremiumCard(
        child: EmptyState(
          title: 'Aucun site trouvé',
          message: 'Aucun site ne correspond aux filtres sélectionnés.',
          icon: Icons.domain_disabled_rounded,
        ),
      );
    }

    return LayoutBuilder(
      builder: (context, constraints) {
        final desktop = constraints.maxWidth >= 860;

        return PremiumCard(
          padding: EdgeInsets.zero,
          child: desktop ? _DesktopSwapTable(controller: controller) : _MobileSwapList(controller: controller),
        );
      },
    );
  }
}

class _DesktopSwapTable extends StatelessWidget {
  const _DesktopSwapTable({required this.controller});

  final SwapDatesController controller;

  static const double _gap = 18;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
          decoration: const BoxDecoration(
            color: Color(0xFFF1F5F9),
            borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
          ),
          child: const Row(
            children: [
              Expanded(flex: 1, child: _HeaderLabel('Marque')),
              SizedBox(width: _gap),
              Expanded(flex: 3, child: _HeaderLabel('Site')),
              SizedBox(width: _gap),
              Expanded(flex: 2, child: _HeaderLabel('Site code')),
              SizedBox(width: _gap),
              Expanded(flex: 2, child: _HeaderLabel('Grid connection')),
              SizedBox(width: _gap),
              Expanded(flex: 2, child: _HeaderLabel('Date de swap')),
              SizedBox(width: _gap),
              Expanded(flex: 1, child: _HeaderLabel('État')),
            ],
          ),
        ),
        ...controller.items.map(
          (item) => _SwapDateRow(controller: controller, item: item),
        ),
      ],
    );
  }
}
class _MobileSwapList extends StatelessWidget {
  const _MobileSwapList({required this.controller});

  final SwapDatesController controller;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(14),
      child: Column(
        children: controller.items
            .map(
              (item) => Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: _MobileSwapCard(controller: controller, item: item),
              ),
            )
            .toList(),
      ),
    );
  }
}

class _SwapDateRow extends StatelessWidget {
  const _SwapDateRow({
    required this.controller,
    required this.item,
  });

  final SwapDatesController controller;
  final SiteSwapDate item;

  static const double _gap = 18;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 12),
      decoration: const BoxDecoration(
        border: Border(top: BorderSide(color: AppColors.borderGray)),
      ),
      child: Row(
        children: [
          Expanded(
            flex: 1,
            child: Align(
              alignment: Alignment.centerLeft,
              child:  Text(
            item.brand,
            style: const TextStyle(
              color: AppColors.mutedText,
              fontWeight: FontWeight.w800,
            ),
          ),
            ),
          ),
          const SizedBox(width: _gap),

          Expanded(
            flex: 3,
            child: Text(
              item.siteName,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppColors.midnightNavy,
                fontWeight: FontWeight.w900,
              ),
            ),
          ),
          const SizedBox(width: _gap),

          Expanded(
            flex: 2,
            child: Text(
              item.siteCode,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppColors.mutedText,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
          const SizedBox(width: _gap),

          Expanded(
            flex: 2,
            child: Text(
              item.gridConnectionDate ?? '-',
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(
                color: AppColors.mutedText,
                fontWeight: FontWeight.w800,
              ),
            ),
          ),
          const SizedBox(width: _gap),

          Expanded(
            flex: 2,
            child: _DateField(controller: controller, item: item),
          ),
          const SizedBox(width: _gap),

          Expanded(
            flex: 1,
            child: Align(
              alignment: Alignment.centerLeft,
              child: _StateChip(controller: controller, item: item),
            ),
          ),
        ],
      ),
    );
  }
}

class _MobileSwapCard extends StatelessWidget {
  const _MobileSwapCard({
    required this.controller,
    required this.item,
  });

  final SwapDatesController controller;
  final SiteSwapDate item;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xFFF8FAFC),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: AppColors.borderGray),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text(
            item.brand,
            style: const TextStyle(
              color: AppColors.mutedText,
              fontWeight: FontWeight.w800,
            ),
          ),
             
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  item.siteName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.midnightNavy,
                    fontWeight: FontWeight.w900,
                  ),
                ),
              ),
              _StateChip(controller: controller, item: item),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            item.siteCode,
            style: const TextStyle(
              color: AppColors.mutedText,
              fontWeight: FontWeight.w800,
            ),
          ),
          const SizedBox(height: 6),
          Row(
            children: [
              const Icon(
                Icons.power_rounded,
                size: 16,
                color: AppColors.mutedText,
              ),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  'Grid connection: ${item.gridConnectionDate ?? '-'}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          _DateField(controller: controller, item: item),
        ],
      ),
    );
  }
}

class _DateField extends StatelessWidget {
  const _DateField({
    required this.controller,
    required this.item,
  });

  final SwapDatesController controller;
  final SiteSwapDate item;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      final error = controller.fieldErrorFor(item);

      return TextField(
        controller: controller.textControllerFor(item),
        keyboardType: TextInputType.datetime,
        decoration: InputDecoration(
          isDense: true,
          hintText: 'dd/mm/aaaa',
          errorText: error,
          prefixIcon: const Icon(Icons.event_rounded, size: 19),
          suffixIcon: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              IconButton(
                tooltip: 'Choisir',
                onPressed: () => controller.pickDate(context, item),
                icon: const Icon(Icons.calendar_today_rounded, size: 18),
              ),
              IconButton(
                tooltip: 'Effacer',
                onPressed: () => controller.clearDate(item),
                icon: const Icon(Icons.close_rounded, size: 18),
              ),
            ],
          ),
        ),
      );
    });
  }
}

class _PaginationCard extends StatelessWidget {
  const _PaginationCard({required this.controller});

  final SwapDatesController controller;

  @override
  Widget build(BuildContext context) {
    return PremiumCard(
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
      child: Obx(() {
        final currentPage = controller.currentPage.value;
        final totalPages = controller.totalPages.value;
        final totalElements = controller.totalElements.value;
        final isLoading = controller.isLoading.value;

        final safeTotalPages = totalPages == 0 ? 1 : totalPages;

        return LayoutBuilder(
          builder: (context, constraints) {
            final compact = constraints.maxWidth < 700;

            final pageBadge = Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: AppColors.tealSoft,
                borderRadius: BorderRadius.circular(14),
                border: Border.all(
                  color: AppColors.deepTeal.withValues(alpha: 0.18),
                ),
              ),
              child: Text(
                'Page ${currentPage + 1}/$safeTotalPages',
                style: const TextStyle(
                  color: AppColors.deepTeal,
                  fontWeight: FontWeight.w900,
                ),
              ),
            );

            final previous = OutlinedButton.icon(
              onPressed: currentPage > 0 && !isLoading
                  ? controller.previousPage
                  : null,
              icon: const Icon(Icons.chevron_left_rounded),
              label: const Text('Précédent'),
            );

            final next = FilledButton.icon(
              onPressed: currentPage + 1 < totalPages && !isLoading
                  ? controller.nextPage
                  : null,
              icon: const Icon(Icons.chevron_right_rounded),
              label: const Text('Suivant'),
            );

            if (compact) {
              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '$totalElements site(s)',
                    style: const TextStyle(
                      color: AppColors.mutedText,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Wrap(
                    spacing: 10,
                    runSpacing: 10,
                    crossAxisAlignment: WrapCrossAlignment.center,
                    children: [
                      previous,
                      pageBadge,
                      next,
                    ],
                  ),
                ],
              );
            }

            return Row(
              children: [
                Text(
                  '$totalElements site(s)',
                  style: const TextStyle(
                    color: AppColors.mutedText,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const Spacer(),
                previous,
                const SizedBox(width: 10),
                pageBadge,
                const SizedBox(width: 10),
                next,
              ],
            );
          },
        );
      }),
    );
  }
}

class _MetricPill extends StatelessWidget {
  const _MetricPill({
    required this.icon,
    required this.label,
    required this.value,
    this.active = false,
  });

  final IconData icon;
  final String label;
  final String value;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 10),
      decoration: BoxDecoration(
        color: active ? AppColors.tealSoft : const Color(0xFFF8FAFC),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: active ? AppColors.deepTeal.withValues(alpha: 0.25) : AppColors.borderGray,
        ),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(
            icon,
            size: 18,
            color: active ? AppColors.deepTeal : AppColors.mutedText,
          ),
          const SizedBox(width: 8),
          Text(
            '$label · ',
            style: const TextStyle(
              color: AppColors.mutedText,
              fontWeight: FontWeight.w800,
            ),
          ),
          Text(
            value,
            style: TextStyle(
              color: active ? AppColors.deepTeal : AppColors.midnightNavy,
              fontWeight: FontWeight.w900,
            ),
          ),
        ],
      ),
    );
  }
}



class _StateChip extends StatelessWidget {
  const _StateChip({
    required this.controller,
    required this.item,
  });

  final SwapDatesController controller;
  final SiteSwapDate item;

  @override
  Widget build(BuildContext context) {
    return Obx(() {
      // Important :
      // Cette ligne force GetX à écouter une vraie variable observable.
      // Sans ça, GetX peut considérer que l'Obx est mal utilisé.
      controller.changedCount.value;

      final error = controller.fieldErrorFor(item);
      final modified = controller.isModified(item);

      final label = error != null
          ? 'Erreur'
          : modified
              ? 'Modifié'
              : 'OK';

      final color = error != null
          ? AppColors.faulty
          : modified
              ? AppColors.warning
              : AppColors.healthy;

      return Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: color.withValues(alpha: 0.10),
          borderRadius: BorderRadius.circular(999),
          border: Border.all(
            color: color.withValues(alpha: 0.18),
          ),
        ),
        child: Text(
          label,
          textAlign: TextAlign.center,
          style: TextStyle(
            color: color,
            fontSize: 11,
            fontWeight: FontWeight.w900,
          ),
        ),
      );
    });
  }
}

class _HeaderLabel extends StatelessWidget {
  const _HeaderLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text.toUpperCase(),
      style: const TextStyle(
        color: AppColors.mutedText,
        fontSize: 11,
        fontWeight: FontWeight.w900,
        letterSpacing: 0.8,
      ),
    );
  }
}

class _SwapFilterChip extends StatelessWidget {
  const _SwapFilterChip({
    required this.label,
    required this.icon,
    required this.selected,
    required this.onTap,
  });

  final String label;
  final IconData icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final foreground = selected ? AppColors.white : AppColors.midnightNavy;
    final background = selected ? AppColors.deepTeal : AppColors.white;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(999),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 9),
          decoration: BoxDecoration(
            color: background,
            borderRadius: BorderRadius.circular(999),
            border: Border.all(
              color: selected ? AppColors.deepTeal : AppColors.borderGray,
            ),
            boxShadow: selected
                ? [
                    BoxShadow(
                      color: AppColors.deepTeal.withValues(alpha: 0.18),
                      blurRadius: 14,
                      offset: const Offset(0, 7),
                    ),
                  ]
                : const [],
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(icon, size: 17, color: foreground),
              const SizedBox(width: 7),
              Text(
                label,
                style: TextStyle(
                  color: foreground,
                  fontWeight: FontWeight.w900,
                  fontSize: 12,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}