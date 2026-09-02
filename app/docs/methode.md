# Méthode de calcul

## 1. Pluies statistiques

Deux sources, toutes deux issues du classeur GTI :

* **Montana** (source par défaut) : `i [mm/h] = a × t[min]^(−b)` avec trois jeux de
  coefficients par commune et par période de retour.

  | Durée `t` | Coefficients |
  |---|---|
  | `t < 25 min` | `a₁, b₁` |
  | `25 min ≤ t ≤ 6000 min` | `a₂, b₂` |
  | `t > 6000 min` | `a₃, b₃` |

  La hauteur de pluie vaut `h(t) = i(t) × t / 60` [mm]. Le modèle étant continu, le balayage
  des durées se fait de 10 min à 60 jours par pas de 5 min : la durée critique est trouvée
  exactement, sans se limiter aux 19 durées normalisées.

* **QDF** : hauteurs mesurées pour 19 durées normalisées ; interpolation logarithmique entre
  les durées tabulées.

Conversion utilisée : `1 mm/h = 10000 / 3600 l/s/ha`.

## 2. Surfaces actives

```
S_pondérée = Σ (coefficient de ruissellement × surface)      [m²]
C_moyen    = S_pondérée / S_totale
```

Coefficients du GTI : forêts 0,05 · prairies 0,15 · champs 0,25 · toitures vertes 0,40 ·
terres battues 0,50 · pavés à joints écartés 0,70 · allées pavées 0,90 · surfaces
imperméables 1,00.

## 3. Débits de sortie

* **Infiltration** — `Q_inf [l/s] = 1000 × S_infiltration [m²] × K [m/s] / coefficient de
  sécurité`, le GTI imposant un coefficient de sécurité de 2 sur la perméabilité mesurée.
* **Ajutage** — débit de l'orifice calibré, supposé constant pendant tout le remplissage
  au-dessus de l'orifice.
* **Débit de fuite admissible** — `5 l/s/ha` de surface raccordée (contrôle affiché).

## 4. Volume à maîtriser (méthode rationnelle)

Pour chaque durée `t` :

```
V_ruisselé(t) = h(t) × S_pondérée / 1000
V_évacué(t)   = Q_sortie × t × 60 / 1000
V(t)          = max( V_ruisselé(t) − V_évacué(t) , 0 )
V_dimensionnement = max sur t de V(t)
```

Le débit de sortie dépend du scénario :

| Scénario | `Q_sortie` |
|---|---|
| Temporisation seule | `Q_ajutage` |
| Dispersion seule | `Q_infiltration` |
| Temporisation et dispersion | `Q_infiltration + Q_ajutage` |
| Dispersion + temporisation au-delà d'un seuil | `Q_infiltration` sous le seuil, `Q_infiltration + Q_ajutage` au-dessus |

Pour le quatrième scénario, l'instant où le niveau atteint l'axe de l'orifice vaut

```
t_seuil = V_sous_ajutage / (Q_entrant − Q_infiltration)
V_évacué(t) = Q_infiltration × t + Q_ajutage × max(t − t_seuil, 0)
```

`Q_entrant` étant le débit ruisselé de la pluie de projet (intensité constante).

## Scénario à orifice surélevé

Le quatrième scénario — dispersion seule, avec temporisation au-delà d'un seuil — se
règle par le **volume situé sous l'axe de l'ajutage**. Sous ce volume, seule
l'infiltration évacue ; au-dessus, l'ajutage s'y ajoute :

Trois régimes se succèdent selon l'intensité de l'averse :

```
q_net = Q_entrant − Q_infiltration          (remplissage sous l'axe)

q_net <= 0                  →  rien ne s'accumule
t <= t_seuil                →  V = q_net × t                 (l'axe n'est pas atteint)
t >  t_seuil, q_haut > 0    →  V = V_sous + q_haut × (t − t_seuil)
t >  t_seuil, q_haut <= 0   →  V = V_sous                     (le niveau se tient sur l'axe)

avec  t_seuil = V_sous_ajutage / q_net   et   q_haut = Q_entrant − Q_infiltration − Q_ajutage
```

