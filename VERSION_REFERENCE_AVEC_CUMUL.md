> Mise à jour du 25/11/2026 : les règles de dates de ce document historique sont remplacées par REGLES_ORIGINE_CUMUL.md.

# Version de référence + cumul des onduleurs disponible

Base : MonitoringSites(20261124-113336)(1).zip, version fournie comme référence
aux captures 814,353 MWh prévus / 717,665 MWh réels filtrés.

## Règles conservées

Le prévu reste calculé avec la puissance et les rendements théoriques du projet,
sur [max(début du filtre, swap), min(fin du filtre, hier)].
La MES ne remplace pas le swap dans ce calcul. Le cache de cumul ne modifie pas
le prévu, les dates de production ni le nombre de jours de la période.

Sans cumul exploitable, tous les champs du résultat restent ceux de la référence :
historiques positifs, estimation des jours absents/nuls/invalides par la meilleure
moyenne spécifique du même gouvernorat (site lui-même inclus), sinon théorique.
Le réel filtré additionne toujours le mesuré et le calculé.
Les règles de filtre, d'accès, de statuts, d'exception SFA_0138 et de sélection des
références restent celles du ZIP de référence. Aucune règle 70 % n'est ajoutée.
La synchronisation de l'inventaire Huawei est également celle du ZIP de référence.

## Seul nouveau chemin de calcul

Si le site possède un cumul complet et exploitable, sa production dans le filtre
est : somme des total_cap des onduleurs × théorique de la période du rapport /
théorique depuis la MES jusqu'à aujourd'hui.
Les onduleurs Huawei de types 1 et 38 sont pris en charge, sans doublons d'ID.
La valeur répartie remplace le mesuré ; le complément calculé vaut alors zéro :
l'ancien historique et les anciennes estimations ne sont pas ajoutés une seconde fois.
Les écarts et bonus/malus utilisent ce nouveau total et le même prévu qu'avant.
Le cumul est conservé entier : aucune valeur day_power n'est soustraite.
La période du rapport restant bornée à hier, elle exclut le poids attribué à aujourd'hui.

Exploitable signifie : tous les onduleurs ont une valeur finie non négative,
collectée depuis moins de 20 minutes, et les dates permettent la répartition.
Si un second onduleur manque, pas de somme partielle : retour complet à l'ancien calcul.
Si MES manque ou est postérieure au swap, origine du cumul incohérente : ancien calcul.
Un swap absent conserve le comportement indisponible de la version de référence.
Un cumul nul est une valeur valide ; il n'est pas confondu avec une valeur absente.
Solis et Growatt conservent leur calcul de référence.

## Présentation et collecte

Conformément à la décision précédente, seule la date MES est affichée dans le
tableau et l'export. Le swap reste utilisé dans les calculs.
Les libellés des jours sont génériques : couverts / à compléter.
Aucun badge ou texte de provenance des kWh n'est ajouté dans l'interface.
Le contrôleur des filtres et des sommes affichées est identique à la référence.

La collecte des cumuls s'ajoute en tâche de fond. Les rapports lisent le cache et
ne déclenchent pas d'appels Huawei. Les comptes, les types et les lots sont séparés,
avec maximum 100 appareils par appel et minimum 5 minutes entre appels sur le
périmètre de quota configuré. Les autres limites d'appels restent celles de la référence.
Les nouveaux champs sont conservés lors des mises à jour du cache des appareils.

## Vérification

- Comparaison différentielle avec le service compilé depuis le ZIP de référence :
  245 scénarios, 6615 comparaisons de champs toutes identiques lorsque le cumul
  est inutilisable. Cela couvre les marques, puissances, dates, valeurs manquantes,
  sommes partielles et valeurs périmées.
- 19 contrôles de stabilité du prévu passent.
- 18 contrôles de rapport/cumul et 17 de répartition passent.
- 9 tests existants de rapport et d'exception passent.
- Compilation Java ciblée réussie ; constructeur Lombok explicité uniquement
  dans une copie temporaire de validation. Les sources livrées gardent Lombok.
- Le build Maven/JUnit et Flutter complet reste à valider dans votre CI.

Le total réel peut changer sur les sites qui utilisent effectivement le cumul,
puisqu'une répartition du cumul remplace l'ancienne estimation. Il n'est pas forcé
à 717,665 MWh. Le total prévu reste identique à données, filtres et sites identiques.
Les nombres de sites dans vos captures différaient : 185 puis 187.

## Mise à jour depuis la version déjà déployée

Ce ZIP contient le projet source complet reconstruit depuis la référence.
Pour préserver d'éventuelles modifications récentes d'autres modules de votre dépôt,
appliquer uniquement les chemins énumérés dans FICHIERS_A_APPLIQUER.txt.
HuaweiDeviceSyncService.java fait partie des fichiers à appliquer car il rétablit
explicitement la synchronisation d'inventaire d'origine.
Les workflows CI/CD et réglages de déploiement sont ceux de votre référence.
Aucun JAR ni build web précompilé n'est fourni.

Avant de copier les fichiers, à la racine du dépôt dans PowerShell :

```powershell
git status
git fetch origin
git switch -c fix/reference-avec-cumul origin/main
```

S'assurer que le dossier de travail est propre avant le changement de branche.
Copier ensuite les seuls fichiers de la liste dans le dépôt et exécuter :

```powershell
Get-Content .\FICHIERS_A_APPLIQUER.txt | ForEach-Object { git add -- $_ }
git diff --cached --stat
git commit -m "Conserver les regles de reference avec repli du cumul Huawei"
git push -u origin fix/reference-avec-cumul
```

Créer une Pull Request vers main, attendre les contrôles verts, fusionner, puis
utiliser la CI réussie sur main pour lancer prepare et deploy.
