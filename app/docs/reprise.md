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
| `build-android.yml` | `HydroBassinPlus-<version>.apk` |
| `build-windows.yml` | `HydroBassinPlus-Setup-<version>.exe` (Inno Setup 6) |
| `tests.yml` | suite de tests |
| `captures.yml` | copies d'écran des dix onglets sur la branche `ui-captures` |

Pour livrer une nouvelle version : mettre à jour le numéro aux cinq endroits (cf. invariant
15), pousser, puis vérifier que le `head_sha` des artefacts correspond bien au commit.

**L'identité du paquet n'est pas le numéro de version.** HydroBassin+ s'installe à côté de
la 2.0.0 parce qu'il porte un identifiant d'application distinct —
`be.hydrobassin.hydrobassinplus` sur Android, un `AppId` Inno Setup propre sur Windows.
Reprendre ceux de la 2.0.0 ferait d'une installation une mise à jour, qui écraserait
l'application précédente et les projets qu'elle garde. Sur Android l'identifiant se
construit de deux façons — `--org` + `--project`, ou `--bundle-id` — réglées toutes deux
sur la même valeur, de sorte qu'aucune des deux voies ne puisse ramener l'ancienne.
`tests/test_version.py` refuse que l'identifiant de la 2.0.0 réapparaisse, commentaires
exceptés.

## 7. Conseils de reprise

- Toute nouvelle fonctionnalité touchant l'hydrologie doit être **confrontée aux modèles de
  référence**, pas seulement testée contre elle-même.
- Les rapports (PDF, DOCX, XLSX) sont trois sorties du même `dossier.construire` : une
  grandeur ajoutée doit apparaître dans les trois, ou être justifiée si elle n'y est pas.
- **Le dossier porte sur l'étude entière.** `Dossier.par_ouvrage()` rend un dossier
  complet par bassin d'orage — même construction, `fiche` désignant l'ouvrage décrit — et
  `pdf_report` comme `docx_report` en font un chapitre chacun. Les deux modules partagent
  le même découpage (`_section_versants` / `_w_versants`, `_donnees`, `_pluie`, `_ouvrage`)
  et le même jeu de fonctions de titrage passé en paramètre : dans un chapitre d'ouvrage,
  une section devient une sous-section et une sous-section un simple intertitre. Ce qui est
  commun au réseau — contraintes, pluie de projet, synthèse — ne s'écrit qu'une fois. Le
  piège est de dimensionner un ouvrage depuis le dossier courant plutôt que depuis le
  sous-dossier du chapitre : le rapport n'annonçait alors qu'un seul bassin.
- **Le classeur Excel est vivant, et il l'est par ouvrage.** Chaque bassin a sa ligne sur
  la feuille `Ouvrages` et ses feuilles `Pluie n` / `Scénarios n`, dont les formules
  pointent vers *ses* cellules (`_Ancrage`). Trois pièges s'y cachent, chacun rencontré :
  une chaîne de formule sans `f` devant laisse partir `={ancrage.q_ajutage}` tel quel
  (un test refuse toute accolade dans une formule) ; un nom de feuille qualifie la **plage**
  et non la fonction (`SUM('F'!A1:A9)`, jamais `'F'!SUM(...)`) ; et les noms de feuille sont
  numérotés, car 31 caractères tronquent « Bassin d'orage de la voirie » en un intitulé
  ambigu et l'apostrophe demanderait un doublement que les tableurs relisent diversement.
  Restent figées, dans des cellules modifiables signalées en orange, les seules grandeurs
  qui demandent une intégration pas à pas : l'apport amont et, sur un ouvrage qui en
  reçoit, ses minima ; les minima du scénario à seuil, dont l'ajutage surélevé ne s'ouvre
  qu'à un instant fonction de l'infiltration ; la simulation du réseau.