Le dernier régime est celui d'une averse longue et faible : le niveau atteint l'axe, mais
l'ajutage évacue plus que l'apport, si bien que le niveau s'y stabilise. La formule
précédente y faisait débiter l'ajutage **sous son propre axe** et annonçait 0 m³ là où le
bassin garde tout son volume mort — d'où un décrochement puis un ressaut sur la courbe.

Ce volume s'encode aussi bien dans l'onglet **Dimensionnement** que dans l'onglet
**Bassin** : c'est la même grandeur, celle de l'ouvrage. Laissé à zéro, le scénario se
confond exactement avec « temporisation et dispersion » — l'application le signale
plutôt que d'afficher deux résultats identiques sans explication.

Le temps de vidange en tient compte : le volume situé sous l'axe ne peut plus partir que
par infiltration, ce qui l'allonge sensiblement (et le rend infini sans infiltration).

## Conformité à la fiche de calcul du GTI

Le moteur reproduit la fiche officielle du Service public de Wallonie, cellule par
cellule (feuille « Pluie », colonnes A à K) :

| Fiche GTI | Formule | Application |
|---|---|---|
| `Pluie!A`, `Pluie!B` | `SI(t<25 ; a1 ; SI(t<=6000 ; a2 ; a3))` | `intensite_montana` |
| `Pluie!D` | `i = a × t^(−b)` | idem |
| `Pluie!E` | `h = i × t / 60` | `hauteur_montana` |
| `Pluie!F` | `V_in = h × S_pondérée / 1000` | `volume_a_maitriser` |
| `Pluie!G`, `Pluie!J` | `V_out = Q × t × 60 / 1000` | idem |
| `Pluie!H`, `Pluie!K` | `V = MAX(V_in − V_out ; 0)` | idem |
| `Calcul!B33`, `F33` | `MAX` sur les 17 280 durées | idem |
| `Infiltration seule!B36` | `Q = 1000 × S × K / 2` | `debit_infiltration_ls` |
| `Infiltration et rejet!B38` | `Q_rejet = 5 l/s/ha × surface raccordée` | `debit_fuite_admissible_ls` |
| `Calcul!F27` | `Q_total = Q_rejet + Q_infiltration` | scénario « temporisation et dispersion » |

La grille de durées est celle de la fiche : **17 280 valeurs, de 10 à 86 405 minutes
par pas de 5**. Le dernier point compte : lorsque le débit de sortie est très faible,
le volume croît encore en fin de grille et c'est lui qui donne le maximum.

Vérification : les 6 756 couples (commune, récurrence) de coefficients de Montana sont
identiques à ceux du classeur ; 960 comparaisons de dimensionnement (40 communes
tirées au sort × 12 récurrences × 2 scénarios) concordent à 10⁻¹² m³ ; et les 17 280
intensités mises en cache par Excel dans le classeur sont reproduites à 10⁻¹⁴ mm/h.
Les tests correspondants sont dans `app/tests/test_gti.py`, avec un jeu de cas figé
(`donnees_gti.json`) rejoué même en l'absence du classeur.

## Sources de pluie et durées balayées

* **Montana** est une formule continue : le balayage des durées est fin (pas de 5 min,
  de 10 min à 60 jours) et la durée critique peut tomber sur n'importe quelle valeur.
* **QDF** ne fournit que 19 durées normalisées mesurées. Le balayage s'y limite : une
  durée critique de 12 h 05 n'existe pas dans le GTI. L'interpolation logarithmique
  reste utilisée pour simuler une durée quelconque choisie par l'utilisateur.

Aux durées tabulées, Montana s'écarte des mesures QDF de −0,2 % en médiane (3 306
comparaisons, communes wallonnes) : les deux sources sont cohérentes, mais un écart
local de quelques pour cent suffit à décaler le volume retenu.

## Bassin d'orage amont

Un bassin d'orage situé en amont peut se déverser dans l'ouvrage étudié. Il reçoit la
même pluie de projet sur son propre bassin versant (`S_amont × C_amont`), la tamponne
dans son volume de temporisation, puis restitue :

