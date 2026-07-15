# Dimensionnement de réseau de distribution d'eau potable — avant-projet

Application web **100 % côté client** de dimensionnement hydraulique de réseaux de
distribution d'eau potable (extensions de réseau, parcs d'activités économiques,
lotissements), destinée à un ingénieur auteur de projet en Wallonie (Belgique).
Elle produit une **note de calcul d'avant-projet traçable et justifiable** — pas une
boîte noire : le distributeur (SWDE ou intercommunale) valide toujours le
dimensionnement final.

Aucun backend : aucune donnée ne quitte le navigateur.

## Installation / utilisation

Deux façons d'utiliser l'application :

1. **Fichier unique (recommandé)** : ouvrir `dist/dimensionnement-reseau-eau.html`
   par double-clic dans un navigateur récent (Chrome, Edge, Firefox). Tout est
   embarqué (styles, code, bibliothèques) ; fonctionne hors ligne.
   Pour le regénérer depuis les sources : `node build.js`.
2. **Sources** : servir le dossier par un serveur statique
   (`npx serve app-reseau-eau` ou `python3 -m http.server`) et ouvrir `index.html`.

### Tests

```bash
node --test "tests/*.test.js"
```

31 tests unitaires et d'intégration du moteur hydraulique : Swamee-Jain comparé à
Colebrook-White (résolution itérative de référence), Hardy Cross sur boucles
académiques (comparé à une résolution indépendante par dichotomie), cotes
altimétriques, validation des saisies, contrôles réglementaires, et le projet
d'exemple complet dont les résultats attendus sont :
Q dimensionnant ≈ 17,0 l/s ; en DN100 fonte boucle fermée v ≈ 1,08 m/s,
ΔH ≈ 3,4 mCE par branche de 250 m, P résiduelle ≈ 2,6 bar.

## Architecture

```
app-reseau-eau/
├── index.html               Coquille de l'application (parcours ① → ⑥)
├── build.js                 Génère dist/dimensionnement-reseau-eau.html (fichier unique)
├── css/
│   ├── style.css            Écran (bureau prioritaire, tablette ensuite)
│   └── print.css            Mise en page d'impression → PDF de la note de calcul
├── js/core/                 MOTEUR (pur JavaScript, testable sous Node)
│   ├── units.js             Conversions (1 l/s = 3,6 m³/h ; 1 bar = 10,197 mCE)
│   ├── materials.js         PVC-U PN10/PN16, PEHD SDR17/SDR11, fonte — Di RÉELS + rugosités
│   ├── hydraulics.js        Darcy-Weisbach + Swamee-Jain (λ), régime laminaire
│   ├── supply.js            Alimentation : pression fixe ou courbe d'essai débit-pression
│   ├── network.js           Graphe, ramifié (aval→amont), maillé (Hardy Cross), pressions
│   ├── checks.js            Validation des saisies + contrôles réglementaires (CM 14/10/1975…)
│   ├── sizing.js            Plus petit diamètre commercial satisfaisant les contrôles
│   └── example.js           Projet d'exemple pré-chargé
├── js/ui/                   Interface (état, formulaires, éditeur SVG, résultats)
├── js/export/               Rapport (HTML/PDF), XLSX à formules vivantes, DOCX
├── vendor/                  xlsx-js-style (SheetJS + styles), docx (embarqués)
└── tests/                   node --test
```

Les modules « core » sont à double usage : globaux `RD.*` dans le navigateur,
`module.exports` sous Node (tests). Le code métier (formules, hypothèses) est
commenté en français.

### Structure JSON du projet

```json
{
  "version": 1,
  "meta": { "nom": "", "auteur": "", "bureau": "", "maitreOuvrage": "",
            "distributeur": "", "indice": "A", "date": "", "description": "" },
  "hypotheses": { "nu": 1.31e-6, "coeffPointe": 2.5, "dureeDistribution": 10,
                  "debitIncendie": 60, "deuxHydrants": false,
                  "pressionMinimale": null, "pertesSingulieresPct": 0,
                  "rugosites": { "fonte": 0.1 } },
  "alimentation": { "noeudId": "N0", "mode": "essai|pression_fixe",
                    "p0": 4.5, "q1": 40, "p1": 3.8 },
  "noeuds":   [{ "id": "N1", "nom": "", "x": 0, "y": 0, "cote": 0,
                 "consommation": 6, "hydrant": true, "type": "consommation" }],
  "troncons": [{ "id": "T1", "nom": "", "de": "N0", "vers": "N1", "longueur": 100,
                 "materiau": "fonte", "diametreForce": 100, "sommeK": 0 }]
}
```

