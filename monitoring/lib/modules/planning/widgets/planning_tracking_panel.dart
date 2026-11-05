import 'dart:async';
import 'package:flutter/material.dart';
import '../../../core/network/api_client.dart';

/// Local backend reads only. Provider synchronization keeps its existing schedule.
class PlanningTrackingPanel extends StatefulWidget {
  const PlanningTrackingPanel({
    super.key,
    required this.api,
    required this.tasks,
    required this.onMission,
    required this.onPlan,
  });
  final ApiClient api;
  final List<Map<String, dynamic>> tasks;
  final Future<void> Function(Map<String, dynamic>) onMission;
  final Future<void> Function(Map<String, dynamic>, String) onPlan;
  @override
  State<PlanningTrackingPanel> createState() => _PlanningTrackingPanelState();
}

class _PlanningTrackingPanelState extends State<PlanningTrackingPanel> {
  Map<String, dynamic> overview = {};
  List<Map<String, dynamic>> results = [];
  String? error;
  bool loading = true,
      fetching = false,
      followupOnly = false,
      reloadRequested = false;
  int tab = 0, page = 0;
  String movement = 'Tous';
  final search = TextEditingController();
  final horizontal = ScrollController();
  DateTime from = DateUtils.dateOnly(
    DateTime.now(),
  ).subtract(const Duration(days: 30));
  DateTime to = DateUtils.dateOnly(
    DateTime.now().toUtc().add(const Duration(hours: 1)),
  );
  Timer? timer;
  List<Map<String, dynamic>> maps(dynamic v) => v is List
      ? v.whereType<Map>().map((m) => Map<String, dynamic>.from(m)).toList()
      : [];
  String iso(DateTime d) =>
      '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
  String date(dynamic v) {
    final d = DateTime.tryParse(v?.toString() ?? '');
    if (d == null) return v == null ? '—' : '$v';
    final local = d.isUtc ? d.add(const Duration(hours: 1)) : d;
    return '${local.day.toString().padLeft(2, '0')}/${local.month.toString().padLeft(2, '0')}/${local.year}${v.toString().contains('T') ? ' ${local.hour.toString().padLeft(2, '0')}:${local.minute.toString().padLeft(2, '0')}' : ''}';
  }

  String state(dynamic v) => switch (v?.toString().toUpperCase()) {
    'HEALTHY' => 'Connecté',
    'FAULTY' => 'En défaut',
    'DISCONNECTED' => 'Hors ligne',
    'WITHOUT_MONITORING' => 'Sans monitoring',
    _ => 'Inconnu',
  };
  String observed(Map site) {
    if (site['brand'] == 'MANUAL') return 'Site manuel · Sans monitoring';
    final timestamp = DateTime.tryParse('${site['observedAt'] ?? ''}');
    final stale =
        timestamp == null ||
        DateTime.now().toUtc().difference(timestamp.toUtc()) >
            const Duration(minutes: 15);
    return '${site['active'] == false ? 'Retiré du parc' : state(site['status'])}${stale ? ' · non récent' : ''}\n${date(site['observedAt'])}';
  }

  String kind(dynamic v) => switch (v) {
    'ADDED' => 'Ajout',
    'REMOVED' => 'Retrait confirmé',
    'REACTIVATED' => 'Réactivation',
    'LOCAL_REMOVED' => 'Retrait de MongoDB',
    'STATE_CHANGED' => 'Changement d’état',
    'GAP' => 'Période sans observation',
    'DATES_UPDATED' => 'Dates modifiées',
    'BASELINE' => 'Parc de référence',
    'MISSION_ASSIGNED' => 'Affectation à une mission',
    'MISSION_COMPLETED' => 'Réalisation déclarée',
    _ => '$v',
  };
  @override
  void initState() {
    super.initState();
    load();
    timer = Timer.periodic(
      const Duration(minutes: 1),
      (_) => load(silent: true),
    );
  }

