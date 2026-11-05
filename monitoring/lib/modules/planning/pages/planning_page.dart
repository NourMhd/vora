import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart' show kIsWeb;
import 'package:flutter/gestures.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import '../../../core/utils/file_downloader.dart';
import '../../../core/utils/planning_excel_export.dart';

import '../../../app/theme/app_colors.dart';
import '../../../core/network/api_client.dart';
import '../widgets/planning_tracking_panel.dart';

// Wait for the route to leave the overlay before disposing editor controllers.
Future<void> _showEditorDialog({
  required BuildContext context,
  required WidgetBuilder builder,
  bool barrierDismissible = true,
}) async {
  final navigator = Navigator.of(context, rootNavigator: true);
  final route = DialogRoute<void>(
    context: context,
    builder: builder,
    themes: InheritedTheme.capture(from: context, to: navigator.context),
    barrierDismissible: barrierDismissible,
  );
  await navigator.push<void>(route);
  await route.completed;
}

class PlanningPage extends StatefulWidget {
  const PlanningPage({super.key});

  @override
  State<PlanningPage> createState() => _PlanningPageState();
}

class _PlanningPageState extends State<PlanningPage> {
  final ApiClient api = Get.find<ApiClient>();
  static const statuses = <String>[
    'Planifié',
    'En cours',
    'Réalisé',
    'Partiellement réalisé',
    'Non réalisé',
    'Reporté',
    'Bloqué',
  ];
  static const priorities = <String>['Basse', 'Normale', 'Haute', 'Critique'];
  static const types = <String>[
    'Étude',
    'Installation',
    'Mise en service',
    'Maintenance / installation modems',
    'Réception',
    'Dépannage',
    'Monitoring',
  ];
  List<Map<String, dynamic>> teams = [];
  List<Map<String, dynamic>> tasks = [];
  List<Map<String, dynamic>> sites = [];
  List<String> missionIssuers = [];
  String? error;
  String? statusFilter;
  bool loading = true;
  final search = TextEditingController();
  final horizontalScroll = ScrollController();

  @override
  void initState() {
    super.initState();
    refresh();
  }

  @override
  void dispose() {
    search.dispose();
    horizontalScroll.dispose();
    if (kIsWeb) BrowserContextMenu.enableContextMenu();
    super.dispose();
  }

  List<Map<String, dynamic>> asMaps(dynamic value) => value is List
      ? value.whereType<Map>().map((e) => Map<String, dynamic>.from(e)).toList()
      : [];

  Future<void> refresh() async {
    if (!mounted) return;
    setState(() {
      loading = true;
      error = null;
    });
    try {
      final results = await Future.wait([
        api.get('/api/planning/teams'),
        api.get('/api/planning/tasks'),
        api.get('/api/planning/sites'),
        api.get('/api/planning/mission-issuers'),
      ]);
      if (!mounted) return;
      setState(() {
        teams = asMaps(results[0]);
        tasks = asMaps(results[1]);
        sites = asMaps(results[2]);
        missionIssuers = (results[3] as List)
            .map((name) => name.toString())
            .toList();
        if (teamFilter != null &&
            !teams.any((team) => team['id']?.toString() == teamFilter)) {
          teamFilter = null;
        }
        pageIndex = 0;
      });
    } catch (e) {
      if (mounted) setState(() => error = '$e');
    } finally {
      if (mounted) setState(() => loading = false);
    }
  }

