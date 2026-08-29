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

L'application propose le volume minimal du bassin amont, calculé par la même méthode
rationnelle sur son bassin versant. La surface de ce bassin versant peut, au choix de
l'utilisateur, être comptée dans la surface raccordée qui fixe le débit de fuite
admissible (5 l/s/ha) et la conversion de l'ajutage en l/(s·ha).

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
