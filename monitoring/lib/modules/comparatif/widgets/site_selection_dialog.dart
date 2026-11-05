import 'package:flutter/material.dart';
import '../../../data/models/daily_comparison_report.dart';

/// Edits a local draft. Cancel never changes the current comparison.
class SiteSelectionDialog extends StatefulWidget {
  const SiteSelectionDialog({
    super.key,
    required this.sites,
    required this.selected,
  });
  final List<DailyComparisonSelectableSite> sites;
  final Set<String> selected;
  @override
  State<SiteSelectionDialog> createState() => _SiteSelectionDialogState();
}

class _SiteSelectionDialogState extends State<SiteSelectionDialog> {
  late final Set<String> draft = Set.of(widget.selected);
  final search = TextEditingController();
  String? zone, governorate, brand;
  bool selectedOnly = false;
  @override
  void dispose() {
    search.dispose();
    super.dispose();
  }

  List<String> options(String? Function(DailyComparisonSelectableSite) field) =>
      widget.sites
          .map(field)
          .whereType<String>()
          .where((s) => s.isNotEmpty)
          .toSet()
          .toList()
        ..sort();
  List<DailyComparisonSelectableSite> get filtered {
    final query = search.text.trim().toLowerCase();
    final result = widget.sites
        .where(
          (s) =>
              (zone == null || s.zone == zone) &&
              (governorate == null || s.governorate == governorate) &&
              (brand == null || s.brand == brand) &&
              (!selectedOnly || draft.contains(s.siteCode)) &&
              (query.isEmpty ||
                  '${s.label} ${s.siteCode} ${s.governorate ?? ''}'
                      .toLowerCase()
                      .contains(query)),
        )
        .toList();
    result.sort((a, b) => a.label.compareTo(b.label));
    return result;
  }

  Widget filter(
    String label,
    String? value,
    List<String> values,
    ValueChanged<String?> onChanged,
  ) => SizedBox(
    width: 175,
    child: DropdownButtonFormField<String>(
      initialValue: value,
      isExpanded: true,
      decoration: InputDecoration(
        labelText: label,
        isDense: true,
        border: const OutlineInputBorder(),
      ),
      items: [
        DropdownMenuItem<String>(value: null, child: Text('Tous · $label')),
        ...values.map(
          (v) => DropdownMenuItem(
            value: v,
            child: Text(v, overflow: TextOverflow.ellipsis),
          ),
        ),
      ],
      onChanged: (v) => setState(() => onChanged(v)),
    ),
  );
  @override
  Widget build(BuildContext context) {
    final visible = filtered;
    return Dialog(
      child: SizedBox(
        width: 860,
        height: MediaQuery.sizeOf(context).height * .82,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Choisir les sites',
                      style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  IconButton(
                    onPressed: () => Navigator.pop(context),
                    tooltip: 'Annuler',
                    icon: const Icon(Icons.close),
                  ),
                ],
              ),
              const SizedBox(height: 12),
              TextField(
                controller: search,
                autofocus: true,
                onChanged: (_) => setState(() {}),
                decoration: InputDecoration(
                  hintText: 'Rechercher un nom, un code ou un gouvernorat',
                  prefixIcon: const Icon(Icons.search),
                  suffixIcon: IconButton(
                    tooltip: 'Effacer la recherche',
                    icon: const Icon(Icons.close),
                    onPressed: () => setState(search.clear),
                  ),
                  border: const OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 10,
                runSpacing: 10,
                children: [
                  filter('Zone', zone, options((s) => s.zone), (v) => zone = v),
                  filter(
                    'Gouvernorat',
                    governorate,
                    options((s) => s.governorate),
                    (v) => governorate = v,
                  ),
                  filter(
                    'Marque',
                    brand,
                    options((s) => s.brand),
                    (v) => brand = v,
                  ),
                  FilterChip(
                    label: const Text('Sélectionnés'),
                    selected: selectedOnly,
                    onSelected: (v) => setState(() => selectedOnly = v),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 12,
                crossAxisAlignment: WrapCrossAlignment.center,
                children: [
                  Text(
                    '${visible.length} résultats · ${draft.length} sélectionnés',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  TextButton(
                    onPressed: visible.isEmpty
                        ? null
                        : () => setState(
                            () => draft.addAll(visible.map((s) => s.siteCode)),
                          ),
                    child: const Text('Sélectionner les résultats'),
                  ),
                  TextButton(
                    onPressed: draft.isEmpty
                        ? null
                        : () => setState(draft.clear),
                    child: const Text('Tout désélectionner'),
                  ),
                ],
              ),
              const Divider(),
              Expanded(
                child: visible.isEmpty
                    ? const Center(
                        child: Text('Aucun site ne correspond aux filtres.'),
                      )
                    : ListView.builder(
                        itemCount: visible.length,
                        itemBuilder: (context, index) {
                          final site = visible[index];
                          return CheckboxListTile(
                            key: ValueKey(site.siteCode),
                            value: draft.contains(site.siteCode),
                            controlAffinity: ListTileControlAffinity.leading,
                            title: Text(
                              site.label,
                              style: const TextStyle(
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            subtitle: Text(
                              '${site.powerLabel} · ${site.governorate ?? 'Gouvernorat inconnu'} · ${site.brand ?? '—'}',
                            ),
                            onChanged: (v) => setState(() {
                              v == true
                                  ? draft.add(site.siteCode)
                                  : draft.remove(site.siteCode);
                            }),
                          );
                        },
                      ),
              ),
              const Divider(),
              Row(
                children: [
                  Expanded(child: Text('${draft.length} sites à comparer')),
                  TextButton(
                    onPressed: () => Navigator.pop(context),
                    child: const Text('Annuler'),
                  ),
                  const SizedBox(width: 8),
                  FilledButton(
                    onPressed: draft.isEmpty
                        ? null
                        : () => Navigator.pop(context, draft),
                    child: const Text('Valider la sélection'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}
