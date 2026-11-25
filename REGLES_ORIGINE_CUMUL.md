# Règles MES / swap et répartition du cumul — 25 novembre 2026

Source : MonitoringSites(20261125-111722).zip fourni par l'utilisateur.
Ce document remplace les explications antérieures sur l'ordre MES/swap.

## Cumul disponible et exploitable

Origine = plus ancienne date disponible parmi MES et swap.
Début effectif = max(début du filtre, origine).
Fin effective = min(fin du filtre, hier), comme dans le rapport existant.

kWh mesuré = cumul entier × somme du théorique entre début et fin effectifs /
somme du théorique entre l'origine et aujourd'hui.
L'origine utilisée au dénominateur est toujours la même que celle choisie pour
borner le numérateur. Aucune valeur de production du jour n'est soustraite.
Le rapport borné à hier n'inclut pas le poids de la journée courante.
L'ordre entre MES et swap n'est plus une cause de rejet du cumul.
Le complément calculé vaut zéro lorsque le cumul est réparti ; il n'y a pas de
double addition des anciens historiques ou des anciennes estimations.

## Exemples validés, filtre 01/01/2025 -> 31/12/2025

| Swap | MES | Origine du cumul | Début dans le filtre |
| --- | --- | --- | --- |
| 12/12/2024 | 15/01/2025 | 12/12/2024 | 01/01/2025 |
| 15/01/2025 | 12/12/2024 | 12/12/2024 | 01/01/2025 |
| 05/01/2025 | 10/01/2025 | 05/01/2025 | 05/01/2025 |
| 10/01/2025 | 05/01/2025 | 05/01/2025 | 05/01/2025 |
| 12/12/2024 | 15/12/2024 | 12/12/2024 | 01/01/2025 |
| 15/12/2024 | 12/12/2024 | 12/12/2024 | 01/01/2025 |

- Une seule date valide disponible : elle sert d'origine au cumul.
- Deux dates identiques : utiliser cette date.
- Filtre entièrement antérieur à l'origine : 0 kWh attribué si cumul exploitable.
- Filtre partiellement antérieur : seuls les jours depuis l'origine sont pondérés.
- Aucune date valide disponible : aucun calcul de production/prévu/jours du site.
  Les champs numériques calculés sont null, le statut est MISSING_ORIGIN_DATE,
  et l'interface ainsi que l'export affichent « Indisponible ».
  Il n'y a pas de retour à l'ancien calcul dans ce dernier cas, même pour un site
  habituellement forcé à zéro ; les métadonnées du site restent affichées.

## Ce qui reste inchangé

Le prévu conserve son calcul de référence : puissance et théorique entre
max(début du filtre, swap) et min(fin du filtre, hier). Il n'est pas réécrit quand
un cumul devient disponible. Les bornes de la production répartie peuvent donc
être différentes des bornes historiques du prévu si MES précède swap.
Sans swap, le prévu reste indisponible, même si une MES permet de répartir le cumul.

Si le cumul est absent, incomplet ou inexploitable, ancien calcul du rapport :
historiques positifs + estimation des jours absents/nuls/invalides, avec les
conditions de référence. L'exception explicite est l'absence des deux dates,
qui interdit désormais tout calcul de production pour la ligne.
Les filtres, zones, coefficients théoriques et contrôles d'accès ne sont pas modifiés.
Les périodes et compteurs de jours retournés suivent la nouvelle origine seulement
lorsque le cumul est effectivement utilisé ; le repli conserve ses bornes historiques.

## Disponibilité du cumul

Collecte Huawei existante inchangée. Somme complète des onduleurs distincts,
types 1/38, valeurs finies non négatives et récentes (20 minutes).
Si le site possède deux onduleurs et qu'un cumul manque : repli pour tout le site.
Les valeurs nulles, invalides et périmées ne sont pas remplacées par un faux zéro.
Un cumul réellement égal à zéro reste valide.
L'interface n'affiche aucune information sur la provenance des kWh.

## Validation effectuée

- Compilation Maven des sources backend et des 38 fichiers de test réussie.
- 84 tests unitaires backend exécutés avec succès via le moteur JUnit Jupiter
  disponible, avec agent Byte Buddy chargé au démarrage pour Mockito.
  Le test de contexte Spring Boot/MongoDB est exclu de cette exécution locale.
- Les tests comprennent les six exemples, une date unique, aucune date,
  dates égales, filtre avant l'origine, borne à hier, cumul partiel/périmé et
  stabilité du prévu. Le contrôle dédié aux nouvelles bornes comporte 108 assertions.
- 245 scénarios de repli vérifiés ; 5886 comparaisons des champs legacy,
  avec une vérification distincte des cas sans aucune date.
- 15 vérifications Dart du modèle exécutées ; syntaxe des fichiers Dart modifiés
  et du nouveau test vérifiée.
- Le cycle Maven test/verify complet reste à exécuter en CI : dépendances du
  plugin Surefire manquantes localement.
- La revue automatique a bloqué l'installation des dépendances Flutter car elle
  tentait un accès aux métadonnées de l'environnement. Aucun contournement
  n'a été tenté. Le test Flutter complet et le build web restent à faire en CI.

## Livraison / CI-CD

Ce ZIP contient les sources complètes issues du ZIP actuel, sans .git, build,
target ni caches. Aucune configuration privée ni workflow CI/CD n'est modifié.
Aucun JAR ni frontend précompilé n'est inclus.

Pour conserver les éventuelles autres modifications de votre dépôt, appliquer
uniquement les fichiers de FICHIERS_A_APPLIQUER.txt et garder leurs sous-dossiers.
La liste contient uniquement les changements de cette livraison par rapport
à MonitoringSites(20261125-111722).zip.

Créer d'abord une branche depuis main dans un dossier de travail propre :

```powershell
git status
git fetch origin
git switch -c fix/origine-mes-swap origin/main
```

Puis copier les fichiers de la liste et lancer :

```powershell
Get-Content .\FICHIERS_A_APPLIQUER.txt | ForEach-Object { git add -- $_ }
git diff --cached --stat
git commit -m "Repartir le cumul depuis la plus ancienne date MES ou swap"
git push -u origin fix/origine-mes-swap
```

Créer une PR vers main, attendre la CI, fusionner puis attendre la CI sur main.
Utiliser son identifiant pour prepare et deploy, comme lors des mises à jour précédentes.
