import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:get/get.dart';
import 'package:monitoring/core/network/api_client.dart';
import 'package:monitoring/modules/planning/pages/planning_page.dart';
import 'package:monitoring/modules/planning/widgets/planning_tracking_panel.dart';

class ManualPlanningApi extends ApiClient {
  final catalog = <Map<String, dynamic>>[];
  int createCalls = 0;
  bool failCreation = false;
  bool showManualResult = false;
  Map<String, dynamic> get manual => {
    'id': 'manual_1', 'brand': 'MANUAL', 'siteCode': 'manual_1',
    'siteName': 'SITE FUTUR', 'manual': true, 'active': true,
    'status': 'WITHOUT_MONITORING',
  };

  @override
  Future<dynamic> get(String path, {Map<String, dynamic>? queryParameters}) async {
    if (path == '/api/planning/teams') {
      return [{'id': 'team', 'name': 'Terrain', 'manager': 'Chef', 'personnel': []}];
    }
    if (path == '/api/planning/sites') return catalog;
    if (path == '/api/planning/tasks') return [];
    if (path == '/api/planning/mission-issuers') return ['Direction'];
    if (path.endsWith('/overview')) {
      return {
        'ready': true, 'sites': [], 'events': [],
        'startCount': 0, 'endCount': 0, 'currentCount': 0,
        'added': 0, 'removed': 0, 'reactivated': 0, 'stale': 0,
      };
    }
    if (path.endsWith('/results')) {
      return showManualResult ? [{
        'taskId': 'task', 'project': 'Avant installation', 'team': 'Terrain',
        'type': 'Étude', 'missionStatus': 'Planifié', 'site': manual,
        'plannedDate': DateTime.now().toIso8601String().substring(0, 10),
        'assessment': 'Sans monitoring · validation terrain',
        'needsFollowup': false, 'nearbyMissions': 0,
      }] : [];
    }
    throw StateError('Unexpected GET $path');
  }

  @override
  Future<dynamic> post(String path, {dynamic data}) async {
    if (path != '/api/planning/sites') throw StateError('Unexpected POST $path');
    createCalls++;
    if (failCreation) throw Exception('Création indisponible');
    final site = {...manual, 'siteName': (data as Map)['siteName']};
    catalog.add(site);
    return site;
  }
}

void main() {
  testWidgets('Site inconnu ajouté puis réutilisable sans site de monitoring',
      (tester) async {
    tester.view.physicalSize = const Size(1440, 1100);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = ManualPlanningApi();
    Get.put<ApiClient>(api);
    addTearDown(() => Get.reset());
    await tester.pumpWidget(const MaterialApp(home: PlanningPage()));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Nouvelle tâche'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Choisir les sites *'));
    await tester.pumpAndSettle();
    final search = find.byWidgetPredicate((w) =>
        w is TextField &&
        w.decoration?.hintText == 'Rechercher par nom, code ou marque');
    await tester.enterText(search, 'SITE FUTUR');
    await tester.pumpAndSettle();
    expect(find.text('Ajouter « SITE FUTUR » au planning'), findsOneWidget);
    api.failCreation = true;
    await tester.tap(find.text('Ajouter « SITE FUTUR » au planning'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Création indisponible'), findsOneWidget);
    api.failCreation = false;
    await tester.tap(find.text('Ajouter « SITE FUTUR » au planning'));
    await tester.pumpAndSettle();
    expect(api.catalog.length, 1);
    expect(tester.widget<CheckboxListTile>(find.byType(CheckboxListTile)).value, isTrue);
    expect(find.text('Site manuel · Planning uniquement'), findsOneWidget);
    await tester.tap(find.text('Confirmer'));
    await tester.pumpAndSettle();
    expect(find.text('1 site(s) sélectionné(s)'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Nouvelle tâche'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Choisir les sites *'));
    await tester.pumpAndSettle();
    await tester.enterText(search, 'site futur');
    await tester.pumpAndSettle();
    expect(find.text('SITE FUTUR'), findsOneWidget);
    expect(find.textContaining('Ajouter «'), findsNothing);
    expect(api.createCalls, 2); // one failed request, then successful creation
    await tester.tap(find.text('Annuler').last);
    await tester.pumpAndSettle();
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });

  testWidgets('Résultat manuel affiché sans état fournisseur et replanifiable',
      (tester) async {
    tester.view.physicalSize = const Size(1440, 1100);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final api = ManualPlanningApi()..showManualResult = true;
    Map<String, dynamic>? plannedSite;
    await tester.pumpWidget(MaterialApp(home: Scaffold(
      body: SingleChildScrollView(child: PlanningTrackingPanel(
        api: api,
        tasks: const [{'id': 'task'}],
        onMission: (_) async {},
        onPlan: (site, previous) async { plannedSite = site; },
      )),
    )));
    await tester.pumpAndSettle();
    expect(find.text('Site manuel · Sans monitoring'), findsOneWidget);
    final history = find.byTooltip('Historique disponible dans la mission');
    final historyButton = find.ancestor(of: history, matching: find.byType(IconButton));
    expect(tester.widget<IconButton>(historyButton).onPressed, isNull);
    await tester.tap(find.text('Résultats des interventions'));
    await tester.pumpAndSettle();
    expect(find.text('Sans monitoring · validation terrain'), findsOneWidget);
    expect(tester.takeException(), isNull);
    await tester.tap(find.byTooltip('Planifier une mission de reprise'));
    await tester.pumpAndSettle();
    expect(plannedSite?['brand'], 'MANUAL');
    expect(plannedSite?['siteCode'], 'manual_1');
    await tester.pumpWidget(const SizedBox());
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
  });
}
