import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:monitoring/core/network/api_client.dart';
import 'package:monitoring/modules/planning/widgets/planning_tracking_panel.dart';

class TrackingApi extends ApiClient {
  final calls = <String>[];
  String get today => DateTime.now().toIso8601String().substring(0, 10);
  Map<String, dynamic> get site => {
    'id': 's1',
    'brand': 'HUAWEI',
    'siteCode': 'ARI_TEST',
    'siteName': 'ARI_TEST',
    'status': 'DISCONNECTED',
    'active': true,
    'observedAt': DateTime.now().toUtc().toIso8601String(),
    'commissionedAt': today,
  };
  @override
  Future<dynamic> get(
    String path, {
    Map<String, dynamic>? queryParameters,
  }) async {
    calls.add(path);
    if (path.endsWith('/overview'))
      return {
        'ready': true,
        'activatedAt': today,
        'lastScanAt': today,
        'startCount': 160,
        'endCount': 170,
        'currentCount': 170,
        'added': 10,
        'removed': 0,
        'reactivated': 0,
        'stale': 0,
        'sites': [site],
        'events': [
          {
            'siteId': 's1',
            'siteName': 'ARI_TEST',
            'siteCode': 'ARI_TEST',
            'brand': 'HUAWEI',
            'kind': 'ADDED',
            'at': today,
            'after': 'DISCONNECTED',
          },
        ],
      };
    if (path.endsWith('/results'))
      return [
        {
          'taskId': 't1',
          'project': 'Monitoring',
          'team': 'Équipe A',
          'missionStatus': 'Réalisé',
          'type': 'Monitoring',
          'plannedDate': today,
          'completedDate': today,
          'site': site,
          'assessment': 'Rechute observée',
          'needsFollowup': true,
          'nearbyMissions': 0,
        },
      ];
    return [
      {
        'kind': 'STATE_CHANGED',
        'at': today,
        'before': 'HEALTHY',
        'after': 'DISCONNECTED',
      },
    ];
  }
}

void main() {
  testWidgets(
    'Trois onglets, ajouts, missions, historique et reprise de mission',
    (tester) async {
      tester.view.physicalSize = const Size(1440, 1000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      final api = TrackingApi();
      String? previous;
      Map<String, dynamic>? selected;
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: PlanningTrackingPanel(
                api: api,
                tasks: const [
                  {'id': 't1'},
                ],
                onMission: (_) async {},
                onPlan: (site, task) async {
                  selected = site;
                  previous = task;
                },
              ),
            ),
          ),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('Missions'), findsOneWidget);
      await tester.tap(find.text('Évolution des sites'));
      await tester.pumpAndSettle();
      expect(find.text('160'), findsOneWidget);
      expect(find.text('10'), findsOneWidget);
      expect(find.text('Ajouts détectés'), findsOneWidget);
      await tester.tap(find.text('Missions'));
      await tester.pumpAndSettle();
      expect(find.text('État actuel observé'), findsOneWidget);
      await tester.tap(find.byTooltip('Historique du site').first);
      await tester.pumpAndSettle();
      expect(find.textContaining('Changement d’état'), findsOneWidget);
      await tester.tap(find.text('Fermer'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Résultats des interventions'));
      await tester.pumpAndSettle();
      expect(find.text('Rechute observée'), findsOneWidget);
      await tester.tap(find.byTooltip('Planifier une mission de reprise'));
      await tester.pumpAndSettle();
      expect(previous, 't1');
      expect(selected?['siteCode'], 'ARI_TEST');
      expect(
        api.calls.every((p) => p.startsWith('/api/planning/tracking/')),
        isTrue,
      );
      expect(tester.takeException(), isNull);
      await tester.pumpWidget(
        const SizedBox(),
      ); // cancel the local refresh timer
    },
  );
}
