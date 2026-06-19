# Manning–Strickler — Écoulement à surface libre

Application Android (Expo / React Native) de calcul d'écoulement à surface libre
selon la loi de **Manning–Strickler** :

```
V = K · Rh^(2/3) · J^(1/2)        Q = V · A
```

avec `K` le coefficient de Strickler (= 1/n de Manning), `Rh = A/P` le rayon
hydraulique, `J` la pente, `A` la section mouillée et `P` le périmètre mouillé.

## Fonctionnalités

**Entrées** (toutes facultatives selon la sortie voulue) :
- Pente `J` (m/m)
- Dimensions du profil (diamètre, largeur ovoïde, B/H, etc.)
- Coefficient `K` (choix d'un matériau ou saisie manuelle)
- Débit `Q` (L/s)

**Sorties :**
- **Débit critique `Qc`** (remplissage 100 %) et vitesse à pleine section `Vc`
- **Taux de remplissage** pour le débit `Q` saisi
- **Vitesse d'écoulement** au point de fonctionnement
- Indication **« en charge »** si le débit critique est dépassé
- **Pente minimale** pour le profil indiqué (n'a pas besoin de la pente saisie)
- **Diamètre / taille minimal(e)** pour la pente indiquée (n'a pas besoin de la dimension saisie)

> Le calcul s'adapte aux entrées disponibles : par exemple la pente minimale est
> donnée même sans pente saisie, et la taille minimale même sans dimension saisie.

**Profils disponibles** (liste déroulante, formulaire adaptatif) :
- Circulaire fermé — *diamètre intérieur*
- Ovoïde fermé (ovoïde normalisé à trois centres, `hauteur = 1,5 × largeur`) — *largeur*
- Caniveau rectangulaire à ciel ouvert — *base B, hauteur H*
- Caniveau trapézoïdal à ciel ouvert (trapèze régulier) — *petite base, grande base, hauteur*

**Matériaux suggérés** (K typiques, modifiables) : PVC/PE, fonte, béton (lisse /
courant / rugueux), acier, grès, amiante-ciment, maçonnerie, terre, enrobé…

**Graphique hydraulique :** deux courbes sur la même grille — `V/Vc` et `Q/Qc`
en abscisse, **taux de remplissage (%)** en ordonnée. Le point d'écoulement saisi
par l'utilisateur est tracé et la grille s'adapte automatiquement au profil et aux
données.

## Lancer en développement

```bash
cd manning-strickler-app
npm install
npx expo start        # puis scanner le QR code avec Expo Go (Android)
# ou
npm run android       # émulateur / appareil branché
```

## Télécharger l'APK prêt à installer (sans Expo)

Un workflow GitHub Actions (`.github/workflows/build-apk.yml`) compile
automatiquement un **APK autonome installable** (aucun compte Expo requis) :

1. Onglet **Actions** du dépôt → workflow **« Build Android APK »**.
2. Ouvrir le dernier run (déclenché à chaque push, ou via **Run workflow**).
3. Section **Artifacts** → télécharger **`manning-strickler-apk`** (zip).
4. Décompresser → `manning-strickler.apk`, le copier sur le téléphone et
   l'installer (activer « Installer des applications inconnues »).

## Publier sur le compte Expo `lano2889`

> Pour des raisons de sécurité, aucun identifiant n'est stocké dans ce dépôt.
> Connectez-vous vous-même :

```bash
cd manning-strickler-app
npm install
npm install -g eas-cli      # si nécessaire

eas login                   # utilisateur : lano2889
eas build:configure

# APK installable directement (recommandé pour tester sur téléphone) :
eas build -p android --profile preview

# ou App Bundle (.aab) pour le Play Store :
eas build -p android --profile production
```

EAS fournit ensuite un lien de téléchargement de l'APK / AAB. Pour une mise à jour
OTA sans rebuild : `eas update` (après `eas update:configure`).

## Structure

```
manning-strickler-app/
├── App.tsx                       # écran principal (formulaire + résultats + graphe)
├── src/
│   ├── hydraulics/
│   │   ├── profiles.ts           # géométrie des sections (table A, P, T)
│   │   ├── engine.ts             # calculs Manning–Strickler
│   │   └── materials.ts          # matériaux & coefficients K
│   └── components/
│       ├── Field.tsx             # champ numérique
│       └── FlowChart.tsx         # graphique V/Vc & Q/Qc vs remplissage
├── app.json · eas.json · tsconfig.json · babel.config.js
└── assets/                       # icône / splash
```

## Notes de calcul

- Le **débit critique** est défini ici comme le débit à remplissage 100 % (section
  pleine), conformément à la demande. Pour une conduite circulaire, le débit
  maximal réel survient vers ~94 % de remplissage (`Qmax ≈ 1,08 · Qc`).
- La géométrie est intégrée numériquement (table de 400 pas) puis interpolée, ce
  qui unifie tous les profils (circulaire, ovoïde, rectangulaire, trapézoïdal).
- L'ovoïde « normalisé » est construit avec trois rayons : invert `R/3`, calotte
  supérieure `R`, flancs `3R` (avec `R = largeur/2`), donnant `hauteur = 1,5 × largeur`.
