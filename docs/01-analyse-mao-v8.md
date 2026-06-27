# MAO V8 — Analyse de l'existant (reverse-engineering)

> Document de référence **anti-régression**. Il décrit ce que fait l'application
> actuelle afin que la reconstruction (MAO Moderne) conserve **100 % des
> fonctionnalités**. Toute fonctionnalité listée ici doit avoir son équivalent
> dans la nouvelle application avant de considérer une phase comme terminée.

## 1. Identité de l'application

**MAO = Métré Assisté par Ordinateur.** Logiciel de métré pour les marchés
publics de travaux routiers en Wallonie, basé sur le cahier des charges type
**Qualiroutes** (anciennement **RW99**), édité par NRB pour le SPW.

Un *métré* est le bordereau quantitatif (bill of quantities) d'un marché : la
liste structurée des postes de travaux, leurs quantités présumées, leurs prix
unitaires, et les totaux HTVA / TVA / TTC qui en découlent.

## 2. Pile technique d'origine

| Couche | Technologie | Fichiers |
|---|---|---|
| Lanceur | exécutable PowerBuilder | `mao.exe` (126 Ko) |
| Logique applicative | **PowerBuilder 7** (p-code compilé) | `*.pbd` (main, metre, admin, formule, import, liste_std, report, report2, report_fb, pipline, outils, racine…) |
| Framework | PowerBuilder Foundation Classes (PFC) | `pfc*.pbd`, `pfe*.pbd` |
| Runtime PB | PowerBuilder VM 7.0 | `Pbvm70.dll`, `Pbdwe70.dll`, `Pbodb70.dll`… |
| Base de données | **Sybase SQL Anywhere 6** (embarquée) | `MAO.db` (7 Mo), moteur `dbeng6.exe` |
| Connexion | ODBC, DSN `MAO_V8`, user `DBA` | `mao.ini`, `pbodb70.ini` |
| Graphiques | Graphics Server 5.5 | `GSW32.EXE`, `GRAPHS32.OCX` |
| Installation | InstallShield → `c:\Qualiroutes\MAO_V8` | `setup.exe`, `data1.cab`, `data2.cab` |

Les `.pbd` sont du **p-code compilé** : il n'existe pas de source modifiable
(`.pbl` absents). La modernisation passe donc nécessairement par une
réécriture, et non par une retouche du binaire.

### Mises à jour livrées séparément

`maj_pstd_v8.002.exe` est un **script de mise à jour de la table des postes
standardisés** (`maj_pstd.apl`, PowerBuilder). Il exécute des centaines
d'`UPDATE POSTE_STD SET …` (corrections d'intitulés, prix, codes, unités). Ce
mécanisme « catalogue mis à jour indépendamment de l'application » doit être
conservé (cf. `docs/03-plan-migration.md`).

## 3. Modèle de données (cœur)

Hiérarchie d'un métré (clé technique commune : `ID_DOSSIER_ENTREPRISE`) :

```
METRE  (le bordereau)
 └── DIVISION       (grandes parties de l'ouvrage)
      └── CHAPITRE  (regroupements thématiques)
           └── POSTE  (lignes de métré : quantité × prix unitaire)
```

À côté, le **catalogue normalisé Qualiroutes** :

```
LISTE_STD ── CHAPITRE_STD ── POSTE_STD (codes type D1000, K3517, S7200…)
                                 ├── FORMULE_REFERENCE  (formules de révision de prix)
                                 ├── TYPE_DECHET / CODE_DECHET (gestion des déblais/déchets)
                                 └── unités, coefficients de conversion, TVA
```

Tables identifiées dans les binaires et scripts SQL (liste non exhaustive,
détaillée dans `docs/02-modele-donnees.md`) :

- **Métré** : `METRE`, `DIVISION`, `CHAPITRE`, `POSTE`, `INFO_POSTE`, `COMPTEUR`
- **Catalogue normalisé** : `POSTE_STD`, `CHAPITRE_STD`, `LISTE_STD`, `FAMILLE`,
  `E_REFERENCE` / `E_STD`
- **Révision de prix** : `FORMULE_REFERENCE`, `FORMULE_REVISION`,
  `INDICE_SALAIRE`, `INDICE_MATERIAUX`, `VALEUR_TP`, `PRIX_TP`, `CODE_TP_FAMILLE`,
  `COEFFICIENT_RUBRIQUE_TP`
