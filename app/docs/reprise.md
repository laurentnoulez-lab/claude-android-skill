# Reprendre HydroBassin dans une autre conversation

Ce document est le point d'entrée pour poursuivre le développement ailleurs, sans repartir
de zéro ni défaire ce qui a été vérifié.

## 1. Par où commencer

| | |
|---|---|
| dépôt | `laurentnoulez-lab/claude-android-skill` |
| branche de travail | `claude/storm-basin-sizing-app-4v53u1` |
| pull request | #13 |
| version | HydroBassin **2.0.0** |
| **commit compilé et vérifié** | **`289a3cfd614b8d2380eb908bf98b50d52b5cafbe`** (`289a3cf`) |

Deux points d'entrée, selon le besoin :

```bash
git fetch origin

# a) Reprendre le développement — code 2.0.0 + ce document
git checkout -b <ma-branche> origin/claude/storm-basin-sizing-app-4v53u1

# b) Revenir exactement à la version dont les binaires ont été produits
git checkout 289a3cf
```

La différence entre les deux se limite à ce fichier de documentation : le code de calcul et
d'interface est identique. Les artefacts `HydroBassin-2.0.0.apk` et
`HydroBassin-Setup-2.0.0.exe` sont attachés aux workflows de la PR #13 sur `289a3cf`.

**Un commit ne bouge jamais** : c'est `289a3cf`, et non la branche, qui est le point de
retour garanti. Y revenir à tout moment : `git checkout 289a3cf`.

État vérifié à ce commit : 223 tests et 341 sous-tests ; campagne contre le modèle de
référence indépendant sur 150 configurations (1 428 sous-tests, écart maximal 0,04 % sur
les volumes) ; recalcul des formules du classeur Excel comparé au moteur.

## 2. Ce qu'est l'application

Dimensionnement de bassins d'orage par la **méthode rationnelle**, à partir des pluies
statistiques du **GTI** (Région wallonne). Un code unique Python + Flet produit l'APK
Android et l'installateur Windows. Aucune dépendance native : DOCX, PDF et PNG sont écrits
en Python pur, condition d'un comportement identique sur les deux plateformes.

Lire `docs/methode.md` pour la méthode de calcul — c'est le document de fond, il explique
les formules, les hypothèses et les raisons des choix.

## 3. Carte du code

```
app/src/
  main.py                     coquille : navigation, entête, thème, persistance
  bassin/
    core/
      model.py                Projet, Bassin, BassinAmont, scénarios
      rainfall.py             pluies GTI (Montana + tables QDF), 563 communes
      hydro.py                méthode rationnelle, balayage des durées, minima
      simulation.py           intégrateur événementiel exact, table QDF d'acceptation
      orifice.py              ajutage (Torricelli)
    reports/                  dossier, PDF, DOCX, XLSX, graphiques (Python pur)
    ui/
      vues/                   sept onglets
      composants/             panneaux partagés entre onglets (bassin amont)
      rafraichissement.py     regroupement des recalculs après saisie
      theme.py, state.py      style, état applicatif
app/tests/                    223 tests
```

## 4. Invariants à ne pas casser

Ces règles ont chacune coûté un défaut trouvé en production. Les enfreindre est une
régression, même si les tests passent encore.

1. **Une seule règle de dimensionnement.** `hydro.volume_de_dimensionnement` décide du
   volume à maîtriser. Le tableau des scénarios, la courbe volume = f(durée), le temps de
   vidange, les minima des 48 h, la table QDF et la simulation passent tous par elle. Deux
   chemins de calcul finissent toujours par diverger.

2. **Le bassin amont entre dans le dimensionnement.** Son apport varie dans le temps et se
   poursuit après l'averse ; aucune formule fermée ne le décrit. Le balayage utilise
   l'intégrateur exact (`simulation.pic_volume_m3`).

3. **Une capacité nulle ne veut pas dire la même chose partout.** `_avancer` lit
   `v_cap <= 0` comme « illimité » — convention nécessaire au balayage de l'ouvrage aval.
   Pour le **bassin amont**, zéro veut dire zéro : sans tampon il ne lamine rien.
   Voir `_hydrogramme_amont`.

