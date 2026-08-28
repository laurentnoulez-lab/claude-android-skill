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

## 5. Temps de vidange

```
t_vidange = V / Q_sortie          (volume au-dessus de l'ajutage : Q_inf + Q_aj)
                                  (volume sous l'ajutage : Q_inf seul)
```

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