- **Déchets** : `TYPE_DECHET`, `CODE_DECHET`, `TYPE_DECHET_POSTE`
- **TVA / paramètres** : `TVA`, `PARAMETRE`, `ENTITE`, `AGENT`
- **Statistiques / adjudications** : `ADJUDICATION`, `STAT_LOCALE`,
  `STAT_REGION`, `VALIDATION`, `ERREUR`

Colonnes confirmées par le SQL embarqué dans les `.pbd` (exemples) :

- `METRE(ID_DOSSIER_ENTREPRISE, L_INTITULE, O_VERROU, C_CCT, D_DECHET,
  O_TVA_IDENTIQUE, C_TAUX_TVA, TS_DERNIERE_MAJ, …)`
- `POSTE_STD(C_POSTE_METRE_STD, C_LISTE_STANDARDISEE, ID_CHAPITRE_STANDARDISE,
  ID_POSTE_STANDARDISE, FREF_ID, N_QO, N_QD, C_UNITES, L_INTITULE,
  TX_DESCRIPTION, L_LISTE, TY_PRIX_POSTE, O_SUP_RW03, TYDE_ID,
  N_CCONV_MIN, N_CCONV_MAX, N_CCONV_PROPOSE, …)`

## 4. Inventaire fonctionnel (reconstruit depuis menus/fenêtres)

> Source : objets `m_*` (menus), `w_*` (fenêtres) et `d_*` (DataWindows / états)
> extraits des `.pbd`. C'est la **check-list de non-régression**.

### 4.1 Gestion des métrés (module `metre`)
- Créer un métré (`w_creation_metre`), saisir l'intitulé.
- Ouvrir / gérer les métrés (`w_gestion_metre`), liste de 6 métrés récents
  (`m_metre1`…`m_metre6`).
- Structurer : ajouter division / chapitre / poste
  (`m_ajoutdivision`, `m_ajoutchapitre`, `m_ajoutposte`,
  `m_ajoutpostenormalis`).
- Éditer les éléments : `w_element_division`, `w_element_chapitre`,
  `w_element_poste`, `w_element_poste_detail_fr`, `w_element_poste_norm`.
- Dupliquer un poste (`w_element_poste_dupliquer`), copier un intitulé.
- Copier un métré : depuis la liste normalisée (`w_copie_liste_normalisee`),
  depuis un métré ouvert, depuis un métré existant (`w_copie_metre_existant`).
- Changer d'agent responsable (`w_change_agent`).
- Verrouillage du métré (`O_VERROU`) pour édition concurrente.
- Code de mesurage (`m_codedemesurage`).
- Validation RW03 (`m_validationrw03`, `w_affiche_erreur_rw03`).

### 4.2 Catalogue normalisé / postes standardisés (module `liste_std`)
- Sélection d'un poste standardisé (`w_selection_poste_std`,
  `w_selection_poste_std_jum`).
- Tableau des prix (`w_tableau_prix`), modification du prix unitaire
  (`w_modif_px_unitaire`).
- Import de données depuis un poste normalisé
  (`w_imp_donnees_poste_normalise_choix`).
- Recherche dans la liste normalisée par mot-clé
  (`w_liste_normalisee_rech_par_mot`).
- Génération des postes D9000 (`m_generationdesposted9000`).

### 4.3 Révision de prix / formules (module `formule`)
- Gestion des indices salaires (`w_gestion_indice_salaire`) et matériaux
  (`w_gestion_indice_materiaux`).
- Gestion des valeurs TP (`w_gestion_valeur_tp`), prix TP
  (`m_gestiondesprixtp`).
- Formules de révision : paramètres (`w_formule_parametre`), recherche
  (`w_rechercher_parametre`), fusion (`w_fusion_formule_revision`),
  édition (`w_rpt_formule_revision`).
- Import / export des indices (`w_import_indice`, `w_export_indice`).

### 4.4 Bordereaux & états imprimés (modules `report`, `report2`, `report_fb`)
- Bordereau (multiples variantes : `d_bordereau`, `_cp`, `_dcp`, `_dp`, `_p`,
  `_fb` selon prix de cautionnement / découpage).
- Métré estimatif (`w_metre_estimatif`, `d_metre_estimatif_cp`).
- Métré récapitulatif (`w_metre_recapitulatif`,
  `d_metre_recapitulatif_*`, nombreuses variantes RT/TDC selon options).
- Métré de travail (`w_metre_travail`).
- Liste des postes de cautionnement (`m_listedespostescautionnementsupp`,
  `w_liste_poste_cc`).