4. **L'ajutage surélevé ne débite pas sous son axe.** Sous le seuil, seule l'infiltration
   évacue ; si l'apport dépasse l'infiltration, le niveau se stabilise sur l'axe.

5. **Le temps de vidange se mesure après la fin de la pluie**, apport amont compris. La
   formule fermée `V/Q` ne vaut que sans apport résiduel.

6. **Le volume mort n'appartient qu'au scénario à orifice surélevé.** Les trois autres
   évacuent par le fond ; y rapporter un volume sous l'axe est trompeur.

7. **L'unité de saisie de l'ajutage décide.** En `l/(s·ha)` le débit suit la surface
   raccordée (bassin versant amont compris si la case est cochée) ; en `l/s` il est figé en
   valeur absolue et seul l'équivalent spécifique s'ajuste.

8. **Les résultats se recalculent même sans `on_blur`.** Sous Windows un clic dans une zone
   non saisissable ne le déclenche pas. Les vues s'abonnent à `etat.invalider()` et un
   regroupeur rattrape peu après la dernière frappe.

9. **Sur Android, le sélecteur de fichiers rend un URI SAF**, pas un chemin
   (`/document/primary:…`). Ne jamais y écrire avec `shutil`/`open` : vérifier avec
   `state.destination_utilisable` / `source_utilisable`.

10. **Numéro de version cohérent** entre `bassin/__init__.py`, `pyproject.toml`, le script
    Inno Setup et les deux workflows — `tests/test_version.py` le vérifie.

## 5. Tests

```bash
cd app
python -m pytest tests -q                     # 223 tests, ~35 s

# campagnes longues, à lancer avant toute compilation
HYDROBASSIN_CAMPAGNE_REFERENCE=150 python -m pytest tests/test_reference.py -q
HYDROBASSIN_TEST_FORMULES=1        python -m pytest tests/test_reports.py -q
```

`tests/test_reference.py` mérite une mention : il oppose au moteur un **modèle de référence
indépendant**, une simulation naïve à très petits pas écrite à partir de la physique seule,
sans code partagé avec l'application. C'est lui qui rattrape la famille de défauts la plus
coûteuse de ce projet — une formule fermée juste pour le cas simple, devenue fausse quand
une fonctionnalité en casse les hypothèses. **Étendre ce fichier en même temps que le
moteur**, sinon il cesse de mordre.

La suite est écrite pour mordre : une modification qui casse un invariant doit faire
échouer un test. En cas de doute sur un nouveau test, le vérifier en réintroduisant
volontairement le défaut qu'il est censé attraper.

## 6. Compilation

Les binaires ne se compilent pas dans l'environnement de développement (ni SDK Android, ni
Windows) : ce sont les workflows GitHub Actions qui les produisent, sur le commit poussé.

| workflow | livrable |
|---|---|
| `build-android.yml` | `HydroBassin-<version>.apk` |
| `build-windows.yml` | `HydroBassin-Setup-<version>.exe` (Inno Setup 6) |
| `tests.yml` | suite de tests |
| `captures.yml` | copies d'écran des sept onglets sur la branche `ui-captures` |

Pour livrer une nouvelle version : mettre à jour le numéro aux cinq endroits (cf. invariant
10), pousser, puis vérifier que le `head_sha` des artefacts correspond bien au commit.

## 7. Conseils de reprise

- Toute nouvelle fonctionnalité touchant l'hydrologie doit être **confrontée au modèle de
  référence**, pas seulement testée contre elle-même.
- Les rapports (PDF, DOCX, XLSX) sont trois sorties du même `dossier.construire` : une
  grandeur ajoutée doit apparaître dans les trois, ou être justifiée si elle n'y est pas
  (le classeur Excel ne peut pas reproduire une intégration pas à pas — c'est documenté).
- Les nombres s'affichent à la française (virgule) à l'écran comme dans les rapports ;
  `formats.fr` s'en charge, un test vérifie l'absence de point décimal dans le texte écrit.
- L'interface se teste sans serveur graphique (`tests/test_ui.py` construit chaque vue et
  parcourt l'arbre de contrôles Flet) : une erreur d'API Flet est détectée là.