- **Le classeur et le dossier décrivent le SYSTÈME.** Le réflexe mono-bassin se cache
  encore par endroits : la feuille « Projet » annonçait « Ouvrage détaillé par ce
  classeur », l'entête du PDF et du Word aussi. `_projet_systeme` remplace
  `_projet_ouvrage` dès qu'il y a plusieurs ouvrages, et seuls les quatre noms définis
  réellement communs au projet subsistent alors ; en mode réseau les onze autres ne
  servaient plus (un test le vérifie).
- **Une cellule à valeurs fermées porte une liste déroulante** (`_liste`), et une cellule
  de saisie numérique un intervalle (`_borne`). Sans cela, la période de retour acceptait
  « 35 ans » et renvoyait un #N/A muet dans chaque feuille de pluie.
- **Ne corrigez pas seulement la cellule qu'on vous signale.** Chaque fois qu'une valeur
  figée a été signalée, l'inventaire complet en a révélé d'autres, plus graves : la
  période de retour ne commandait rien (coefficients de Montana figés sur la récurrence
  de l'application), toute la colonne des intensités était en dur en mode QDF, la surface
  active amont, le bloc de surfaces de la feuille « Projet », le K et le diamètre
  commercial étaient recopiés. `test_toute_cellule_figee_a_une_raison_d_etre` classe
  désormais **chaque** nombre écrit en dur — source GTI, abaque, constante, grille de
  durées, saisie, intégration pas à pas — et échoue sur ce qui n'entre dans aucune case.
- **Un minimum se calcule, il ne se recopie pas.** Le moteur cherche la surface
  d'infiltration et l'ajutage minimaux par dichotomie, ce qui ne se met pas en cellule —
  mais la condition qu'ils satisfont, elle, s'inverse. `V x 1000 / Q / 3600 <= T` avec
  `V = h x S / 1000 - Q x t x 60 / 1000` donne `Q >= (h x S / 1000) / (3,6 T + 0,06 t)` :
  le débit sort du maximum, et le minimum cherché est le maximum d'une colonne (colonne Q
  de `Pluie n`). Vérifié contre le moteur sur 200 projets tirés au sort, écart maximal
  0,1 % — la tolérance de la dichotomie. L'inversion suppose que tout le volume se vidange
  au même débit : elle ne vaut donc ni pour le scénario à seuil, ni avec un apport amont.
- Les nombres s'affichent à la française (virgule) à l'écran comme dans les rapports ;
  `formats.fr` s'en charge, un test vérifie l'absence de point décimal dans le texte écrit.
- L'interface se teste sans serveur graphique (`tests/test_ui.py` construit chaque vue et
  parcourt l'arbre de contrôles Flet) : une erreur d'API Flet est détectée là, de même
  qu'un recouvrement dans le schéma du réseau. **Cela ne remplace pas le rendu réel** :
  le workflow `captures.yml` publie sur la branche `ui-captures` les dix onglets aux trois
  formats, et c'est là qu'on voit ce qu'un arbre de contrôles ne dit pas. À relire avant
  toute livraison. `tools/controle_captures.py`, que le workflow exécute ensuite, dit par
  où commencer : il mesure dans chaque image le plus grand pavé d'une seule couleur, signe
  d'un contrôle mal posé. Sur la 3.0.0 les captures saines plafonnent à 21 %, à une
  exception près qui n'est pas un défaut — l'abaque des diamètres, un tableau à trois
  colonnes sur une carte de tablette, atteint 51 %. Le journal de la console, publié à
  côté des images, est à lire aussi : c'est lui qui a révélé que le regroupeur de
  recalculs n'armait jamais son minuteur sous Pyodide.
- La recherche des minima coûte deux dichotomies par ouvrage. Au-delà de six ouvrages, elle
  n'est plus automatique : l'utilisateur la demande (`EtatApplication.minima_disponibles`).
  Les rapports, eux, la calculent toujours.
- La seconde fenêtre ne peut pas s'éprouver ici (ni serveur graphique, ni `flet_desktop`) :
  `ui/fenetres.py` est écrit pour se déclarer indisponible plutôt que d'échouer, et les
  tests vérifient le partage d'état et le désabonnement, pas l'ouverture réelle.