* son **débit d'ajutage**, qui arrive dans l'ouvrage aval ;
* son **débit d'infiltration**, qui est perdu pour l'aval ;
* son **trop-plein**, s'il est sous-dimensionné : le surplus traverse alors sans être
  laminé, ce qui aggrave nettement la pointe en aval.

Le déversement **ne s'arrête pas avec la pluie** : un bassin amont rempli continue de se
vider dans l'ouvrage aval pendant des heures, voire des jours. L'horizon de simulation
est étendu pour couvrir tout l'apport, et la pointe en aval peut donc survenir bien après
la fin de l'averse — c'est le cas dès que l'apport amont dépasse la capacité d'évacuation
de l'ouvrage aval.

Les graphiques de débits décomposent alors l'apport en deux courbes, le **ruissellement
direct** (qui rejoint l'ouvrage sans transiter par l'amont) et l'**apport du bassin
amont**, la somme restant tracée en pointillé. On y lit d'un coup d'œil ce que le total
masquait :

| | pendant l'averse | après l'averse |
|---|---|---|
| ruissellement direct | 9,0 l/s | 0 |
| apport amont (bassin suffisant) | 1,0 l/s | 1,0 l/s pendant 21 700 min |
| apport amont (bassin qui surverse) | 121,5 l/s | 1,0 l/s |

La surverse du bassin amont se voit ainsi comme un saut brutal du débit entrant, à
l'instant où il se remplit.

Le déversement ne se prolonge pas toujours : si l'ajutage du bassin amont évacue au fur
et à mesure ce que son bassin versant lui apporte, il ne stocke rien et son déversement
s'arrête avec la pluie. Les rapports le disent explicitement plutôt que d'annoncer un
déversement prolongé dans tous les cas.

L'application propose le volume minimal du bassin amont, calculé par la même méthode
rationnelle sur son bassin versant.

### Le dimensionnement intègre l'apport amont

L'apport amont **entre dans le volume à mettre en œuvre**. Sans cela le tableau des
scénarios et la vérification de l'ouvrage se contredisaient : un rapport annonçait
988,4 m³ de temporisation puis déclarait 441,4 m³ de débordement dans l'ouvrage de
1 174,5 m³ encodé au-dessus de cette valeur.

Cet apport varie dans le temps — il se poursuit après l'averse et bondit si le bassin
amont surverse — donc aucune formule fermée ne le décrit. Le balayage des durées passe
alors par l'**intégrateur exact** de la simulation (`simulation.pic_volume_m3`), qui
découpe l'événement aux instants où l'apport change de palier et où le niveau franchit
un seuil. Le résultat est indépendant du pas d'échantillonnage.

Pour rester utilisable sur téléphone, le balayage se fait en deux passes : une grille
dégrossie d'environ 200 durées, puis un affinage autour du maximum. Sur les 17 280 durées
de la grille GTI, la valeur retenue est identique à celle d'un balayage exhaustif (test
`test_le_balayage_en_deux_passes_retrouve_le_maximum_absolu`), pour environ 40 ms par
projet.

La même règle sert partout — tableau des scénarios, courbe « volume à maîtriser =
f(durée) », temps de vidange et minima de la limite des 48 h, table QDF d'acceptation,
simulation — via `hydro.volume_de_dimensionnement`. Un bassin amont allonge donc aussi la
surface d'infiltration et l'ajutage minimaux : sur le cas ci-dessus, 53,6 m² → 110,3 m²
et 7,31 l/s → 10,99 l/s.

Le classeur Excel fait exception : ses formules vives n'appliquent la méthode rationnelle
qu'au bassin versant du projet, une cellule ne pouvant pas reproduire une intégration pas
à pas. La feuille « Scénarios » le signale et donne, sur une ligne séparée, le volume à
maîtriser apport amont compris.

### Compter le bassin versant amont dans l'ajutage

C'est **l'unité de saisie qui décide** laquelle des deux valeurs fait foi :

