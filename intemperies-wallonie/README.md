# Intempéries Wallonie — application Android

Application Android (Kotlin, Jetpack Compose) qui aide l'auteur de projet / le surveillant de chantier à compléter
le calendrier des travaux : elle relie chaque commune wallonne suivie au **pluviomètre SPW le plus proche**,
collecte les mesures en continu et qualifie chaque journée (vert / orange / rouge), puis génère un
**calendrier mensuel PDF** par commune.

## Fonctions

| Fonction | Détail |
|---|---|
| Communes | Les 262 communes wallonnes (recherche par nom ou code INS), sélection multiple |
| Stations | Pluviomètre SPW le plus proche du centroïde de la commune (choix manuel possible) ; station de température de l'air la plus proche dans un rayon paramétrable |
| Données | Pluie au pas de 5 min, température horaire ; synchro horaire en arrière-plan (WorkManager), incrémentale ; rechargement quotidien des 35 derniers jours pour intégrer les validations SPW |
| Stockage | Mois en cours + 6 mois précédents (paramétrable 1–12) ; purge automatique ; seules les stations utilisées sont conservées |
| Calendrier | Grille mensuelle colorée, détail par jour (pas pluvieux, cumuls, température, couverture), jours fériés légaux |
| PDF | Calendrier A4 par commune (ou toutes les communes du mois en un seul PDF), liste des jours d'intempéries avec indications pluviométriques, mention de la source SPW ; partage par mail/Teams/Drive |
| Excel (.xlsx) | Même calendrier coloré + tableau journalier chiffré (date, jour ouvrable, statut, mm, durées, T moy., motif, couverture, validation SPW) + synthèse par formules (`NB.SI`) qui se recalcule si un statut est corrigé ; une feuille par commune + feuilles « Mode de fonctionnement » et « Base réglementaire ». Généré sans bibliothèque externe |
| Widget | Un widget par commune : statut du jour, 7 derniers jours, bilan du mois |
| Fonctionnement | Écran dédié expliquant la méthode avec les paramètres en vigueur |
| Base réglementaire | Écran dédié : définition du jour d'intempérie selon le CCT Qualiroutes (ch. A, art. 76 § 4, 4°) |

## Règles de qualification (toutes paramétrables)

L'écran **« Fonctionnement »** (icône « ? » de l'accueil, ou Paramètres) explique la méthode avec les valeurs
**actuellement en vigueur** ; la même explication figure dans la feuille « Mode de fonctionnement » des exports Excel.

Une journée est une **intempérie** si les **précipitations** (pluie, neige ou grêle) **ou** la **température**
atteignent les seuils activés. Valeurs par défaut :

| Origine | Paramètres (par défaut) | Effet par défaut |
|---|---|---|
| Précipitations | plage **07:30–16:00**, pas de **30 min** (15 / 30 / 60), pas « avec précipitations » si cumul ≥ **0,1 mm** ; consécutives **> 3 h** ou totales **> 4 h** (option ≥) | 🔴 intempérie |
| Précipitations | totales **≥ 2 h** sans intempérie | 🟠 orange |
| Température — seuil bas (gel) | T **< 0 °C** pendant **≥ 4 h** cumulées dans la plage | signalé ❄, case à cocher pour 🔴 |
| Température — seuil haut (canicule) | T **> 30 °C** pendant **≥ 4 h** cumulées dans la plage | signalé ☀, case à cocher pour 🔴 |
| Données | < **80 %** des mesures de précipitations disponibles | ⚪ gris |

Les valeurs 30 °C et 4 h pour la température sont des valeurs de départ proposées (non imposées par un texte) ;
les cases « provoque une intempérie » sont décochées par défaut, conformément à la demande initiale.

- **Précipitations** : les pluviomètres SPW sont « à pesée » (source : hydrometrie.wallonie.be › Réseaux de mesure).
  *Interprétation* : ce principe pèse les précipitations liquides et solides (équivalent eau) ; l'application ne distingue
  donc pas pluie, neige et grêle.
- **Température** : moyennes horaires, chacune pondérée par son recouvrement avec la plage (07:00–08:00 compte pour 30 min).
  La durée sous le seuil bas (au-dessus du seuil haut) est la somme de ces recouvrements. La moyenne de la plage et les
  T min./max. horaires restent affichées à titre d'information.
- Un astérisque (*) signale les journées dont les données de **précipitations** ne sont pas encore validées par le SPW
  (code qualité ≥ 200). Les séries de température SPW restant au code 200, elles n'entrent pas dans cet indicateur.

## Base réglementaire (écran « Base réglementaire »)

**Explicite (texte cité)** — CCT Qualiroutes, Chapitre A « Clauses administratives », édition du 01/01/2026,
art. 76, § 4, 4°, p. A.77, reproduisant l'AR du 14 janvier 2013 (RGE) — recoupé avec la consolidation officieuse
WALLEX du RGE en vigueur depuis le 01/01/2025 :

> « § 4. Quand le délai d’exécution est fixé en jours ouvrables, ne sont pas considérés comme tels: […]
> 4° les jours pendant lesquels, sur reconnaissance de l'adjudicateur, le travail a, ou aurait, par suite de
> conditions météorologiques défavorables ou de leurs conséquences, été rendu impossible pendant quatre heures au moins. »