  @override
  void didUpdateWidget(covariant PlanningTrackingPanel oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!identical(oldWidget.tasks, widget.tasks)) load(silent: true);
  }

  @override
  void dispose() {
    timer?.cancel();
    search.dispose();
    horizontal.dispose();
    super.dispose();
  }

  Future<void> load({bool silent = false}) async {
    if (fetching) {
      reloadRequested = true;
      return;
    }
    fetching = true;
    if (!silent) {
      setState(() {
        loading = true;
        error = null;
      });
    }
    try {
      final data = await Future.wait([
        widget.api.get(
          '/api/planning/tracking/overview',
          queryParameters: {'from': iso(from), 'to': iso(to)},
        ),
        widget.api.get('/api/planning/tracking/results'),
      ]);
      if (!mounted) return;
      setState(() {
        overview = Map<String, dynamic>.from(data[0] as Map);
        results = maps(data[1]);
        error = null;
      });
    } catch (e) {
      if (mounted) setState(() => error = 'Suivi indisponible : $e');
    } finally {
      fetching = false;
      if (mounted) {
        setState(() => loading = false);
        if (reloadRequested) {
          reloadRequested = false;
          unawaited(load());
        }
      }
    }
  }

  Future<void> history(Map<String, dynamic> site) async {
    final id = site['id'];
    if (id == null) return;
    var historyPage = 0;
    var items = <Map<String, dynamic>>[];
    var busy = true, hasMore = false, started = false;
    String? message;
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (dialogContext) => StatefulBuilder(
        builder: (context, update) {
          Future<void> fetch() async {
            try {
              final next = maps(
                await widget.api.get(
                  '/api/planning/tracking/sites/${Uri.encodeComponent('$id')}/history',
                  queryParameters: {'page': historyPage},
                ),
              );
              if (!dialogContext.mounted) return;
              update(() {
                items.addAll(next);
                hasMore = next.length == 100;
                busy = false;
                historyPage++;
              });
            } catch (e) {
              if (dialogContext.mounted) {
                update(() {
                  busy = false;
                  message = '$e';
                });
              }
            }
          }

          if (!started) {
            started = true;
            // Trigger once after layout, never from a repeated build.

            WidgetsBinding.instance.addPostFrameCallback((_) {
              fetch();
            });
          }
          return AlertDialog(
            title: Text('Historique · ${site['siteName'] ?? site['siteCode']}'),
            content: SizedBox(
              width: 760,
              height: 440,
              child: Column(
                children: [
                  const Text(
                    'Dates d’observation VORA · les transitions entre deux collectes peuvent ne pas être connues.',
                    style: TextStyle(fontSize: 12, color: Colors.blueGrey),
                  ),
                  if (message != null)
                    Text(message!, style: const TextStyle(color: Colors.red)),
                  Expanded(
                    child: busy && items.isEmpty
                        ? const Center(child: CircularProgressIndicator())
                        : ListView.builder(
                            itemCount: items.length,
                            itemBuilder: (_, i) {
                              final e = items[i];
                              return ListTile(
                                dense: true,
                                leading: Icon(
                                  e['kind'] == 'GAP'
                                      ? Icons.warning_amber
                                      : Icons.history,
                                ),
                                title: Text(
                                  '${kind(e['kind'])} · ${date(e['at'])}',
                                ),
                                subtitle: Text(
                                  e['kind'] == 'GAP'
                                      ? 'Pas d’observation entre ${date(e['gapStart'])} et ${date(e['at'])}'
                                      : '${state(e['before'])} → ${state(e['after'])}\nMES : ${date(e['commissionedAt'])} · Swap : ${date(e['swapDate'])}',
                                ),
                              );
                            },
                          ),
                  ),
                  if (hasMore)
                    TextButton(
                      onPressed: busy
                          ? null
                          : () {
                              update(() => busy = true);
                              fetch();
                            },
                      child: const Text('Charger les événements précédents'),
                    ),
                ],
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(dialogContext),
                child: const Text('Fermer'),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget metric(String title, dynamic value, {VoidCallback? onTap}) => SizedBox(
    width: 155,
    child: Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                '${value ?? '—'}',
                style: const TextStyle(
                  fontSize: 25,
                  fontWeight: FontWeight.w700,
                  color: Color(0xFF126F69),
                ),
              ),
              Text(title, style: const TextStyle(fontSize: 12)),
            ],
          ),
        ),
      ),
    ),
  );
  Future<void> pick(bool start) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: start ? from : to,
      firstDate: DateTime(2020),
      lastDate: DateUtils.dateOnly(
        DateTime.now().toUtc().add(const Duration(hours: 1)),
      ),
    );
    if (picked == null || !mounted) return;
    setState(() {
      if (start) {
        from = picked;
        if (to.isBefore(from)) to = from;
      } else {
        to = picked;
        if (from.isAfter(to)) from = to;
      }
      page = 0;
    });
    await load();
  }

  Widget value(String text, {double width = 150}) => SizedBox(
    width: width,
    child: Tooltip(
      message: text,
      child: Text(text, maxLines: 3, overflow: TextOverflow.ellipsis),
    ),
  );
  Widget table(List<String> headers, List<List<Widget>> data) {
    if (data.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(28),
        child: Text('Aucun résultat pour ces filtres.'),
      );
    }
    final pages = (data.length / 15).ceil();
    final current = page.clamp(0, pages - 1);
    return Column(
      children: [
        LayoutBuilder(
          builder: (context, constraints) => Scrollbar(
            controller: horizontal,
            thumbVisibility: true,
            child: SingleChildScrollView(
              controller: horizontal,
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.only(bottom: 14),
              child: ConstrainedBox(
                constraints: BoxConstraints(minWidth: constraints.maxWidth),
                child: DataTable(
                  headingRowColor: WidgetStateProperty.all(
                    const Color(0xFFF0F5F8),
                  ),
                  dataRowMinHeight: 60,
                  dataRowMaxHeight: 85,
                  columns: headers
                      .map(
                        (h) => DataColumn(
                          label: Text(
                            h,
                            style: const TextStyle(fontWeight: FontWeight.w700),
                          ),
                        ),
                      )
                      .toList(),
                  rows: data
                      .skip(current * 15)
                      .take(15)
                      .map(
                        (row) => DataRow(
                          cells: row.map((w) => DataCell(w)).toList(),
                        ),
                      )
                      .toList(),
                ),
              ),
            ),
          ),
        ),
        Row(
          children: [
            Expanded(
              child: Text(
                '${data.length} résultats · page ${current + 1}/$pages',
                style: const TextStyle(fontSize: 12),
              ),
            ),
            IconButton(
              tooltip: 'Page précédente',
              onPressed: current == 0
                  ? null
                  : () => setState(() => page = current - 1),
              icon: const Icon(Icons.chevron_left),
            ),
            IconButton(
              tooltip: 'Page suivante',
              onPressed: current == pages - 1
                  ? null
                  : () => setState(() => page = current + 1),
              icon: const Icon(Icons.chevron_right),
            ),
          ],
        ),
      ],
    );
  }

  Widget actions(Map<String, dynamic> row, {bool plan = false}) {
    final site = Map<String, dynamic>.from(row['site'] as Map);
    final task = widget.tasks
        .where((t) => t['id'] == row['taskId'])
        .firstOrNull;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        IconButton(
          tooltip: site['brand'] == 'MANUAL'
              ? 'Historique disponible dans la mission'
              : 'Historique du site',
          onPressed: site['brand'] == 'MANUAL' ? null : () => history(site),
          icon: const Icon(Icons.timeline),
        ),
        if (task != null)
          IconButton(
            tooltip: 'Consulter / modifier la mission',
            onPressed: () async {
              await widget.onMission(task);
              if (mounted) load();
            },
            icon: const Icon(Icons.assignment_outlined),
          ),
        if (widget.tasks.any((t) => t['id'] == row['followupOfTaskId']))
          IconButton(
            tooltip: 'Ouvrir la mission précédente',
            icon: const Icon(Icons.link),
            onPressed: () => widget.onMission(
              widget.tasks.firstWhere(
                (t) => t['id'] == row['followupOfTaskId'],
              ),
            ),
          ),
        if (plan && site['active'] == true)
          IconButton(
            tooltip: 'Planifier une mission de reprise',
            onPressed: () async {
              await widget.onPlan(site, '${row['taskId']}');
              if (mounted) load();
            },
            icon: const Icon(Icons.add_task),
          ),
      ],
    );
  }

  Widget missions() {
    final query = search.text.trim().toLowerCase();
    final rows = results
        .where(
          (r) =>
              '${r['project']} ${r['team']} ${r['site']} ${r['missionStatus']}'
                  .toLowerCase()
                  .contains(query),
        )
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Références des sites affectés aux missions. L’état à l’affectation est conservé, sans être remplacé lors d’une modification.',
          style: TextStyle(color: Colors.blueGrey),
        ),
        const SizedBox(height: 12),
        table(
          [
            'Actions',
            'Mission / équipe',
            'Site',
            'À l’affectation',
            'À la clôture déclarée',
            'État actuel observé',
          ],
          rows.map((r) {
            final s = r['site'] as Map;
            final a = r['assigned'] as Map?;
            final c = r['atCompletion'] as Map?;
            return [
              actions(r),
              value(
                '${r['project'] ?? 'Mission'}\n${r['team']} · ${r['missionStatus']}',
              ),
              value('${s['siteName']}\n${s['brand'] == 'MANUAL' ? 'Site manuel' : s['brand']}'),
              value(
                s['brand'] == 'MANUAL'
                    ? 'Sans monitoring'
                    : a == null
                    ? 'Référence non enregistrée'
                    : '${state(a['status'])}\n${date(a['observedAt'])}${r['referenceLate'] == true ? '\nRéférence prise après affectation' : ''}',
              ),
              value(
                s['brand'] == 'MANUAL'
                    ? (c == null ? 'Pas encore clôturée' : 'Réalisation déclarée · sans monitoring')
                    : c == null
                    ? 'Pas encore clôturée'
                    : '${state(c['status'])}\n${date(c['observedAt'])}',
              ),
              value(observed(s)),
            ];
          }).toList(),
        ),
      ],
    );
  }

  Widget evolution() {
    final query = search.text.trim().toLowerCase();
    final events = maps(overview['events'])
        .where(
          (e) =>
              (movement == 'Tous' ||
                  (movement == 'Ajout' && e['kind'] == 'ADDED') ||
                  (movement == 'Retrait' &&
                      (e['kind'] == 'REMOVED' ||
                          e['kind'] == 'LOCAL_REMOVED'))) &&
              '${e['siteName']} ${e['siteCode']}'.toLowerCase().contains(query),
        )
        .toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton.icon(
              onPressed: () => pick(true),
              icon: const Icon(Icons.date_range, size: 18),
              label: Text('Du ${date(iso(from))}'),
            ),
            OutlinedButton.icon(
              onPressed: () => pick(false),
              icon: const Icon(Icons.date_range, size: 18),
              label: Text('Au ${date(iso(to))}'),
            ),
          ],
        ),
        const SizedBox(height: 12),
        Wrap(
          children: [
            metric('Parc au début', overview['startCount']),
            metric(
              'Ajouts détectés',
              overview['added'],
              onTap: () => setState(() {
                movement = 'Ajout';
                page = 0;
              }),
            ),
            metric('Retraits confirmés', overview['removed']),
            metric('Réactivations', overview['reactivated']),
            metric('Parc en fin de période', overview['endCount']),
            metric('Parc actuel', overview['currentCount']),
          ],
        ),
        if (overview['partialPeriod'] == true)
          Text(
            'Période partielle : le suivi commence le ${date(overview['activatedAt'])}. Les sites présents au démarrage constituent le parc de référence.',
            style: const TextStyle(color: Colors.deepOrange, fontSize: 12),
          ),
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          children: ['Tous', 'Ajout', 'Retrait']
              .map(
                (m) => ChoiceChip(
                  label: Text(m),
                  selected: movement == m,
                  onSelected: (_) => setState(() {
                    movement = m;
                    page = 0;
                  }),
                ),
              )
              .toList(),
        ),
        table(
          [
            'Actions',
            'Site / marque',
            'Mouvement',
            'Détecté le',
            'Mise en service',
            'Date de swap',
            'État à l’événement',
          ],
          events
              .map(
                (e) => [
                  IconButton(
                    tooltip: 'Historique du site',
                    onPressed: () =>
                        history({'id': e['siteId'], 'siteName': e['siteName']}),
                    icon: const Icon(Icons.timeline),
                  ),
                  value('${e['siteName']}\n${e['brand']}'),
                  value(kind(e['kind'])),
                  value(date(e['at'])),
                  value(date(e['commissionedAt'])),
                  value(date(e['swapDate'])),
                  value(state(e['after'])),
                ],
              )
              .toList(),
        ),
      ],
    );
  }

  Widget outcomes() {
    final query = search.text.trim().toLowerCase();
    final fromDay = iso(from), toDay = iso(to);
    final rows = results.where((r) {
      final d = '${r['completedDate'] ?? r['plannedDate'] ?? ''}';
      return d.compareTo(fromDay) >= 0 &&
          d.compareTo(toDay) <= 0 &&
          (!followupOnly || r['needsFollowup'] == true) &&
          '${r['site']} ${r['project']} ${r['assessment']}'
              .toLowerCase()
              .contains(query);
    }).toList();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            OutlinedButton(
              onPressed: () => pick(true),
              child: Text('Du ${date(iso(from))}'),
            ),
            OutlinedButton(
              onPressed: () => pick(false),
              child: Text('Au ${date(iso(to))}'),
            ),
            FilterChip(
              label: const Text('Sites à reprendre'),
              selected: followupOnly,
              onSelected: (v) => setState(() {
                followupOnly = v;
                page = 0;
              }),
            ),
          ],
        ),
        Wrap(
          children: [
            metric('Sites / missions affichés', rows.length),
            metric(
              'Rétablis observés',
              rows
                  .where((r) => '${r['assessment']}'.startsWith('Rétabli'))
                  .length,
            ),
            metric(
              'Contrôle en cours',
              rows
                  .where((r) => '${r['assessment']}'.contains('72 h en cours'))
                  .length,
            ),
            metric(
              'À reprendre',
              rows.where((r) => r['needsFollowup'] == true).length,
            ),
            metric(
              'Rechutes',
              rows
                  .where((r) => '${r['assessment']}'.startsWith('Rechute'))
                  .length,
            ),
            metric(
              'Non vérifiables',
              rows
                  .where(
                    (r) => '${r['assessment']}'.startsWith('Non vérifiable'),
                  )
                  .length,
            ),
          ],
        ),
        const Padding(
          padding: EdgeInsets.symmetric(vertical: 10),
          child: Text(
            'Contrôle sur 72 h à partir de la déclaration de réalisation. Une amélioration observée ne prouve pas à elle seule l’efficacité de la mission. Études et réceptions : validation métier nécessaire.',
            style: TextStyle(color: Colors.blueGrey, fontSize: 12),
          ),
        ),
        table(
          [
            'Actions',
            'Site',
            'Mission',
            'Avant / à la clôture',
            'Dernier état',
            'Résultat observé',
            'Contrôle / contexte',
          ],
          rows.map((r) {
            final s = r['site'] as Map;
            final a = r['assigned'] as Map?;
            final c = r['atCompletion'] as Map?;
            return [
              actions(r, plan: true),
              value('${s['siteName']}'),
              value('${r['project'] ?? '—'}\n${r['type']}'),
              value(s['brand'] == 'MANUAL' ? 'Sans monitoring' : '${state(a?['status'])} / ${state(c?['status'])}'),
              value(observed(s)),
              value('${r['assessment']}', width: 230),
              value(
                '${s['brand'] == 'MANUAL' ? 'Validation terrain' : 'Fin prévue : ${date(r['windowEndsAt'])}'}${(r['nearbyMissions'] as num? ?? 0) > 0 ? '\nAutres missions proches : ${r['nearbyMissions']}' : ''}',
                width: 200,
              ),
            ];
          }).toList(),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) => Card(
    margin: EdgeInsets.zero,
    child: Padding(
      padding: const EdgeInsets.all(18),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Expanded(
                child: Text(
                  'Suivi des sites et des interventions',
                  style: TextStyle(fontSize: 21, fontWeight: FontWeight.w700),
                ),
              ),
              IconButton(
                tooltip: 'Actualiser le suivi',
                onPressed: fetching ? null : load,
                icon: const Icon(Icons.refresh),
              ),
            ],
          ),
          Text(
            'Suivi actif depuis ${date(overview['activatedAt'])} · contrôle local : ${date(overview['lastScanAt'])}',
            style: const TextStyle(color: Colors.blueGrey, fontSize: 12),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final entry in [
                (0, 'Missions'),
                (1, 'Évolution des sites'),
                (2, 'Résultats des interventions'),
              ])
                ChoiceChip(
                  label: Text(entry.$2),
                  selected: tab == entry.$1,
                  onSelected: (_) => setState(() {
                    tab = entry.$1;
                    page = 0;
                  }),
                ),
            ],
          ),
          const SizedBox(height: 12),
          TextField(
            controller: search,
            onChanged: (_) => setState(() => page = 0),
            decoration: const InputDecoration(
              prefixIcon: Icon(Icons.search),
              hintText: 'Rechercher un site ou une mission',
              border: OutlineInputBorder(),
              isDense: true,
            ),
          ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.all(10),
              child: Text(error!, style: const TextStyle(color: Colors.red)),
            ),
          if (overview['warning'] != null)
            Padding(
              padding: const EdgeInsets.all(10),
              child: Text(
                '${overview['warning']}',
                style: const TextStyle(color: Colors.deepOrange),
              ),
            ),
          const SizedBox(height: 12),
          if (loading && overview.isEmpty)
            const Center(child: CircularProgressIndicator())
          else if (overview['ready'] != true)
            const Padding(
              padding: EdgeInsets.all(20),
              child: Text(
                'Initialisation du parc de référence en cours. Actualisez dans quelques instants.',
              ),
            )
          else if (tab == 0)
            missions()
          else if (tab == 1)
            evolution()
          else
            outcomes(),
          const SizedBox(height: 8),
          Text(
            '${overview['stale'] ?? '—'} sites actifs sans observation récente (15 min). Le journal couvre les observations reçues, pas les changements survenus entre deux collectes.',
            style: const TextStyle(color: Colors.blueGrey, fontSize: 12),
          ),
        ],
      ),
    ),
  );
}
