# FusionSolar -> Mongo sync policy

Objectif: pousser MongoDB le plus près possible de FusionSolar sans déclencher `ACCESS_FREQUENCY_IS_TOO_HIGH`, `407` ou `429`.

## Règle principale

Le flux temps-réel des sites Huawei est maintenant le flux principal de synchronisation backend.

- Scheduler backend: toutes les 5 minutes (`app.huawei-sync.summary-cron`).
- Protection anti-burst: une nouvelle sync Huawei est ignorée si la précédente a démarré il y a moins de `app.huawei-sync.min-summary-refresh-interval-ms`.
- Anti-overlap: une seule sync Huawei peut tourner à la fois.
- Batch API Huawei: maximum 100 sites par appel.
- Pour environ 162 sites, la sync complète utilise 2 appels `getStationRealKpi` par cycle.

## Données rafraîchies toutes les 5 minutes

La collection Mongo des sites est remise à jour avec les informations de portefeuille et KPI live:

- statut opérationnel;
- énergie du jour;
- énergie du mois;
- énergie de l'année;
- énergie totale si fournie par l'API;
- puissance installée;
- puissance active si disponible;
- date de dernière mise à jour Mongo.

## Données volontairement plus lentes

Ces endpoints FusionSolar ont une limite journalière plus basse, donc ils ne doivent pas être appelés toutes les 5 minutes:

- liste des plants / stations: cache backend 60 minutes;
- devices: sync lente planifiée, au maximum une fois par jour si le cache est complet;
- historiques day/month/year/custom: lazy loading à l'ouverture du détail site, avec cache Mongo;
- historiques globaux sur tous les sites: pas de scheduler massif, sinon le backend peut dépasser les limites Huawei.

## Fichiers responsables

- `src/main/resources/application.yml`  
  Configure le rythme de sync Huawei.

- `src/main/java/com/fusionsolar/monitoring/service/sync/SitesSummarySyncSchedulerService.java`  
  Lance la sync planifiée.

- `src/main/java/com/fusionsolar/monitoring/service/sync/HuaweiSyncService.java`  
  Protège la sync contre overlap et refresh trop fréquent, puis sauvegarde Mongo.

- `src/main/java/com/fusionsolar/monitoring/service/HuaweiLiveStationService.java`  
  Récupère les données FusionSolar, applique les caches backend et prépare les KPI.

- `src/main/java/com/fusionsolar/monitoring/client/FusionSolarStationClient.java`  
  Appelle les endpoints FusionSolar et gère les retours 407 / 429.

- `src/main/java/com/fusionsolar/monitoring/service/sync/HuaweiHistorySyncService.java`  
  Gère le cache Mongo des historiques demandés par la page détail.

## Ce que l'utilisateur voit

Après un changement côté FusionSolar:

1. Huawei collecte et rend la donnée disponible sur l'API Northbound.
2. Le backend VORA récupère les KPI live au prochain cycle de 5 minutes.
3. MongoDB reçoit les nouvelles valeurs.
4. Le front verra la nouvelle valeur dès son prochain appel backend.

La prochaine étape est de synchroniser le front avec Mongo en polling propre, refresh manuel intelligent ou stream/SSE selon le besoin UX.
