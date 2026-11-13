# Estimation par meilleur site du même gouvernorat

## Période
- Filtre demandé, avec exclusion du jour actuel (Africa/Tunis).
- Pour chaque site : début effectif = max(début filtre, date de swap).
- Sans date de swap : calcul indisponible. La mise en service n'intervient pas.

## Jours et référence
- Jour réel valide : donnée finie et production / puissance >= 1 kWh/kWc.
- Jour à estimer : absent, nul, négatif, non fini, zéro ou rendement strictement inférieur à 1.
- Le seuil est comparé AVANT arrondi ; exactement 1 est valide.
- Meilleur site : moyenne spécifique la plus élevée, calculée avec ses seuls jours réels valides.
- Un seul jour valide suffit. Toutes les marques et tous les statuts sont éligibles ; seuls les sites accessibles à l'utilisateur entrent dans le rapport et ses références.
- Chaque référence utilise le filtre commun et sa propre borne de swap. Ses jours valides peuvent être antérieurs au swap du site à compléter.
- Aucune moyenne ne contient d'estimations.
- Puissance absente, non finie ou <= 0 : impossible de normaliser, calcul indisponible.
- Gouvernorat inconnu : aucune référence à un autre site ; sa propre moyenne valide reste utilisable, sinon repli théorique si zone disponible.

## Formules
Rendement moyen = somme des kWh réels valides / nombre de jours valides / puissance.
Remplacement par jour = rendement moyen du meilleur site du gouvernorat × puissance du site.
Le meilleur site utilise sa propre moyenne pour ses propres jours à estimer.
Sans référence valide : rendement théorique de chaque jour × puissance du site (100 %, sans coefficient 70 %).
Total corrigé = réel conservé + somme des remplacements.
Les valeurs faibles sont remplacées, jamais ajoutées une deuxième fois.
Le prévu théorique demeure calculé sur toute la période effective.
Les arrondis sont appliqués aux agrégats finaux, pas avant le seuil ou la sélection.

Exemple : meilleur site 5 kWc, 30 kWh sur son seul jour valide => 6 kWh/kWc/jour.
Un site 10 kWc reçoit 60 kWh pour chaque jour à estimer.
Le meilleur site reçoit 30 kWh pour chacun de ses propres jours à estimer.

## Front et export
- Compteurs : jours valides / jours estimés.
- Énergie : réel conservé, estimé et total corrigé.
- Infobulle sur le titre pour expliquer la méthode.
- Les données brutes enregistrées en base restent intactes ; le remplacement concerne le rapport.
- La politique indépendante du module de comparaison journalière reste inchangée.

## Fichiers modifiés
- backend/monitoring/src/main/java/com/fusionsolar/monitoring/service/ProductionEstimationService.java
- backend/monitoring/src/main/java/com/fusionsolar/monitoring/service/PerformanceReportService.java
- backend/monitoring/src/test/java/com/fusionsolar/monitoring/service/ProductionEstimationServiceTests.java
- backend/monitoring/src/test/java/com/fusionsolar/monitoring/service/PerformanceReportSwapTests.java
- backend/monitoring/src/test/java/com/fusionsolar/monitoring/service/GovernorateProductionTests.java (nouveau)
- monitoring/lib/modules/performence/pages/performance_page.dart

## Validation
20 cas de test ciblés passent : moteur, nouvelles règles et intégration du rapport.
Compilation Java ciblée des services avec substituts locaux pour les dépendances de framework et les accesseurs/constructeurs Lombok.
La suite Maven complète est bloquée par le parent Spring Boot 4.0.5 absent du cache.
Flutter/Dart ne sont pas installés : compilation frontend non effectuée.
Le ZIP contient les sources, pas les binaires de déploiement.
