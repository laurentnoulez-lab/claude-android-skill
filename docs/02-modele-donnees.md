# MAO Moderne — Modèle de données

Reconstruit depuis le SQL embarqué des `.pbd`, les scripts Oracle/ASA de
migration et le catalogue système de `MAO.db`. La cible est **SQLite** (base
embarquée mono-fichier, comme `MAO.db` l'était pour Sybase).

## Conventions de modernisation

L'existant utilise des préfixes hongrois (`L_`, `N_`, `O_`, `C_`, `TS_`,
`ID_`, `TX_`). MAO Moderne adopte des noms .NET lisibles tout en **conservant
la sémantique** et en documentant la correspondance, afin de pouvoir importer
les données d'origine sans perte.

| Préfixe d'origine | Sens | Type .NET |
|---|---|---|
| `ID_` | identifiant numérique | `int`/`long` |
| `L_` | libellé court | `string` |
| `TX_` | texte long / description | `string` |
| `N_` | nombre (quantité, coef., prix) | `decimal` |
| `O_` | booléen `'O'`/`'N'` | `bool` |
| `C_` | code | `string` |
| `TS_` | timestamp | `DateTime` |

## Hiérarchie du métré

### Metre (← `METRE`)
| Champ moderne | Origine | Type | Notes |
|---|---|---|---|
| Id | ID_DOSSIER_ENTREPRISE | int (PK) | |
| Intitule | L_INTITULE | string | titre du métré |
| Verrouille | O_VERROU | bool | édition concurrente |
| CodeCct | C_CCT | string | cahier des charges type |
| GestionDechets | D_DECHET | string? | |
| TvaIdentique | O_TVA_IDENTIQUE | bool | TVA unique pour tout le métré |
| TauxTva | C_TAUX_TVA | string | code de taux (→ `Tva`) |
| ListeNormalisee | — | string | RW99 / Qualiroutes |
| DerniereMaj | TS_DERNIERE_MAJ | DateTime | |

### Division (← `DIVISION`)
| Champ | Origine | Type |
|---|---|---|
| Id | ID_DIVISION_METRE | int (PK) |
| MetreId | ID_DOSSIER_ENTREPRISE | int (FK → Metre) |
| Numero | — | int (ordre) |
| Intitule | L_INTITULE | string |
| DerniereMaj | TS_DERNIERE_MAJ | DateTime |

### Chapitre (← `CHAPITRE`)
| Champ | Origine | Type |
|---|---|---|
| Id | ID_CHAPITRE_METRE | int (PK) |
| DivisionId | ID_DIVISION_METRE | int (FK → Division) |
| MetreId | ID_DOSSIER_ENTREPRISE | int (FK) |
| Numero | — | int (ordre) |
| Intitule | L_INTITULE | string |

### Poste (← `POSTE`) — ligne de métré
| Champ | Origine | Type | Notes |
|---|---|---|---|
| Id | ID_POSTE_METRE | int (PK) | |
| ChapitreId | ID_CHAPITRE_METRE | int (FK → Chapitre) | |
| MetreId | ID_DOSSIER_ENTREPRISE | int (FK) | |
| Numero | — | int | ordre d'affichage |
| CodePosteStd | C_POSTE_METRE_STD | string? | lien vers catalogue |
| Intitule | L_INTITULE | string | |
| Description | TX_DESCRIPTION | string? | |
| Unite | C_UNITES | string | m², m³, t, pièce… |
| QuantitePresumee | N_QP | decimal | quantité du marché |
| PrixUnitaire | N_PU | decimal | prix unitaire |
| TypePrix | TY_PRIX_POSTE | string | QP (forfait), QF… |
| TauxTva | C_TAUX_TVA | string | (→ `Tva`) |
| EstNormalise | — | bool | issu du catalogue ou libre |

**Montant d'un poste** = `QuantitePresumee × PrixUnitaire` (HTVA).

## Catalogue normalisé

### PosteStd (← `POSTE_STD`)
| Champ | Origine | Type | Notes |
|---|---|---|---|
| Code | C_POSTE_METRE_STD | string (PK) | ex. `D1000`, `S7200` |
| ListeStandardisee | C_LISTE_STANDARDISEE | string | RW99 |
| ChapitreStdId | ID_CHAPITRE_STANDARDISE | int | |
| PosteStdId | ID_POSTE_STANDARDISE | int | |
| Intitule | L_INTITULE | string | |
| Description | TX_DESCRIPTION | string | |
| Liste | L_LISTE | string | sous-puces descriptives |
| Unite | C_UNITES | string | |
| FormuleRefId | FREF_ID | int? | → FormuleReference |
| QuantiteObservee | N_QO | decimal | |
| QuantiteDefaut | N_QD | decimal | |
| TypePrix | TY_PRIX_POSTE | string | |
| CoefConvMin | N_CCONV_MIN | decimal | |
| CoefConvMax | N_CCONV_MAX | decimal | |
| CoefConvPropose | N_CCONV_PROPOSE | decimal | |
| TypeDechetId | TYDE_ID | int? | → TypeDechet |
| SupprimeRw03 | O_SUP_RW03 | bool | |

### Tva (← `TVA`)
| Champ | Origine | Type |
|---|---|---|
| Code | C_TAUX_TVA | string (PK) |
| Taux | N_TAUX | decimal (ex. 0.21) |
| Libelle | L_LIBELLE | string |

### FormuleReference (← `FORMULE_REFERENCE`)
| Champ | Origine | Type |
|---|---|---|
| Id | FREF_ID | int (PK) |
| Description | FREF_DESCRIPTION | string |
| FamilleId | FREF_FAM_ID | int? |
| PartFixe | FREF_Q_FIXE | decimal |

> Tables de révision de prix complètes (`INDICE_SALAIRE`, `INDICE_MATERIAUX`,
> `VALEUR_TP`, `PRIX_TP`, `FORMULE_REVISION`…), déchets (`TYPE_DECHET`,
> `CODE_DECHET`), statistiques (`ADJUDICATION`, `STAT_LOCALE`) et administration
> (`AGENT`, `ENTITE`, `PARAMETRE`) sont modélisées progressivement par module —
> cf. `docs/03-plan-migration.md`. Le périmètre de la **phase 1** est la
> hiérarchie du métré + le catalogue `PosteStd` + `Tva`.

## Calculs de référence (à préserver à l'identique)

Pour un métré :

```
MontantPoste(HTVA)   = QuantitePresumee × PrixUnitaire
TotalChapitre(HTVA)  = Σ MontantPoste des postes du chapitre
TotalDivision(HTVA)  = Σ TotalChapitre des chapitres de la division
TotalMetre(HTVA)     = Σ TotalDivision
TVA                  = Σ (MontantPoste × TauxTva du poste)   [par taux]
TotalMetre(TTC)      = TotalMetre(HTVA) + TVA
```

Si `TvaIdentique = true`, le `TauxTva` du métré s'applique à tous les postes.