- Choix de la devise (`w_choix_devise`).
- Options d'édition (`w_options`, section `[OPTION_RECAP]` du `mao.ini` :
  inventaire, édition C/D, n° de page, montant bordereau, FR…).
- Aperçu / impression / mise en page (`m_printpreview`, `m_print`,
  `m_pagesetup`).

### 4.5 Export Excel (module `main`/`metre`)
- Export métré estimatif Excel (`m_mtrestimatifexcel`, `w_excel_estimatif`).
- Export métré récapitulatif Excel (`m_mtrrcapitulatifexcel`,
  `w_excel_recapitulatif`).
- Export générique (`w_export`, `w_export_metre`).

### 4.6 Adjudications & statistiques (module `import`)
- Import d'une adjudication depuis fichiers (`w_import_adjudication`,
  `m_chargementduneadjudicationfichiers`).
- Suppression d'une adjudication (`w_suppressionduneadjudication`).
- Chargement de l'ensemble des statistiques (`w_import_all_stat`).
- Export statistiques globales / locales / toutes
  (`m_exportdesstatistiquesglobales`, `…locales`, `w_export_all_stat`).
- Transfert vers SIGMA (`m_transfertverssigma`, section `[SIGMA]` du `mao.ini`).
- Statistiques locales / régionales (`STAT_LOCALE`, `STAT_REGION`).

### 4.7 Administration (module `admin`)
- Gestion des utilisateurs (`w_liste_utilisateur`,
  `m_gestiondesutilisateurs`), changement de mot de passe
  (`w_changement_password`).
- Gestion des entités administratives (`w_gestion_entites_admin`,
  `w_liste_entites_admin`).
- Gestion des agents administratifs (`w_liste_agents_admin`).
- Paramètres de l'application (`w_gestion_parametres_application`,
  `m_paramtresdelapplication`).
- Gestion des taux de TVA (`w_gestion_taux_tva`).

### 4.8 Migration / transfert de version
- Transfert des données depuis la version précédente (V7 → V8) au premier
  lancement (cf. `readme.txt`).
- Migration pipeline (`pipline.pbd`, `w_mig_pipline_v2`,
  `w_select_db_source`).
- Transfert des métrés en attente vers la version locale
  (`m_listedesmtrsenattentedetransf`).

### 4.9 Divers / système
- Connexion / authentification (`m_connexion`, `w_changement_password`).
- À propos (`w_a_propos`), aide / rubriques d'aide (`m_helptopics`).
- Accès Intranet SPW et CCTRW99 (`m_intranet`, `m_cctrw99`, URLs dans
  `[PARAM]` du `mao.ini`).
- Gestion d'erreurs centralisée (`w_affiche_erreur`, table `ERREUR`).

## 5. Configuration actuelle (`mao.ini`)

Points de flexibilité existants à conserver/améliorer :

- `[database]` : connexion ODBC (DBMS, ServerName, DSN, identifiants).
- `[PARAM]` : liste normalisée active (`Liste_Norm=RW99`), bornes de quantités
  (`qmin/qmax`), nombre de prix (`nbrprix=5`), nombre de termes de formule
  (`nbrterme=5`), URLs Intranet/CCTRW99, utilisateur courant.
- `[OPTION_RECAP]` : options d'édition du récapitulatif.
- `[BPRIX]` / `[SIGMA]` : connexions externes Oracle (banque de prix, SIGMA).

## 6. Limites de l'existant (cibles d'amélioration « plus pratique et flexible »)

Constatées via l'architecture d'origine :

1. **Windows only**, dépendant d'un runtime PowerBuilder 7 et d'un moteur
   Sybase 6 obsolètes (fin de support).
2. **Installation lourde** (InstallShield, DSN ODBC à configurer manuellement,
   `ServerName=TOBEDEFINED`).
3. **Configuration en fichiers `.ini`** éparpillés, édition manuelle.
4. **Multitude d'états quasi dupliqués** (`d_nr_metre_recapitul_rt_*` ×16) :
   options figées dans des DataWindows distinctes plutôt que paramétrables.
5. **Pas d'export moderne** (CSV/JSON/PDF natif), Excel via OLE fragile.
6. **Mise à jour du catalogue** via `.exe` séparés (un par révision).

Ces points constituent les axes « plus pratique et flexible » de MAO Moderne,
**sans retirer** aucune fonctionnalité du §4.