  DateTime? parseDate(dynamic value) =>
      DateTime.tryParse(value?.toString() ?? '');
  String iso(DateTime value) =>
      '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')}';
  String displayDate(dynamic value) {
    final d = parseDate(value);
    return d == null
        ? '—'
        : '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
  }

  int difference(Map<String, dynamic> t) {
    final planned = parseDate(t['plannedDate']);
    if (planned == null) return 0;
    final actual = t['status'] == 'Réalisé'
        ? parseDate(t['completedDate'])
        : DateTime.now();
    if (actual == null) return 0;
    return DateUtils.dateOnly(
      actual,
    ).difference(DateUtils.dateOnly(planned)).inDays;
  }

  bool overdue(Map<String, dynamic> t) =>
      t['status'] != 'Réalisé' && t['status'] != 'Reporté' && difference(t) > 0;
  bool onTime(Map<String, dynamic> t) =>
      t['status'] == 'Réalisé' &&
      t['completedDate'] != null &&
      difference(t) <= 0;
  String? requiredText(String? value) =>
      value == null || value.trim().isEmpty ? 'Champ obligatoire' : null;
  String siteKey(Map<String, dynamic> site) =>
      '${site['brand']}:${site['siteCode']}';
  List<Map<String, dynamic>> taskSites(Map<String, dynamic> task) {
    final selected = asMaps(task['sites']);
    if (selected.isNotEmpty) return selected;
    // Existing interventions from V1 stored one site as free text.
    final legacy = task['site']?.toString().trim() ?? '';
    return legacy.isEmpty
        ? []
        : [
            {'siteName': legacy, 'siteCode': '', 'brand': ''},
          ];
  }

  String names(Map<String, dynamic> task) => taskSites(
    task,
  ).map((site) => site['siteName']?.toString() ?? '').join(', ');

  Color statusColor(String value) {
    switch (value) {
      case 'Réalisé':
        return const Color(0xFF157347);
      case 'Partiellement réalisé':
        return const Color(0xFF2568A0);
      case 'Bloqué':
      case 'Non réalisé':
        return const Color(0xFFC23335);
      case 'Reporté':
        return const Color(0xFF738092);
      case 'En cours':
        return const Color(0xFFB97017);
      default:
        return AppColors.deepTeal;
    }
  }

  void showError(BuildContext context, Object error) {
    if (context.mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('$error')));
    }
  }

  Future<void> teamDialog([Map<String, dynamic>? existing]) async {
    final form = GlobalKey<FormState>();
    final name = TextEditingController(text: existing?['name']?.toString());
    final manager = TextEditingController(
      text: existing?['manager']?.toString(),
    );
    final people = <Map<String, TextEditingController>>[
      for (final person in asMaps(existing?['personnel']))
        {
          'name': TextEditingController(text: person['name']?.toString()),
          'role': TextEditingController(text: person['role']?.toString()),
          'phone': TextEditingController(text: person['phone']?.toString()),
        },
    ];
    await _showEditorDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) => AlertDialog(
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(20),
          ),
          title: Text(
            existing == null ? 'Créer une équipe' : 'Modifier l’équipe',
          ),
          content: SizedBox(
            width: 650,
            child: Form(
              key: form,
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      'Identité de l’équipe',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: name,
                      decoration: const InputDecoration(
                        labelText: 'Nom de l’équipe *',
                        border: OutlineInputBorder(),
                      ),
                      validator: requiredText,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: manager,
                      decoration: const InputDecoration(
                        labelText: 'Responsable *',
                        border: OutlineInputBorder(),
                      ),
                      validator: requiredText,
                    ),
                    const SizedBox(height: 22),
                    const Text(
                      'Personnel',
                      style: TextStyle(fontWeight: FontWeight.bold),
                    ),
                    const SizedBox(height: 8),
                    for (final p in people)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 10),
                        child: Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          crossAxisAlignment: WrapCrossAlignment.center,
                          children: [
                            SizedBox(
                              width: 175,
                              child: TextFormField(
                                controller: p['name'],
                                validator: requiredText,
                                decoration: const InputDecoration(
                                  labelText: 'Nom *',
                                  border: OutlineInputBorder(),
                                ),
                              ),
                            ),
                            SizedBox(
                              width: 145,
                              child: TextFormField(
                                controller: p['role'],
                                decoration: const InputDecoration(
                                  labelText: 'Fonction',
                                  border: OutlineInputBorder(),
                                ),
                              ),
                            ),
                            SizedBox(
                              width: 145,
                              child: TextFormField(
                                controller: p['phone'],
                                decoration: const InputDecoration(
                                  labelText: 'Téléphone',
                                  border: OutlineInputBorder(),
                                ),
                              ),
                            ),
                            IconButton(
                              tooltip: 'Supprimer ce personnel',
                              onPressed: () async {
                                final confirmed = await confirmDeletion(
                                  ctx,
                                  'Supprimer ce personnel ?',
                                  'Enregistrez ensuite l’équipe pour confirmer la modification.',
                                );
                                if (confirmed && ctx.mounted)
                                  update(() => people.remove(p));
                              },
                              icon: const Icon(
                                Icons.delete_outline,
                                color: Colors.red,
                              ),
                            ),
                          ],
                        ),
                      ),
                    TextButton.icon(
                      onPressed: () => update(
                        () => people.add({
                          'name': TextEditingController(),
                          'role': TextEditingController(),
                          'phone': TextEditingController(),
                        }),
                      ),
                      icon: const Icon(Icons.person_add_alt_1_outlined),
                      label: const Text('Ajouter un personnel'),
                    ),
                  ],
                ),
              ),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: () async {
                if (!form.currentState!.validate()) return;
                try {
                  final body = {
                    'name': name.text.trim(),
                    'manager': manager.text.trim(),
                    'personnel': people
                        .map(
                          (p) => p.map(
                            (key, value) => MapEntry(key, value.text.trim()),
                          ),
                        )
                        .toList(),
                  };
                  if (existing == null) {
                    await api.post('/api/planning/teams', data: body);
                  } else {
                    await api.put(
                      '/api/planning/teams/${existing['id']}',
                      data: body,
                    );
                  }
                  if (ctx.mounted) Navigator.pop(ctx);
                  await refresh();
                } catch (e) {
                  showError(ctx, e);
                }
              },
              child: const Text('Enregistrer'),
            ),
          ],
        ),
      ),
    );
    name.dispose();
    manager.dispose();
    for (final p in people) {
      for (final value in p.values) {
        value.dispose();
      }
    }
  }

  Future<void> taskDialog([
    Map<String, dynamic>? existing,
    List<Map<String, dynamic>>? initialSites,
    String? followupOf,
  ]) async {
    if (teams.isEmpty) {
      showError(context, 'Créez d’abord une équipe.');
      return;
    }
    final form = GlobalKey<FormState>();
    final fields = <String, TextEditingController>{
      for (final key in ['project', 'zone', 'description', 'reason'])
        key: TextEditingController(text: existing?[key]?.toString()),
    };
    final selected = <String>{
      for (final site in initialSites ?? taskSites(existing ?? {}))
        if (site['brand'] != null && site['siteCode'] != null) siteKey(site),
    };
    String teamId =
        existing?['teamId']?.toString() ?? teams.first['id'].toString();
    if (!teams.any((team) => team['id']?.toString() == teamId))
      teamId = teams.first['id'].toString();
    const newIssuerOption = '__new_mission_issuer__';
    final issuerNames = <String>{...missionIssuers};
    final previousIssuer = existing?['missionIssuer']?.toString().trim();
    if (previousIssuer != null && previousIssuer.isNotEmpty)
      issuerNames.add(previousIssuer);
    final sortedIssuers = issuerNames.toList()
      ..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
    String? selectedIssuer = previousIssuer?.isNotEmpty == true
        ? previousIssuer
        : null;
    final newIssuer = TextEditingController();
    String status = existing?['status']?.toString() ?? statuses.first;
    String priority = existing?['priority']?.toString() ?? 'Normale';
    if (!priorities.contains(priority)) priority = 'Normale';
    String type = existing?['type']?.toString() ?? types.first;
    if (!types.contains(type)) type = types.first;
    DateTime planned = parseDate(existing?['plannedDate']) ?? DateTime.now();
    DateTime completed =
        parseDate(existing?['completedDate']) ?? DateTime.now();
    DateTime postponed =
        parseDate(existing?['postponedDate']) ?? DateTime.now();
    Widget dateField(
      String label,
      DateTime value,
      void Function(DateTime) onDate,
    ) => OutlinedButton.icon(
      icon: const Icon(Icons.calendar_month_outlined, size: 18),
      label: Text('$label : ${displayDate(iso(value))}'),
      onPressed: () async {
        final result = await showDatePicker(
          context: context,
          initialDate: value,
          firstDate: DateTime(2020),
          lastDate: DateTime(2100),
        );
        if (result != null) onDate(result);
      },
    );
    await _showEditorDialog(
      context: context,
      builder: (ctx) => StatefulBuilder(
        builder: (ctx, update) {
          Future<void> pickSites() async {
            final draft = <String>{...selected};
            final query = TextEditingController();
            bool creating = false;
            String? creationError;
            String nameKey(String value) =>
                value.trim().replaceAll(RegExp(r'\s+'), ' ').toLowerCase();
            await _showEditorDialog(
              context: ctx,
              barrierDismissible: false,
              builder: (picker) => StatefulBuilder(
                builder: (picker, updatePicker) {
                  final needle = nameKey(query.text);
                  final matches = sites
                      .where(
                        (site) =>
                            nameKey('${site['siteName']} ${site['siteCode']} ${site['brand']}')
                                .contains(needle),
                      )
                      .toList();
                  final newName = query.text.trim().replaceAll(RegExp(r'\s+'), ' ');
                  final canCreate = newName.isNotEmpty &&
                      newName.length <= 120 &&
                      !sites.any((site) => nameKey('${site['siteName'] ?? ''}') == nameKey(newName));
                  Future<void> addSite() async {
                    if (!canCreate || creating) return;
                    updatePicker(() { creating = true; creationError = null; });
                    try {
                      final response = await api.post(
                        '/api/planning/sites',
                        data: {'siteName': newName},
                      );
                      final added = Map<String, dynamic>.from(response as Map);
                      if (!mounted || !picker.mounted) return;
                      setState(() {
                        if (!sites.any((site) => siteKey(site) == siteKey(added))) {
                          sites = [...sites, added];
                        }
                      });
                      updatePicker(() { draft.add(siteKey(added)); });
                    } catch (e) {
                      if (picker.mounted) updatePicker(() { creationError = '$e'; });
                    } finally {
                      if (picker.mounted) updatePicker(() { creating = false; });
                    }
                  }
                  return PopScope(
                    canPop: !creating,
                    child: AlertDialog(
                      title: const Text('Sélectionner des sites'),
                      content: SizedBox(
                        width: 620,
                        height: 500,
                        child: Column(
                          children: [
                            TextField(
                              controller: query,
                              autofocus: true,
                              enabled: !creating,
                              maxLength: 120,
                              decoration: const InputDecoration(
                                prefixIcon: Icon(Icons.search),
                                hintText: 'Rechercher par nom, code ou marque',
                                border: OutlineInputBorder(),
                              ),
                              onChanged: (_) => updatePicker(() { creationError = null; }),
                            ),
                            if (canCreate || creating)
                              Align(
                                alignment: Alignment.centerLeft,
                                child: TextButton.icon(
                                  onPressed: creating ? null : addSite,
                                  icon: creating
                                      ? const SizedBox(width: 18, height: 18,
                                          child: CircularProgressIndicator(strokeWidth: 2))
                                      : const Icon(Icons.add_location_alt_outlined),
                                  label: Text(creating
                                      ? 'Ajout en cours…'
                                      : 'Ajouter « $newName » au planning'),
                                ),
                              ),
                            if (canCreate)
                              const Align(
                                alignment: Alignment.centerLeft,
                                child: Text('Ce site sera disponible uniquement dans le Planning terrain.',
                                  style: TextStyle(fontSize: 12, color: Colors.blueGrey)),
                              ),
                            if (creationError != null)
                              Text(creationError!, style: const TextStyle(color: Colors.red)),
                            const SizedBox(height: 8),
                            Align(
                              alignment: Alignment.centerLeft,
                              child: Text(
                                '${draft.length} sélectionné(s) · ${matches.length} résultat(s)',
                              ),
                            ),
                            const SizedBox(height: 8),
                            Expanded(
                              child: ListView.builder(
                                itemCount: matches.length,
                                itemBuilder: (context, index) {
                                  final site = matches[index];
                                  final key = siteKey(site);
                                  return CheckboxListTile(
                                    dense: true,
                                    title: Text(
                                      site['siteName']?.toString() ?? 'Site',
                                    ),
                                    secondary: site['brand'] == 'MANUAL' ? IconButton(
                                      tooltip: 'Supprimer ce site manuel',
                                      icon: const Icon(Icons.delete_outline),
                                      onPressed: creating ? null : () async {
                                        if (await deleteManualSite(site) && picker.mounted) {
                                          if (ctx.mounted) update(() => selected.remove(key));
                                          updatePicker(() => draft.remove(key));
                                        }
                                      },
                                    ) : null,
                                    subtitle: Text(
                                      site['brand'] == 'MANUAL'
                                          ? 'Site manuel · Planning uniquement'
                                          : '${site['brand']} · ${site['siteCode']}',
                                    ),
                                    value: draft.contains(key),
                                    onChanged: creating ? null : (checked) => updatePicker(() {
                                      if (checked == true) {
                                        draft.add(key);
                                      } else {
                                        draft.remove(key);
                                      }
                                    }),
                                  );
                                },
                              ),
                            ),
                          ],
                        ),
                      ),
                      actions: [
                        TextButton(
                          onPressed: creating ? null : () => Navigator.pop(picker),
                          child: const Text('Annuler'),
                        ),
                        FilledButton(
                          onPressed: creating ? null : () {
                            update(() {
                              selected
                                ..clear()
                                ..addAll(draft);
                            });
                            Navigator.pop(picker);
                          },
                          child: const Text('Confirmer'),
                        ),
                      ],
                    ),
                  );
                },
              ),
            );
            query.dispose();
          }

          Widget section(String title, String subtitle) => Padding(
            padding: const EdgeInsets.only(top: 16, bottom: 10),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                Text(
                  subtitle,
                  style: const TextStyle(fontSize: 12, color: Colors.black54),
                ),
              ],
            ),
          );
          return AlertDialog(
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(20),
            ),
            title: Text(
              existing == null
                  ? 'Programmer une intervention'
                  : 'Modifier l’intervention',
            ),
            content: SizedBox(
              width: 680,
              height: 620,
              child: Form(
                key: form,
                child: SingleChildScrollView(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      section(
                        '1 · Affectation',
                        'Choisissez les sites et l’équipe responsable.',
                      ),
                      OutlinedButton.icon(
                        onPressed: pickSites,
                        icon: const Icon(Icons.place_outlined),
                        label: Text(
                          selected.isEmpty
                              ? 'Choisir les sites *'
                              : '${selected.length} site(s) sélectionné(s)',
                        ),
                      ),
                      if (selected.isNotEmpty)
                        Padding(
                          padding: const EdgeInsets.only(top: 6),
                          child: Text(
                            <String, Map<String, dynamic>>{
                              for (final site in taskSites(existing ?? {})) siteKey(site): site,
                              for (final site in sites) siteKey(site): site,
                            }.values
                                .where((s) => selected.contains(siteKey(s)))
                                .map((s) => s['siteName'])
                                .join(', '),
                            maxLines: 3,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 10,
                        runSpacing: 10,
                        children: [
                          SizedBox(
                            width: 315,
                            child: DropdownButtonFormField<String>(
                              value: teamId,
                              isExpanded: true,
                              decoration: const InputDecoration(
                                labelText: 'Équipe *',
                                border: OutlineInputBorder(),
                              ),
                              items: teams
                                  .map(
                                    (team) => DropdownMenuItem<String>(
                                      value: team['id'].toString(),
                                      child: Text(
                                        '${team['name']} · ${team['manager']}',
                                        overflow: TextOverflow.ellipsis,
                                      ),
                                    ),
                                  )
                                  .toList(),
                              onChanged: (value) =>
                                  update(() => teamId = value!),
                            ),
                          ),
                          SizedBox(
                            width: 315,
                            child: DropdownButtonFormField<String>(
                              value: selectedIssuer,
                              isExpanded: true,
                              decoration: const InputDecoration(
                                labelText: 'Ordre de mission donné par *',
                                border: OutlineInputBorder(),
                              ),
                              items: [
                                ...sortedIssuers.map(
                                  (name) => DropdownMenuItem<String>(
                                    value: name,
                                    child: Text(
                                      name,
                                      overflow: TextOverflow.ellipsis,
                                    ),
                                  ),
                                ),
                                const DropdownMenuItem<String>(
                                  value: newIssuerOption,
                                  child: Text('＋ Nouveau nom'),
                                ),
                              ],
                              onChanged: (value) =>
                                  update(() => selectedIssuer = value),
                            ),
                          ),
                        ],
                      ),
                      if (selectedIssuer == newIssuerOption)
                        Padding(
                          padding: const EdgeInsets.only(top: 10),
                          child: TextFormField(
                            controller: newIssuer,
                            autofocus: true,
                            maxLength: 120,
                            validator: requiredText,
                            decoration: const InputDecoration(
                              labelText: 'Nouveau nom *',
                              border: OutlineInputBorder(),
                            ),
                          ),
                        ),
                      section(
                        '2 · Intervention',
                        'Décrivez le programme prévu pour les sites choisis.',
                      ),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          dateField(
                            'Date programmée',
                            planned,
                            (d) => update(() => planned = d),
                          ),
                          if (status == 'Réalisé')
                            dateField(
                              'Date réalisée',
                              completed,
                              (d) => update(() => completed = d),
                            ),
                          if (status == 'Reporté')
                            dateField(
                              'Nouvelle date',
                              postponed,
                              (d) => update(() => postponed = d),
                            ),
                        ],
                      ),
                      const SizedBox(height: 10),
                      DropdownButtonFormField<String>(
                        value: type,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Type d’intervention *',
                          border: OutlineInputBorder(),
                        ),
                        items: types
                            .map(
                              (value) => DropdownMenuItem(
                                value: value,
                                child: Text(value),
                              ),
                            )
                            .toList(),
                        onChanged: (value) => update(() => type = value!),
                      ),
                      const SizedBox(height: 10),
                      TextFormField(
                        controller: fields['description'],
                        minLines: 2,
                        maxLines: 3,
                        validator: requiredText,
                        decoration: const InputDecoration(
                          labelText: 'Description de la tâche *',
                          border: OutlineInputBorder(),
                        ),
                      ),
                      const SizedBox(height: 10),
                      Wrap(
                        spacing: 10,
                        runSpacing: 10,
                        children: [
                          SizedBox(
                            width: 275,
                            child: TextFormField(
                              controller: fields['project'],
                              decoration: const InputDecoration(
                                labelText: 'Projet / Client',
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          SizedBox(
                            width: 275,
                            child: TextFormField(
                              controller: fields['zone'],
                              decoration: const InputDecoration(
                                labelText: 'Zone / Gouvernorat',
                                border: OutlineInputBorder(),
                              ),
                            ),
                          ),
                          SizedBox(
                            width: 275,
                            child: DropdownButtonFormField<String>(
                              value: priority,
                              isExpanded: true,
                              decoration: const InputDecoration(
                                labelText: 'Priorité',
                                border: OutlineInputBorder(),
                              ),
                              items: priorities
                                  .map(
                                    (value) => DropdownMenuItem(
                                      value: value,
                                      child: Text(value),
                                    ),
                                  )
                                  .toList(),
                              onChanged: (value) =>
                                  update(() => priority = value!),
                            ),
                          ),
                        ],
                      ),
                      section(
                        '3 · Suivi',
                        'Le statut est choisi manuellement après votre échange avec le terrain.',
                      ),
                      DropdownButtonFormField<String>(
                        value: status,
                        isExpanded: true,
                        decoration: const InputDecoration(
                          labelText: 'Statut *',
                          border: OutlineInputBorder(),
                        ),
                        items: statuses
                            .map(
                              (value) => DropdownMenuItem(
                                value: value,
                                child: Text(value),
                              ),
                            )
                            .toList(),
                        onChanged: (value) => update(() => status = value!),
                      ),
                      const SizedBox(height: 10),
                      TextFormField(
                        controller: fields['reason'],
                        minLines: 2,
                        maxLines: 3,
                        decoration: const InputDecoration(
                          labelText: 'Motif libre',
                          border: OutlineInputBorder(),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('Annuler'),
              ),
              FilledButton.icon(
                icon: const Icon(Icons.save_outlined),
                label: const Text('Enregistrer'),
                onPressed: () async {
                  if (!form.currentState!.validate()) return;
                  if (selected.isEmpty) {
                    showError(ctx, 'Sélectionnez au moins un site.');
                    return;
                  }
                  final issuer =
                      (selectedIssuer == newIssuerOption
                              ? newIssuer.text
                              : selectedIssuer ?? '')
                          .trim()
                          .replaceAll(RegExp(r'\s+'), ' ');
                  if (issuer.isEmpty) {
                    showError(
                      ctx,
                      'Sélectionnez ou saisissez le donneur d’ordre de mission.',
                    );
                    return;
                  }
                  try {
                    final choices = <String, Map<String, dynamic>>{
                      for (final site in taskSites(existing ?? {})) siteKey(site): site,
                      for (final site in sites) siteKey(site): site,
                    };
                    final selectedSites = choices.values
                        .where((site) => selected.contains(siteKey(site)))
                        .map(
                          (site) => {
                            'brand': site['brand'],
                            'siteCode': site['siteCode'],
                            'siteName': site['siteName'],
                          },
                        )
                        .toList();
                    if (selectedSites.length != selected.length) {
                      showError(
                        ctx,
                        'Un site sélectionné est indisponible. Actualisez la page.',
                      );
                      return;
                    }
                    final payload = {
                      for (final field in fields.entries)
                        field.key: field.value.text.trim(),
                      'sites': selectedSites,
                      'teamId': teamId,
                      'missionIssuer': issuer,
                      'type': type,
                      'priority': priority,
                      'status': status,
                      'plannedDate': iso(planned),
                      'completedDate': status == 'Réalisé'
                          ? iso(completed)
                          : null,
                      'postponedDate': status == 'Reporté'
                          ? iso(postponed)
                          : null,
                      'version': existing?['version'],
                      'followupOfTaskId':
                          followupOf ?? existing?['followupOfTaskId'],
                    };
                    if (existing == null) {
                      await api.post('/api/planning/tasks', data: payload);
                    } else {
                      await api.put(
                        '/api/planning/tasks/${existing['id']}',
                        data: payload,
                      );
                    }
                    if (ctx.mounted) Navigator.pop(ctx);
                    await refresh();
                  } catch (e) {
                    showError(ctx, e);
                  }
                },
              ),
            ],
          );
        },
      ),
    );
    for (final field in fields.values) {
      field.dispose();
    }
    newIssuer.dispose();
  }

  Future<bool> confirmDeletion(
    BuildContext context,
    String title,
    String description,
  ) async =>
      await showDialog<bool>(
        context: context,
        builder: (dialog) => AlertDialog(
          title: Text(title),
          content: Text(description),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(dialog, false),
              child: const Text('Annuler'),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(dialog, true),
              child: const Text('Supprimer'),
            ),
          ],
        ),
      ) ??
      false;

  bool exportingTasks = false;
  Future<void> exportTasks(List<Map<String, dynamic>> visible) async {
    if (exportingTasks || visible.isEmpty) return;
    setState(() => exportingTasks = true);
    try {
      final now = DateTime.now();
      final bytes = PlanningExcelExport.build(
        tasks: visible.map((t) => Map<String, dynamic>.from(t)).toList(),
        filters: {'Recherche': search.text.trim(), 'Statut': statusFilter ?? 'Tous',
          'Équipe': teamFilter == null ? 'Toutes' : teams.where((t) => '${t['id']}' == teamFilter).map((t) => '${t['name']}').join(', '),
          'Priorité': priorityFilter ?? 'Toutes'}, exportedAt: now,
      );
      await downloadBytes(bytes: bytes, filename: 'vora_planning_${iso(now)}_${now.millisecondsSinceEpoch}.xlsx',
        mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
    } catch (e) {
      if (mounted) showError(context, 'Export impossible : $e');
    } finally { if (mounted) setState(() => exportingTasks = false); }
  }

  Future<bool> deleteManualSite(Map<String, dynamic> site) async {
    if (site['brand'] != 'MANUAL') return false;
    final confirmed = await showDialog<bool>(context: context, builder: (ctx) => AlertDialog(
      title: const Text('Supprimer le site manuel ?'),
      content: Text('Retirer « ${site['siteName']} » de la liste ? Les tâches déjà enregistrées seront conservées.'),
      actions: [TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Annuler')),
        FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Supprimer'))],
    ));
    if (confirmed != true || !mounted) return false;
    try {
      await api.delete('/api/planning/sites/${Uri.encodeComponent('${site['siteCode']}')}');
      if (!mounted) return false;
      setState(() => sites = sites.where((s) => siteKey(s) != siteKey(site)).toList());
      return true;
    } catch (e) { if (mounted) showError(context, '$e'); return false; }
  }

  Future<void> deleteTask(Map<String, dynamic> task) async {
    if (!await confirmDeletion(
      context,
      'Supprimer cette intervention ?',
      'Cette suppression effacera la tâche et son historique.',
    ))
      return;
    try {
      await api.delete(
        '/api/planning/tasks/${task['id']}',
        queryParameters: {'version': task['version']},
      );
      await refresh();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  Future<void> deleteTeam(Map<String, dynamic> team) async {
    final linked = tasks.where((task) => task['teamId'] == team['id']).length;
    if (linked > 0) {
      showError(
        context,
        'Équipe associée à $linked tâche(s). Supprimez ces tâches avant de supprimer l’équipe.',
      );
      return;
    }
    if (!await confirmDeletion(
      context,
      'Supprimer l’équipe ${team['name']} ?',
      'Son responsable et ses personnels seront également supprimés.',
    ))
      return;
    try {
      await api.delete('/api/planning/teams/${team['id']}');
      await refresh();
    } catch (e) {
      if (mounted) showError(context, e);
    }
  }

  void teamDetails(Map<String, dynamic> team) {
    final linked = tasks.where((task) => task['teamId'] == team['id']).toList();
    final done = linked.where((task) => task['status'] == 'Réalisé').length;
    final blocked = linked.where((task) => task['status'] == 'Bloqué').length;
    final counts = {
      for (final status in statuses)
        status: linked.where((task) => task['status'] == status).length,
    };
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Équipe ${team['name']}'),
        content: SizedBox(
          width: 700,
          height: 490,
          child: ListView(
            children: [
              Text(
                'Responsable : ${team['manager']}',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 12),
              Text(
                'Personnel (${asMaps(team['personnel']).length})',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              for (final person in asMaps(team['personnel']))
                ListTile(
                  dense: true,
                  leading: const Icon(Icons.person_outline),
                  title: Text(person['name']?.toString() ?? '—'),
                  subtitle: Text(
                    '${person['role'] ?? ''} · ${person['phone'] ?? ''}',
                  ),
                ),
              const Divider(),
              Text(
                'Interventions : ${linked.length} · Réalisation : ${linked.isEmpty ? 0 : (100 * done / linked.length).round()} % · Blocage : ${linked.isEmpty ? 0 : (100 * blocked / linked.length).round()} %',
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  for (final status in statuses)
                    Chip(label: Text('$status : ${counts[status]}')),
                  Chip(
                    label: Text('En retard : ${linked.where(overdue).length}'),
                  ),
                ],
              ),
              const Divider(),
              const Text(
                'Tâches associées',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              if (linked.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(12),
                  child: Text('Aucune tâche associée.'),
                ),
              for (final task in linked)
                ListTile(
                  title: Text(names(task)),
                  subtitle: Text(
                    '${task['description'] ?? ''} · ${displayDate(task['plannedDate'])} · ${task['status']}${overdue(task) ? ' · En retard' : ''}',
                  ),
                ),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('Fermer'),
          ),
        ],
      ),
    );
  }

  void history(Map<String, dynamic> task) => showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('Historique des modifications'),
      content: SizedBox(
        width: 600,
        height: 390,
        child: ListView(
          children: [
            for (final event in asMaps(task['history']).reversed)
              ListTile(
                leading: const Icon(Icons.history),
                title: Text(
                  '${event['oldStatus'] ?? 'Création'} → ${event['newStatus']}',
                ),
                subtitle: Text(
                  '${event['at']} · ${event['actor']}\nOrdre de mission donné par : ${event['newMissionIssuer'] ?? '—'}${event['oldMissionIssuer'] != null && event['oldMissionIssuer'] != event['newMissionIssuer'] ? ' (avant : ${event['oldMissionIssuer']})' : ''}\nMotif : ${event['reason'] ?? '—'}',
                ),
              ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Fermer'),
        ),
      ],
    ),
  );

  void metricDetails(
    String title,
    List<Map<String, dynamic>> matching,
  ) => showDialog<void>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text('$title · ${matching.length} intervention(s)'),
      content: SizedBox(
        width: 740,
        height: 460,
        child: matching.isEmpty
            ? const Center(
                child: Text('Aucune intervention dans cet indicateur.'),
              )
            : ListView.separated(
                itemCount: matching.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (_, index) {
                  final task = matching[index];
                  return ListTile(
                    leading: CircleAvatar(
                      backgroundColor: statusColor(
                        task['status']?.toString() ?? '',
                      ).withOpacity(.12),
                      child: Icon(
                        Icons.place_outlined,
                        color: statusColor(task['status']?.toString() ?? ''),
                      ),
                    ),
                    title: Text(
                      names(task).isEmpty ? 'Site non renseigné' : names(task),
                    ),
                    subtitle: Text(
                      '${task['description'] ?? ''}\n${task['teamName'] ?? ''} · Ordre donné par : ${task['missionIssuer'] ?? '—'} · ${displayDate(task['plannedDate'])} · ${task['status']}${overdue(task) ? ' · ${difference(task)} j de retard' : ''}\nMotif : ${task['reason']?.toString().isNotEmpty == true ? task['reason'] : '—'}',
                    ),
                    isThreeLine: true,
                  );
                },
              ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(ctx),
          child: const Text('Fermer'),
        ),
      ],
    ),
  );

  Widget metric(
    String label,
    String value,
    IconData icon,
    Color color,
    List<Map<String, dynamic>> matching,
  ) => SizedBox(
    width: 200,
    child: Card(
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(16),
        side: const BorderSide(color: Color(0xFFE2E8EF)),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => metricDetails(label, matching),
        child: Padding(
          padding: const EdgeInsets.all(18),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(icon, color: color),
              const SizedBox(height: 14),
              Text(
                value,
                style: TextStyle(
                  fontSize: 28,
                  color: color,
                  fontWeight: FontWeight.w800,
                ),
              ),
              const SizedBox(height: 4),
              Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
              const SizedBox(height: 6),
              const Text(
                'Voir les sites →',
                style: TextStyle(fontSize: 11, color: Colors.black54),
              ),
            ],
          ),
        ),
      ),
    ),
  );

  DataCell cell(String text, {double? width}) => DataCell(
    SizedBox(
      width: width,
      child: Text(
        text.isEmpty ? '—' : text,
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
    ),
  );
  Widget sectionTitle(String title, String subtitle) => Padding(
    padding: const EdgeInsets.only(top: 26, bottom: 12),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          title,
          style: const TextStyle(fontSize: 19, fontWeight: FontWeight.bold),
        ),
        Text(subtitle, style: const TextStyle(color: Colors.black54)),
      ],
    ),
  );

  static const Color _navy = Color(0xFF10233A);
  static const Color _teal = Color(0xFF087E80);
  static const Color _muted = Color(0xFF617387);
  static const Color _canvas = Color(0xFFF3F6FA);

  String? teamFilter;
  String? priorityFilter;
  int view = 0;
  int pageIndex = 0;
  static const int pageSize = 20;

  Widget _surface({required Widget child, EdgeInsets? padding}) => Container(
    decoration: BoxDecoration(
      color: Colors.white,
      borderRadius: BorderRadius.circular(18),
      border: Border.all(color: const Color(0xFFE2EAF1)),
      boxShadow: const [
        BoxShadow(
          color: Color(0x0A10233A),
          blurRadius: 18,
          offset: Offset(0, 7),
        ),
      ],
    ),
    padding: padding ?? const EdgeInsets.all(18),
    child: child,
  );

  Widget _heading(String title, String subtitle, {Widget? trailing}) => Padding(
    padding: const EdgeInsets.only(bottom: 14),
    child: Wrap(
      alignment: WrapAlignment.spaceBetween,
      crossAxisAlignment: WrapCrossAlignment.center,
      spacing: 12,
      runSpacing: 8,
      children: [
        Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              title,
              style: const TextStyle(
                fontSize: 19,
                color: _navy,
                fontWeight: FontWeight.w800,
              ),
            ),
            const SizedBox(height: 3),
            Text(subtitle, style: const TextStyle(color: _muted, fontSize: 12)),
          ],
        ),
        if (trailing != null) trailing,
      ],
    ),
  );

  Widget _statusBadge(Map<String, dynamic> task) {
    final label = task['status']?.toString() ?? 'Planifié';
    final isLate = overdue(task);
    final color = isLate ? const Color(0xFFC7363D) : statusColor(label);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 7,
          height: 7,
          decoration: BoxDecoration(color: color, shape: BoxShape.circle),
        ),
        const SizedBox(width: 6),
        Flexible(
          child: Text(
            isLate ? '$label · Retard' : label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(
              color: color,
              fontWeight: FontWeight.w700,
              fontSize: 12,
            ),
          ),
        ),
      ],
    );
  }

  Widget _kpiCard(
    String label,
    String value,
    String detail,
    IconData icon,
    Color color,
    List<Map<String, dynamic>> matching,
  ) => SizedBox(
    width: 188,
    child: _surface(
      padding: EdgeInsets.zero,
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: () => metricDetails(label, matching),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Container(
                    padding: const EdgeInsets.all(9),
                    decoration: BoxDecoration(
                      color: color.withOpacity(.09),
                      borderRadius: BorderRadius.circular(11),
                    ),
                    child: Icon(icon, color: color, size: 20),
                  ),
                  const Icon(
                    Icons.north_east,
                    size: 16,
                    color: Color(0xFF9AA9B6),
                  ),
                ],
              ),
              const SizedBox(height: 13),
              Text(
                value,
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                  color: _navy,
                ),
              ),
              const SizedBox(height: 3),
              Text(
                label,
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: _navy,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                detail,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, color: _muted),
              ),
            ],
          ),
        ),
      ),
    ),
  );

  Widget _tab(String text, int index, IconData icon) => Padding(
    padding: const EdgeInsets.only(right: 5),
    child: ChoiceChip(
      avatar: Icon(icon, size: 17, color: view == index ? Colors.white : _teal),
      label: Text(text),
      selected: view == index,
      onSelected: (_) => setState(() => view = index),
      selectedColor: _teal,
      labelStyle: TextStyle(
        color: view == index ? Colors.white : _navy,
        fontWeight: FontWeight.w700,
      ),
      backgroundColor: Colors.white,
      side: BorderSide(color: view == index ? _teal : const Color(0xFFDDE6ED)),
      showCheckmark: false,
    ),
  );

  Widget _filters() => Wrap(
    spacing: 10,
    runSpacing: 10,
    crossAxisAlignment: WrapCrossAlignment.center,
    children: [
      SizedBox(
        width: 285,
        child: TextField(
          controller: search,
          onChanged: (_) => setState(() => pageIndex = 0),
          decoration: const InputDecoration(
            prefixIcon: Icon(Icons.search),
            hintText: 'Site, équipe, projet, tâche…',
            isDense: true,
            border: OutlineInputBorder(),
          ),
        ),
      ),
      SizedBox(
        width: 155,
        child: DropdownButtonFormField<String>(
          value: statusFilter,
          isExpanded: true,
          decoration: const InputDecoration(
            labelText: 'Statut',
            isDense: true,
            border: OutlineInputBorder(),
          ),
          items: [
            const DropdownMenuItem<String>(
              value: null,
              child: Text('Tous les statuts'),
            ),
            ...statuses.map((s) => DropdownMenuItem(value: s, child: Text(s))),
          ],
          onChanged: (value) => setState(() {
            statusFilter = value;
            pageIndex = 0;
          }),
        ),
      ),
      SizedBox(
        width: 170,
        child: DropdownButtonFormField<String>(
          value: teamFilter,
          isExpanded: true,
          decoration: const InputDecoration(
            labelText: 'Équipe',
            isDense: true,
            border: OutlineInputBorder(),
          ),
          items: [
            const DropdownMenuItem<String>(
              value: null,
              child: Text('Toutes les équipes'),
            ),
            ...teams.map(
              (t) => DropdownMenuItem(
                value: t['id'].toString(),
                child: Text(t['name']?.toString() ?? ''),
              ),
            ),
          ],
          onChanged: (value) => setState(() {
            teamFilter = value;
            pageIndex = 0;
          }),
        ),
      ),
      SizedBox(
        width: 155,
        child: DropdownButtonFormField<String>(
          value: priorityFilter,
          isExpanded: true,
          decoration: const InputDecoration(
            labelText: 'Priorité',
            isDense: true,
            border: OutlineInputBorder(),
          ),
          items: [
            const DropdownMenuItem<String>(value: null, child: Text('Toutes')),
            ...priorities.map(
              (p) => DropdownMenuItem(value: p, child: Text(p)),
            ),
          ],
          onChanged: (value) => setState(() {
            priorityFilter = value;
            pageIndex = 0;
          }),
        ),
      ),
      IconButton(
        onPressed: refresh,
        tooltip: 'Actualiser',
        icon: const Icon(Icons.refresh, color: _teal),
      ),
    ],
  );

  Widget _rowActions(Map<String, dynamic> task) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      IconButton(
        tooltip: 'Historique',
        onPressed: () => history(task),
        icon: const Icon(Icons.history, size: 19),
      ),
      IconButton(
        tooltip: 'Modifier',
        onPressed: () => taskDialog(task),
        icon: const Icon(Icons.edit_outlined, size: 19),
      ),
      IconButton(
        tooltip: 'Supprimer',
        onPressed: () => deleteTask(task),
        icon: const Icon(
          Icons.delete_outline,
          size: 19,
          color: Color(0xFFC7363D),
        ),
      ),
    ],
  );

  Widget _table(List<Map<String, dynamic>> rows) {
    if (rows.isEmpty)
      return _empty(
        Icons.search_off_outlined,
        tasks.isEmpty ? 'Aucune intervention programmée' : 'Aucun résultat',
        tasks.isEmpty
            ? 'Créez une équipe, puis programmez votre première tâche.'
            : 'Modifiez les filtres ou le texte recherché.',
      );
    final table = DataTable(
      headingRowColor: MaterialStateProperty.all(const Color(0xFFEAF1F5)),
      headingTextStyle: const TextStyle(
        color: _navy,
        fontWeight: FontWeight.w800,
        fontSize: 12,
      ),
      dataTextStyle: const TextStyle(color: _navy, fontSize: 12),
      dividerThickness: .5,
      columnSpacing: 19,
      horizontalMargin: 16,
      dataRowMinHeight: 62,
      dataRowMaxHeight: 82,
      columns: const [
        DataColumn(label: Text('Actions')),
        DataColumn(label: Text('Date prévue')),
        DataColumn(label: Text('Sites')),
        DataColumn(label: Text('Équipe')),
        DataColumn(label: Text('Ordre de mission donné par')),
        DataColumn(label: Text('Responsable')),
        DataColumn(label: Text('Projet / Client')),
        DataColumn(label: Text('Zone')),
        DataColumn(label: Text('Type d’intervention')),
        DataColumn(label: Text('Tâche')),
        DataColumn(label: Text('Priorité')),
        DataColumn(label: Text('Statut')),
        DataColumn(label: Text('Date réalisée')),
        DataColumn(label: Text('Date reportée')),
        DataColumn(label: Text('Écart / retard')),
        DataColumn(label: Text('Motif')),
      ],
      rows: [
        for (final task in rows)
          DataRow(
            cells: [
              DataCell(_rowActions(task)),
              cell(displayDate(task['plannedDate'])),
              cell(names(task), width: 220),
              cell(task['teamName']?.toString() ?? ''),
              cell(task['missionIssuer']?.toString() ?? '—', width: 180),
              cell(task['manager']?.toString() ?? ''),
              cell(task['project']?.toString() ?? ''),
              cell(task['zone']?.toString() ?? ''),
              cell(task['type']?.toString() ?? ''),
              cell(task['description']?.toString() ?? '', width: 230),
              cell(task['priority']?.toString() ?? ''),
              DataCell(_statusBadge(task)),
              cell(displayDate(task['completedDate'])),
              cell(displayDate(task['postponedDate'])),
              cell(
                task['status'] == 'Réalisé' || overdue(task)
                    ? '${difference(task)} j'
                    : '—',
              ),
              cell(task['reason']?.toString() ?? '', width: 190),
            ],
          ),
      ],
    );
    return MouseRegion(
      cursor: SystemMouseCursors.grab,
      onEnter: (_) {
        if (kIsWeb) BrowserContextMenu.disableContextMenu();
      },
      onExit: (_) {
        if (kIsWeb) BrowserContextMenu.enableContextMenu();
      },
      child: Listener(
        onPointerDown: (event) {
          if ((event.buttons & kSecondaryMouseButton) != 0) {
            if (kIsWeb) BrowserContextMenu.disableContextMenu();
          }
        },
        onPointerMove: (event) {
          if ((event.buttons & kSecondaryMouseButton) != 0 &&
              horizontalScroll.hasClients) {
            final position = horizontalScroll.position;
            horizontalScroll.jumpTo(
              (horizontalScroll.offset - event.delta.dx)
                  .clamp(position.minScrollExtent, position.maxScrollExtent)
                  .toDouble(),
            );
          }
        },
        child: Scrollbar(
          controller: horizontalScroll,
          thumbVisibility: true,
          child: SingleChildScrollView(
            controller: horizontalScroll,
            scrollDirection: Axis.horizontal,
            child: table,
          ),
        ),
      ),
    );
  }

  Widget _empty(IconData icon, String title, String subtitle) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 38, horizontal: 18),
    child: Column(
      children: [
        Icon(icon, size: 36, color: const Color(0xFF9AABBB)),
        const SizedBox(height: 12),
        Text(
          title,
          style: const TextStyle(fontWeight: FontWeight.w800, color: _navy),
        ),
        const SizedBox(height: 4),
        Text(
          subtitle,
          textAlign: TextAlign.center,
          style: const TextStyle(color: _muted, fontSize: 12),
        ),
      ],
    ),
  );

  Widget _teamCard(Map<String, dynamic> team) {
    final linked = tasks.where((task) => task['teamId'] == team['id']).toList();
    final completed = linked
        .where((task) => task['status'] == 'Réalisé')
        .length;
    final blocked = linked.where((task) => task['status'] == 'Bloqué').length;
    final percent = linked.isEmpty ? 0.0 : completed / linked.length;
    return SizedBox(
      width: 330,
      child: _surface(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  padding: const EdgeInsets.all(10),
                  decoration: BoxDecoration(
                    color: const Color(0xFFE8F5F3),
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: const Icon(Icons.groups_outlined, color: _teal),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        team['name']?.toString() ?? 'Équipe',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          color: _navy,
                          fontWeight: FontWeight.w800,
                          fontSize: 16,
                        ),
                      ),
                      Text(
                        team['manager']?.toString() ?? '—',
                        style: const TextStyle(color: _muted, fontSize: 12),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              '${asMaps(team['personnel']).length} personnel(s) · ${linked.length} tâche(s)',
              style: const TextStyle(color: _muted, fontSize: 12),
            ),
            const SizedBox(height: 9),
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: LinearProgressIndicator(
                value: percent,
                minHeight: 7,
                backgroundColor: const Color(0xFFE9EFF4),
                color: _teal,
              ),
            ),
            const SizedBox(height: 8),
            Text(
              '${(percent * 100).round()} % réalisées  ·  $blocked bloquée(s)',
              style: const TextStyle(
                color: _navy,
                fontWeight: FontWeight.w700,
                fontSize: 12,
              ),
            ),
            const Divider(height: 24),
            Row(
              children: [
                TextButton.icon(
                  onPressed: () => teamDetails(team),
                  icon: const Icon(Icons.open_in_new, size: 16),
                  label: const Text('Détails'),
                ),
                const Spacer(),
                IconButton(
                  onPressed: () => teamDialog(team),
                  tooltip: 'Modifier',
                  icon: const Icon(Icons.edit_outlined, size: 19),
                ),
                IconButton(
                  onPressed: () => deleteTeam(team),
                  tooltip: 'Supprimer',
                  icon: const Icon(
                    Icons.delete_outline,
                    size: 19,
                    color: Color(0xFFC7363D),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final width = MediaQuery.sizeOf(context).width;
    final compact = width < 850;
    final q = search.text.trim().toLowerCase();
    final visible = tasks
        .where(
          (task) =>
              (statusFilter == null || task['status'] == statusFilter) &&
              (teamFilter == null ||
                  task['teamId']?.toString() == teamFilter) &&
              (priorityFilter == null || task['priority'] == priorityFilter) &&
              (q.isEmpty ||
                  '${names(task)} ${task['teamName']} ${task['missionIssuer']} ${task['description']} ${task['project']}'
                      .toLowerCase()
                      .contains(q)),
        )
        .toList();
    final done = tasks.where((task) => task['status'] == 'Réalisé').toList();
    final active = tasks.where((task) => task['status'] == 'En cours').toList();
    final partial = tasks
        .where((task) => task['status'] == 'Partiellement réalisé')
        .toList();
    final delayed = tasks.where(overdue).toList();
    final blocked = tasks.where((task) => task['status'] == 'Bloqué').toList();
    final punctual = done.where(onTime).toList();
    final issues = tasks
        .where(
          (task) =>
              overdue(task) ||
              task['status'] == 'Partiellement réalisé' ||
              task['status'] == 'Bloqué' ||
              task['status'] == 'Non réalisé',
        )
        .toList();
    final totalPages = visible.isEmpty
        ? 1
        : (visible.length + pageSize - 1) ~/ pageSize;
    final safePage = pageIndex.clamp(0, totalPages - 1).toInt();
    final shown = visible.skip(safePage * pageSize).take(pageSize).toList();
    final realization = tasks.isEmpty ? 0.0 : done.length / tasks.length;

    return Scaffold(
      backgroundColor: _canvas,
      body: RefreshIndicator(
        onRefresh: refresh,
        child: ListView(
          padding: EdgeInsets.fromLTRB(
            compact ? 14 : 24,
            20,
            compact ? 14 : 24,
            46,
          ),
          children: [
            Container(
              padding: EdgeInsets.all(compact ? 20 : 26),
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(21),
                gradient: const LinearGradient(
                  colors: [_navy, Color(0xFF163F53)],
                ),
              ),
              child: Wrap(
                alignment: WrapAlignment.spaceBetween,
                crossAxisAlignment: WrapCrossAlignment.center,
                runSpacing: 18,
                spacing: 18,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        'OPÉRATIONS TERRAIN',
                        style: TextStyle(
                          color: Color(0xFF8DE2D5),
                          letterSpacing: 1.8,
                          fontSize: 11,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 6),
                      const Text(
                        'Planning terrain',
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: 29,
                          fontWeight: FontWeight.w800,
                        ),
                      ),
                      const SizedBox(height: 5),
                      const Text(
                        'Planifier, suivre et piloter les équipes sur site.',
                        style: TextStyle(
                          color: Color(0xFFC5D5DE),
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                  Wrap(
                    spacing: 9,
                    runSpacing: 9,
                    children: [
                      OutlinedButton.icon(
                        onPressed: () => teamDialog(),
                        style: OutlinedButton.styleFrom(
                          foregroundColor: Colors.white,
                          side: const BorderSide(color: Color(0xFF85A7B3)),
                        ),
                        icon: const Icon(Icons.group_add_outlined),
                        label: const Text('Créer une équipe'),
                      ),
                      FilledButton.icon(
                        onPressed: () => taskDialog(),
                        style: FilledButton.styleFrom(
                          backgroundColor: const Color(0xFF13A398),
                        ),
                        icon: const Icon(Icons.add),
                        label: const Text('Nouvelle tâche'),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            if (loading)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: LinearProgressIndicator(),
              ),
            if (error != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: _surface(
                  child: Text(
                    'Impossible de charger les données : $error',
                    style: const TextStyle(color: Color(0xFFC7363D)),
                  ),
                ),
              ),
            const SizedBox(height: 23),
            _heading(
              'Vue d’ensemble',
              'Cliquez sur un indicateur pour voir les interventions concernées.',
            ),
            Wrap(
              spacing: 11,
              runSpacing: 11,
              children: [
                _kpiCard(
                  'Programmées',
                  '${tasks.length}',
                  'Toutes les interventions',
                  Icons.event_note_outlined,
                  _teal,
                  tasks,
                ),
                _kpiCard(
                  'Réalisées',
                  '${done.length}',
                  'Terminées',
                  Icons.task_alt,
                  const Color(0xFF16865F),
                  done,
                ),
                _kpiCard(
                  'En cours',
                  '${active.length}',
                  'En intervention',
                  Icons.timelapse_outlined,
                  const Color(0xFFBF7621),
                  active,
                ),
                _kpiCard(
                  'Partiellement réalisées',
                  '${partial.length}',
                  'Reste à finaliser',
                  Icons.pie_chart_outline,
                  const Color(0xFF2568A0),
                  partial,
                ),
                _kpiCard(
                  'En retard',
                  '${delayed.length}',
                  'Délai dépassé',
                  Icons.warning_amber_outlined,
                  const Color(0xFFC7363D),
                  delayed,
                ),
                _kpiCard(
                  'Bloquées',
                  '${blocked.length}',
                  'À débloquer',
                  Icons.block_outlined,
                  const Color(0xFFC7363D),
                  blocked,
                ),
                _kpiCard(
                  'Réalisation',
                  '${(realization * 100).round()} %',
                  'Tâches réalisées',
                  Icons.trending_up,
                  _teal,
                  done,
                ),
                _kpiCard(
                  'Respect du délai',
                  '${done.isEmpty ? 0 : (punctual.length / done.length * 100).round()} %',
                  'Parmi les réalisées',
                  Icons.schedule_outlined,
                  const Color(0xFF16865F),
                  punctual,
                ),
              ],
            ),
            const SizedBox(height: 22),
            _surface(
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Performance du programme',
                          style: TextStyle(
                            color: _navy,
                            fontWeight: FontWeight.w800,
                            fontSize: 14,
                          ),
                        ),
                        const SizedBox(height: 5),
                        Text(
                          realization >= .9
                              ? 'Très bon'
                              : realization >= .8
                              ? 'Bon'
                              : realization >= .7
                              ? 'À surveiller'
                              : 'Critique',
                          style: const TextStyle(color: _muted, fontSize: 12),
                        ),
                        const SizedBox(height: 10),
                        ClipRRect(
                          borderRadius: BorderRadius.circular(5),
                          child: LinearProgressIndicator(
                            value: realization,
                            minHeight: 9,
                            backgroundColor: const Color(0xFFE6EDF2),
                            color: _teal,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 24),
                  Text(
                    '${(realization * 100).round()} %',
                    style: const TextStyle(
                      fontSize: 27,
                      color: _navy,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            Wrap(
              spacing: 5,
              runSpacing: 5,
              children: [
                _tab('Interventions', 0, Icons.table_rows_outlined),
                _tab(
                  'À traiter (${issues.length})',
                  1,
                  Icons.priority_high_rounded,
                ),
                _tab('Équipes (${teams.length})', 2, Icons.groups_outlined),
              ],
            ),
            const SizedBox(height: 12),
            if (view == 0)
              _surface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _heading(
                      'Tableau des interventions',
                      '${visible.length} résultat(s) · ${tasks.length} tâche(s) au total',
                      trailing: OutlinedButton.icon(
                        onPressed: exportingTasks || visible.isEmpty ? null : () => exportTasks(visible),
                        icon: const Icon(Icons.download_outlined),
                        label: Text(exportingTasks ? 'Export en cours…' : 'Exporter Excel (${visible.length})'),
                      ),
                    ),
                    _filters(),
                    const SizedBox(height: 16),
                    if (!compact)
                      const Padding(
                        padding: EdgeInsets.only(bottom: 10),
                        child: Row(
                          children: [
                            Icon(
                              Icons.pan_tool_alt_outlined,
                              color: _muted,
                              size: 15,
                            ),
                            SizedBox(width: 7),
                            Text(
                              'Maintenez le clic droit et déplacez la souris pour parcourir les colonnes.',
                              style: TextStyle(color: _muted, fontSize: 11),
                            ),
                          ],
                        ),
                      ),
                    _table(shown),
                    if (visible.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Row(
                          children: [
                            Text(
                              'Page ${safePage + 1} / $totalPages  ·  $pageSize par page',
                              style: const TextStyle(
                                color: _muted,
                                fontSize: 12,
                              ),
                            ),
                            const Spacer(),
                            IconButton(
                              tooltip: 'Page précédente',
                              onPressed: safePage == 0
                                  ? null
                                  : () => setState(
                                      () => pageIndex = safePage - 1,
                                    ),
                              icon: const Icon(Icons.chevron_left),
                            ),
                            IconButton(
                              tooltip: 'Page suivante',
                              onPressed: safePage >= totalPages - 1
                                  ? null
                                  : () => setState(
                                      () => pageIndex = safePage + 1,
                                    ),
                              icon: const Icon(Icons.chevron_right),
                            ),
                          ],
                        ),
                      ),
                  ],
                ),
              ),
            if (view == 1)
              _surface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _heading(
                      'Interventions à traiter',
                      'En retard, bloquées ou non réalisées.',
                    ),
                    if (issues.isEmpty)
                      _empty(
                        Icons.verified_outlined,
                        'Tout est à jour',
                        'Aucune intervention ne nécessite de suivi immédiat.',
                      ),
                    for (final task in issues)
                      Padding(
                        padding: const EdgeInsets.only(bottom: 8),
                        child: Container(
                          decoration: BoxDecoration(
                            color: const Color(0xFFFFFAF9),
                            borderRadius: BorderRadius.circular(12),
                            border: Border.all(color: const Color(0xFFF1DEDB)),
                          ),
                          child: ListTile(
                            leading: const Icon(
                              Icons.report_problem_outlined,
                              color: Color(0xFFC7363D),
                            ),
                            title: Text(
                              names(task),
                              maxLines: 2,
                              overflow: TextOverflow.ellipsis,
                            ),
                            subtitle: Text(
                              '${task['teamName']} · ${task['description']}\n${task['status']}${overdue(task) ? ' · ${difference(task)} j de retard' : ''} · Motif : ${task['reason']?.toString().isNotEmpty == true ? task['reason'] : '—'}',
                              maxLines: 3,
                              overflow: TextOverflow.ellipsis,
                            ),
                            isThreeLine: true,
                            trailing: IconButton(
                              tooltip: 'Modifier',
                              icon: const Icon(Icons.edit_outlined),
                              onPressed: () => taskDialog(task),
                            ),
                          ),
                        ),
                      ),
                  ],
                ),
              ),
            if (view == 2)
              _surface(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    _heading(
                      'Équipes terrain',
                      'Cliquez sur une équipe pour consulter son effectif et ses résultats.',
                      trailing: TextButton.icon(
                        onPressed: () => teamDialog(),
                        icon: const Icon(Icons.add),
                        label: const Text('Nouvelle équipe'),
                      ),
                    ),
                    if (teams.isEmpty)
                      _empty(
                        Icons.group_add_outlined,
                        'Aucune équipe',
                        'Créez une équipe pour commencer à programmer les interventions.',
                      ),
                    Wrap(
                      spacing: 12,
                      runSpacing: 12,
                      children: [for (final team in teams) _teamCard(team)],
                    ),
                  ],
                ),
              ),
            const SizedBox(height: 26),
            PlanningTrackingPanel(
              api: api,
              tasks: tasks,
              onMission: (task) => taskDialog(task),
              onPlan: (site, previous) => taskDialog(null, [site], previous),
            ),
          ],
        ),
      ),
    );
  }
}