L'export JSON ajoute un horodatage et une synthèse des résultats ; l'import les
ignore et impose un recalcul.

## Hypothèses métier

- **Pressions relatives (manométriques)** en bar, partout (UI et rapports).
- Deux cas de charge : **pointe** (Σ conso journalière / durée de distribution × Cp)
  et **incendie + consommation moyenne** (60 m³/h = 1 000 l/min par défaut,
  CM 14/10/1975), appliqué à l'hydrant le plus défavorable — déterminé en simulant
  chaque hydrant (ou chaque paire si l'option « 2 hydrants simultanés » est active).
  **Débit dimensionnant par tronçon = max des deux cas** (enveloppe des scénarios).
- Pertes de charge : **Darcy-Weisbach**, λ par **Swamee-Jain** (approximation
  explicite de Colebrook-White, ±1 % environ sur le domaine usuel) ;
  ν = 1,31×10⁻⁶ m²/s par défaut (10 °C), modifiable. Pertes singulières par % global
  et/ou ΣK par tronçon. Les cotes altimétriques entrent dans les pressions résiduelles.
- Réseaux **ramifiés** : calcul direct aval → amont. Réseaux **maillés** :
  **Hardy Cross** itératif (λ recalculé à chaque itération, critère de convergence
  et nombre d'itérations affichés et rapportés).
- Alimentation : pression fixée, ou **courbe d'essai débit-pression**
  P(Q) = P0 − (P0 − P1)·(Q/Q1)^1,852 (exposant Hazen-Williams — règle de l'art
  d'avant-projet, documentée comme telle).
- Diamètres **intérieurs réels** : tables De/épaisseur/Di pour PVC-U (PN10 = SDR21,
  PN16 = SDR13,6 — EN 1452) et PEHD PE100 (SDR17, SDR11 — EN 12201) ; fonte
  ductile DN 60–300 avec Di ≈ DN (EN 545). Rugosités par défaut **prudentes
  « en service »** (ordres de grandeur de la littérature) : hypothèses à justifier,
  modifiables, pas des valeurs normatives.
- Contrôles : Ø ≥ 100 mm si hydrant (**bloquant**, CM 14/10/1975) ; interdistance
  hydrants ≤ 100 m (avertissement) ; vitesses (plage 0,5–1,5 m/s, < 0,3 stagnation,
  > 2 coup de bélier) ; pression minimale exigée **sans valeur par défaut**
  (à confirmer par écrit — distributeur / zone de secours) ; pressions négatives ;
  non-convergence.
- Rappel permanent (bandeau + rapports) : dimensionnement d'avant-projet —
  validation par le distributeur et consultation préalable de la zone de secours
  obligatoires (AR 06/05/1971, règlement-type art. 36 ; CM 14/10/1975 art. 1.5).

## Exports

- **DOCX** : note de calcul complète (page de garde, hypothèses, méthode,
  références, données, résultats, contrôles, schéma en image, réserves).
- **PDF** : même contenu via la mise en page d'impression dédiée
  (bouton « PDF » → boîte de dialogue d'impression → « Enregistrer en PDF »).
- **XLSX** : classeur à **formules vivantes** — onglet Tronçons avec v, Re,
  λ (Swamee-Jain) et ΔH (Darcy-Weisbach) en formules Excel référençant l'onglet
  Hypothèses ; cellules d'entrée en jaune / police bleue, formules en noir.
  Limite documentée : les débits proviennent de l'équilibrage Hardy Cross de
  l'application (Excel ne refait pas l'itération) ; ils restent modifiables pour
  les études de sensibilité.
- **JSON** : projet complet (seule sauvegarde pérenne — la sauvegarde de session
  du navigateur est un filet de sécurité, pas une archive).

## Limites (assumées, rappelées dans les rapports)

- Pas de calcul de coup de bélier (régime transitoire).
- Pas de calcul de temps de séjour / qualité d'eau (seul un avertissement de
  vitesse faible signale le risque de stagnation).
- Boucles multiples complexes, réservoirs, pompes, régulateurs : hors périmètre —
  à confier à EPANET ou au modèle hydraulique du distributeur.
- La proposition automatique de diamètre est une aide d'avant-projet ; le tableau
  des diamètres candidats permet à l'ingénieur de justifier un autre choix.