* **encodé en l/(s·ha)** — le débit absolu se calcule seul sur la surface incidente
  totale (non pondérée), bassin versant amont compris si la case est cochée. La case
  l/s affiche le résultat.

  ```
  Q_ajutage [l/s] = q_spécifique × (S_incidente_totale + S_bassin_versant_amont) / 10000
  ```

* **encodé en l/s** — le débit est fixé en valeur absolue : c'est une contrainte de
  rejet, indépendante de la surface. La case l/(s·ha) n'affiche plus qu'un équivalent.

Exemple, 0,5 ha de surfaces propres et 10 000 m² de bassin versant amont :

| Saisie | Case décochée | Case cochée |
|---|---|---|
| 5 l/(s·ha) | 2,500 l/s | **7,500 l/s** — les 5 l/s de l'hectare amont s'ajoutent |
| 2,5 l/s | 2,500 l/s | 2,500 l/s — inchangé, l'équivalent affiché tombe à 1,667 l/(s·ha) |

En mode spécifique, le débit se recalcule aussi dès qu'une surface incidente change.
Le débit de fuite admissible (5 l/s/ha) suit la surface raccordée dans les deux cas.

## Intégration de la simulation

La simulation n'est pas échantillonnée : entre deux seuils, les débits sont constants
et le volume évolue linéairement. L'intervalle est découpé aux instants exacts où
l'ajutage démarre ou s'arrête, où le bassin se vide et où il atteint le trop-plein.
Le volume maximal simulé est donc rigoureusement égal au volume annoncé par le
dimensionnement, indépendamment de la finesse d'affichage.

## 5. Temps de vidange (après la pluie)

Le temps annoncé est celui qui sépare **la fin de la pluie** du retour à un
ouvrage vide. Pour une pluie de projet à intensité constante, le volume stocké
croît tant que le débit entrant dépasse le débit de sortie : la pointe est donc
atteinte à la fin de l'averse, et la vidange part de cette pointe.

```
t_vidange = V_pointe / Q_sortie   (volume au-dessus de l'ajutage : Q_inf + Q_aj)
                                  (volume sous l'ajutage : Q_inf seul)
```

Ce calcul est vérifié par des tests qui comparent la valeur annoncée au temps
réellement mesuré en simulation, entre la fin de la pluie et le bassin vide,
pour les quatre scénarios (écart inférieur à 0,1 %).

Le GTI impose **48 h** au maximum (valeur modifiable dans l'application). Si le temps de
vidange est dépassé, l'application signale que la surface d'infiltration doit être
augmentée — sauf si celle-ci atteint déjà 10 % de la surface de référence, cas que le GTI
admet comme maximum raisonnable.

## 6. Valeurs minimales

* **Surface d'infiltration minimale** : plus petite surface telle que `t_vidange ≤ t_max`
  (recherche par dichotomie, le volume étant lui-même fonction de la surface).
* **Débit d'ajutage minimal** : même principe sur le débit de l'orifice.

## 7. Simulation de l'ouvrage

Pluie de projet à intensité constante (bloc) de durée `t`, pas de temps constant :

```
dV/dt = Q_entrant(t) − Q_infiltration·[V > 0] − Q_ajutage·[V > V_sous_ajutage]
```

Le débordement est comptabilisé dès que `V > V_total`. La simulation fournit le volume
stocké maximum, le taux de remplissage, le volume débordé et le temps de vidange.

La **table QDF d'acceptation** applique ce bilan à 19 durées × 12 périodes de retour et
compare le volume requis à la capacité de l'ouvrage : vert (absorbé), orange (plus de 95 %
de la capacité), rouge (débordement).

## 8. Ajutage — Torricelli

Orifice en paroi mince, charge `h` mesurée entre l'axe de l'orifice et le trop-plein :

```
Q = Cd × A × √(2 g h)        →      A = Q / (Cd × √(2 g h))      d = √(4A/π)
```

`Cd = 0,60` par défaut (paroi mince). L'application propose le diamètre commercial
immédiatement inférieur, afin de ne pas dépasser le débit de fuite autorisé, et trace la
loi `Q = f(h)` réelle en regard de l'hypothèse de débit constant.
