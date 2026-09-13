# Reprendre HydroBassin+ dans une autre conversation

Ce document est le point d'entrée pour poursuivre le développement ailleurs, sans repartir
de zéro ni défaire ce qui a été vérifié.

## 1. Par où commencer

| | |
|---|---|
| dépôt | `laurentnoulez-lab/claude-android-skill` |
| branche de travail | `claude/hydrobassin-multi-basin-qt40v6` |
| version | HydroBassin+ **3.0.0** |
| version de référence précédente | **2.0.0**, branche `claude/storm-basin-sizing-app-4v53u1`, commit `289a3cf` |

```bash
git fetch origin

# a) Reprendre le développement — code 3.0.0
git checkout -b <ma-branche> origin/claude/hydrobassin-multi-basin-qt40v6

# b) Revenir à la version 2.0.0, à bassin unique
git checkout 289a3cf
```

**Un commit ne bouge jamais** : c'est lui, et non la branche, qui est le point de retour
garanti.

## 2. Ce qu'est l'application

Dimensionnement de **réseaux de bassins d'orage** par la **méthode rationnelle**, à partir
des pluies statistiques du **GTI** (Région wallonne). Un code unique Python + Flet produit
l'APK Android et l'installateur Windows. Aucune dépendance native : DOCX, PDF et PNG sont
écrits en Python pur, condition d'un comportement identique sur les deux plateformes.

Lire `docs/methode.md` pour la méthode de calcul — c'est le document de fond, il explique
les formules, les hypothèses et les raisons des choix.

### Ce que la version 3 ajoute à la version 2

* plusieurs **bassins versants** nommés, chacun raccordé à un seul bassin d'orage ;
* plusieurs **bassins d'orage** nommés, raccordés entre eux ou à l'exutoire, avec le choix
  de la destination de leur **surverse** (aval ou milieu naturel) ;