L'écran reprend aussi : les 1° à 3° (samedis, dimanches et jours fériés légaux, congés), le complément
« marchés d'électromécanique » (4 heures **consécutives**, liste hebdomadaire), l'art. 83 (journal des travaux,
obligatoire en génie civil) et l'art. 38/7 (révision des prix : jours suspendus pour intempéries exclus de jok).

**Implicite (interprétation)** — le texte ne fixe aucun seuil de pluie : les critères de l'application sont des
indicateurs objectifs pour documenter la reconnaissance par l'adjudicateur, pas une qualification automatique.
« Quatre heures au moins » correspond à ≥ 4 h : désactiver « Comparaison stricte » pour s'aligner sur ce libellé
(par défaut, la demande initiale « plus de 4 h » est appliquée).

## Sources des données

- **Service web KiWIS du SPW** — `https://hydrometrie.wallonie.be/services/KiWIS/KiWIS` (réseau Wacondah, DGH ;
  « une centaine de pluviomètres à pesée », selon hydrometrie.wallonie.be › Réseaux de mesure).
  - Pluie : séries `02b-Precipitation.5min.Production` (96 stations actives au 22/11/2026).
  - Température : séries `10-Temperature.1h.Moyen` filtrées sur le paramètre « Température Air » (27 stations actives).
  - Convention vérifiée : l'horodatage marque le **début** du pas (la valeur horaire de 16:00 = somme des pas 5 min de 16:00 à 16:55).
- **Conditions d'utilisation** (hydrometrie.wallonie.be › Mentions légales) : reproduction autorisée sans accord
  préalable, avec la mention « Sources des données : SPW » (reprise dans l'app et sur chaque PDF) ; données sans
  valeur légale ; données validées = code qualité < 200 ; **interdiction de fournir les données à un tiers ou de
  les diffuser au public sans accord écrit du SPW**. L'application interroge le SPW depuis chaque téléphone, sans
  serveur intermédiaire. *Interprétation* : joindre un PDF à un dossier de chantier semble couvert par la mention
  de source, mais une confirmation écrite du SPW (hydrometrie@spw.wallonie.be) est recommandée avant un usage
  contractuel systématique.
- **Communes** : 262 communes wallonnes et centroïdes calculés à partir des secteurs statistiques Statbel
  (dissolution par code INS, jeu « Belgium-Geographic-Data », Lambert 72 → WGS84).

## Limites connues

- **Température** : le réseau SPW ne compte que 27 stations de température de l'air, surtout en Ardenne. Médiane
  de la distance commune → station : 24,7 km ; jusqu'à 120 km dans l'ouest du Hainaut. Au-delà du rayon (40 km par
  défaut), la température est « non disponible ». Les données ouvertes de l'IRM (stations AWS) n'ont pas pu être
  testées depuis l'environnement de développement (accès réseau bloqué) : piste d'amélioration.
- **Pluie** : distance médiane commune → pluviomètre 5,5 km (max. 13,1 km). Une commune étendue peut connaître
  un temps différent de celui du pluviomètre.
- **Fusions de communes au 01/01/2025** : la liste est construite sur les limites Statbel antérieures ;
  les fusions éventuelles n'ont pas pu être vérifiées (sources officielles inaccessibles depuis l'environnement
  de développement). Sans impact sur la recherche de station (localisation par centroïde).
- Les congés du bâtiment ne sont pas intégrés (seuls les 10 jours fériés légaux).

## Construire et installer

Prérequis : JDK 17+, Android SDK (plateforme 36).

```bash
cd intemperies-wallonie
echo "sdk.dir=/chemin/vers/android-sdk" > local.properties
./gradlew testDebugUnitTest      # 25 tests (classification pluie/gel/canicule, jours fériés, parseur KiWIS, classeur Excel)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

Contrôle sur données réelles (réseau requis) :
`LIVE_SPW=1 XLSX_OUT=/tmp/Wavre.xlsx ./gradlew testDebugUnitTest --tests '*LiveSpwCheck*'` (affiche le mois en cours de Wavre et écrit le classeur Excel correspondant)

## Architecture

Module unique, couches séparées (UI Compose + ViewModel / domaine pur Kotlin / données Room + DataStore + KiWIS),
injection manuelle (`AppContainer`) — volontairement plus léger que la structure multi-modules du skill pour une
application de cette taille.

```
app/src/main/java/be/wallonie/intemperies/
├── domain/      DayClassifier (règles), StationMatcher, BelgianHolidays, Geo
├── data/        KiwisClient, Room (mesures, stations), SettingsRepository, WeatherRepository (synchro)
├── work/        SyncWorker (horaire)
├── export/      CalendarExporter (PDF / Excel), CalendarWorkbook, XlsxWriter (OOXML sans dépendance)
├── pdf/         CalendarPdfExporter (A4)
├── ui/          home, calendar, settings, legal (base réglementaire), theme
└── widget/      Widget Glance + écran de configuration
```