* le dimensionnement de chaque ouvrage (volume minimal sans surverse, surface
  d'infiltration minimale, ajutage minimal) et le **dimensionnement en cascade** ;
* la **simulation du système complet** et une **synthèse graphique** du réseau ;
* sur le bureau, une **seconde fenêtre** sur le même projet (Ctrl+N).

Le panneau « bassin d'orage amont » a disparu de l'interface : un amont est devenu un
ouvrage comme un autre. Le mécanisme sous-jacent reste en place et reste testé, parce que
les projets 2.x s'y appuient au chargement.

## 3. Carte du code

```
app/src/
  main.py                     coquille : navigation, entête, thème, persistance, fenêtres
  bassin/
    core/
      model.py                Projet, Bassin, BassinAmont, scénarios
      reseau.py               BassinVersant, Ouvrage, Systeme, topologie, routage, cascade
      rainfall.py             pluies GTI (Montana + tables QDF), 563 communes
      hydro.py                méthode rationnelle, balayage des durées, minima
      simulation.py           intégrateur événementiel exact, table QDF d'acceptation
      orifice.py              ajutage (Torricelli)
    reports/
      schema.py               géométrie du schéma de réseau, partagée écran / PDF
      dossier.py, pdf_*, docx_*, xlsx_*, charts.py   (Python pur)
    ui/
      vues/                   dix onglets
      composants/selecteur.py barre de choix de l'ouvrage étudié
      schema_flet.py          rendu du schéma en contrôles Flet
      fenetres.py             seconde fenêtre sur le bureau
      rafraichissement.py     regroupement des recalculs après saisie
      theme.py, state.py      style, état applicatif
app/tests/                    dont test_reference.py et test_reseau.py, deux modèles
                              de référence indépendants
```

## 4. Invariants à ne pas casser

Ces règles ont chacune coûté un défaut trouvé en production. Les enfreindre est une
régression, même si les tests passent encore.

1. **Une seule règle de dimensionnement.** `hydro.volume_de_dimensionnement` décide du
   volume à maîtriser. Le tableau des scénarios, la courbe volume = f(durée), le temps de
   vidange, les minima des 48 h, la table QDF et la simulation passent tous par elle —
   **pour chaque ouvrage du réseau comme pour un bassin isolé**. Deux chemins de calcul
   finissent toujours par diverger.

2. **Le réseau se branche, il ne se recalcule pas.** Un ouvrage du réseau est décrit par un
   `Projet` complet (`Ouvrage.etude`) dont l'apport amont est branché par
   `Projet.brancher_apport`. Tout le moteur du bassin isolé s'y applique tel quel. Ne pas
   écrire de second moteur « réseau » : un réseau à un ouvrage doit rendre au dernier bit
   ce que rendait la version 2 (`test_reseau.EquivalenceAvecLeBassinIsole`).

3. **`Systeme.synchroniser` est la mise en cohérence.** Elle reporte la pluie, les
   contraintes et l'identification dans chaque étude, relie les surfaces des bassins
   versants (les mêmes objets, pas des copies) et rebranche les apports amont. Toute
   lecture de résultat doit être précédée d'une synchronisation — `EtatApplication.invalider`
   s'en charge. Les champs qu'elle recalcule ne sont pas enregistrés dans le fichier de
   projet : les écrire deux fois, c'est les laisser diverger.

4. **Le bassin amont entre dans le dimensionnement.** Son apport varie dans le temps et se
   poursuit après l'averse ; aucune formule fermée ne le décrit. Le balayage utilise
   l'intégrateur exact (`simulation.pic_volume_m3`).

5. **Une capacité nulle ne veut pas dire la même chose partout.** `_avancer` lit
   `v_cap <= 0` comme « illimité » — convention nécessaire au balayage de l'ouvrage qu'on
   dimensionne. Pour un ouvrage **qui restitue** vers l'aval, zéro veut dire zéro : sans
   tampon il ne lamine rien. Voir `simulation.hydrogramme_sortant`.

6. **L'ajutage surélevé ne débite pas sous son axe.** Sous le seuil, seule l'infiltration
   évacue ; si l'apport dépasse l'infiltration, le niveau se stabilise sur l'axe.

7. **Le temps de vidange se mesure après la fin de la pluie**, apport amont compris. La
   formule fermée `V/Q` ne vaut que sans apport résiduel.

8. **Le volume mort n'appartient qu'au scénario à orifice surélevé.** Les trois autres
   évacuent par le fond ; y rapporter un volume sous l'axe est trompeur.

9. **L'unité de saisie de l'ajutage décide.** En `l/(s·ha)` le débit suit la surface
   raccordée (bassins versants amont compris si la case du Réseau est cochée) ; en `l/s` il
   est figé en valeur absolue et seul l'équivalent spécifique s'ajuste.

10. **Les résultats se recalculent même sans `on_blur`.** Sous Windows un clic dans une zone
    non saisissable ne le déclenche pas. Les vues s'abonnent à `etat.invalider()` et un
    regroupeur rattrape peu après la dernière frappe.

11. **Un réseau incohérent reste calculable.** Cycle, ouvrage disparu, bassin versant
    orphelin : le lien fautif est coupé, l'anomalie est **signalée** (`Systeme.anomalies`),
    et l'application continue d'afficher. Jamais de boucle infinie, jamais d'écran vide.

12. **Le schéma ne se superpose pas.** Sa géométrie est calculée une seule fois dans
    `reports/schema.py` : bassins versants au-dessus de leur ouvrage, une colonne par
    distance à l'exutoire. Les flèches des versants sont verticales, celles du réseau
    horizontales — elles ne peuvent pas se croiser. `test_ui.TestMiseEnPage` le vérifie.

13. **Trois pièges de Flet, qu'aucun parcours de l'arbre de contrôles ne voit.** Ils ont
    tous les trois été trouvés sur les captures d'écran du rendu réel, et sont désormais
    tenus par des tests (`test_ui.TestMiseEnPage`) :

    * un contrôle `expand` dans un `Row(wrap=True)` n'a pas de largeur définie — Flutter
      le rend en un aplat gris qui mange tout l'écran ;
    * une liste déroulante dont la valeur est la **chaîne vide** n'affiche rien, même si
      une option porte cette valeur : donner une valeur propre à chaque choix ;
    * un texte rendu dans une boîte de hauteur fixe occupe un peu plus que son corps :
      réserver large, sinon la dernière ligne passe sous la bordure.

    Et une règle de Python qui s'y ajoute : dans une expression conditionnelle, **la
    branche non retenue n'est pas évaluée**. Un compteur appelé dans une seule branche
    n'avance pas — c'est ainsi que le rapport PDF d'un bassin unique a numéroté deux fois
    sa section 3.

14. **Sur Android, le sélecteur de fichiers rend un URI SAF**, pas un chemin
    (`/document/primary:…`). Ne jamais y écrire avec `shutil`/`open` : vérifier avec
    `state.destination_utilisable` / `source_utilisable`.

15. **Numéro de version cohérent** entre `bassin/__init__.py`, `pyproject.toml`, le script
    Inno Setup et les deux workflows — `tests/test_version.py` le vérifie.

16. **Les fenêtres partagent un seul projet.** L'état applicatif vit au niveau du module
    `main` (`etat_partage`), pas dans `main(page)` : une seconde fenêtre est une session
    Flet de plus dans le même processus, pas une seconde application. Une fenêtre qui se
    ferme se **désabonne** ; seule la première arrête l'application.

## 5. Tests

```bash
cd app
python -m pytest tests -q                     # ~285 tests, ~30 s

# campagnes longues, à lancer avant toute compilation
HYDROBASSIN_CAMPAGNE_REFERENCE=150 python -m pytest tests/test_reference.py -q
HYDROBASSIN_CAMPAGNE_RESEAU=120    python -m pytest tests/test_reseau.py -q
HYDROBASSIN_TEST_FORMULES=1        python -m pytest tests/test_reports.py -q   # pip install formulas
```

Deux fichiers méritent une mention : `tests/test_reference.py` et `tests/test_reseau.py`
opposent au moteur des **modèles de référence indépendants** — des simulations naïves à très
petits pas écrites à partir de la physique seule, sans code partagé avec l'application. Ce
sont eux qui rattrapent la famille de défauts la plus coûteuse de ce projet : une formule
fermée juste pour le cas simple, devenue fausse quand une fonctionnalité en casse les
hypothèses. **Les étendre en même temps que le moteur**, sinon ils cessent de mordre.

`test_reseau.EquivalenceAvecLeBassinIsole` est le garde-fou anti-régression : il compare le
réseau à un ouvrage au moteur du bassin isolé, à l'égalité stricte.

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
| `captures.yml` | copies d'écran des dix onglets sur la branche `ui-captures` |

Pour livrer une nouvelle version : mettre à jour le numéro aux cinq endroits (cf. invariant
15), pousser, puis vérifier que le `head_sha` des artefacts correspond bien au commit.

## 7. Conseils de reprise

- Toute nouvelle fonctionnalité touchant l'hydrologie doit être **confrontée aux modèles de
  référence**, pas seulement testée contre elle-même.
- Les rapports (PDF, DOCX, XLSX) sont trois sorties du même `dossier.construire` : une
  grandeur ajoutée doit apparaître dans les trois, ou être justifiée si elle n'y est pas
  (le classeur Excel ne peut pas reproduire une intégration pas à pas — c'est documenté).
- Les nombres s'affichent à la française (virgule) à l'écran comme dans les rapports ;
  `formats.fr` s'en charge, un test vérifie l'absence de point décimal dans le texte écrit.
- L'interface se teste sans serveur graphique (`tests/test_ui.py` construit chaque vue et
  parcourt l'arbre de contrôles Flet) : une erreur d'API Flet est détectée là, de même
  qu'un recouvrement dans le schéma du réseau. **Cela ne remplace pas le rendu réel** :
  le workflow `captures.yml` publie sur la branche `ui-captures` les dix onglets aux trois
  formats, et c'est là qu'on voit ce qu'un arbre de contrôles ne dit pas. À relire avant
  toute livraison.
- La recherche des minima coûte deux dichotomies par ouvrage. Au-delà de six ouvrages, elle
  n'est plus automatique : l'utilisateur la demande (`EtatApplication.minima_disponibles`).
  Les rapports, eux, la calculent toujours.
- La seconde fenêtre ne peut pas s'éprouver ici (ni serveur graphique, ni `flet_desktop`) :
  `ui/fenetres.py` est écrit pour se déclarer indisponible plutôt que d'échouer, et les
  tests vérifient le partage d'état et le désabonnement, pas l'ouverture réelle.
