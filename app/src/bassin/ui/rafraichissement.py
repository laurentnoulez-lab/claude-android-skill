"""Regroupement des recalculs déclenchés par la saisie."""

from __future__ import annotations

import threading
from typing import Callable, Optional

#: Délai d'attente après la dernière frappe, en secondes.
DELAI_S = 0.4


class Rafraichisseur:
    """Appelle ``action`` peu après la dernière sollicitation.

    Recalculer à chaque frappe coûte trop cher — quatre scénarios, un balayage
    de 17 280 durées, et l'intégration de l'apport amont. Mais ne recalculer
    qu'à la sortie du champ laissait l'écran périmé quand le focus ne se perdait
    pas : sous Windows, cliquer dans une zone non saisissable ne déclenche pas
    ``on_blur``, et l'utilisateur lisait des résultats qui n'étaient plus ceux
    de sa saisie, sans aucun signe que quelque chose clochait.

    Les deux voies coexistent donc : la sortie de champ recalcule tout de suite
    (:meth:`executer_maintenant`), et à défaut le minuteur rattrape.
    """

    def __init__(self, action: Callable[[], None], delai_s: float = DELAI_S,
                 minuteur: Optional[Callable[..., threading.Timer]] = None) -> None:
        self._action = action
        self._delai_s = delai_s
        self._minuteur = minuteur or threading.Timer
        self._en_attente: Optional[threading.Timer] = None
        self._verrou = threading.Lock()

    @property
    def en_attente(self) -> bool:
        """Vrai quand un recalcul est programmé et pas encore joué."""
        with self._verrou:
            return self._en_attente is not None

    def demander(self) -> None:
        """Programme un recalcul, en annulant celui qui attendait.

        Là où les fils d'exécution n'existent pas — la version web tourne sous
        Pyodide, qui refuse ``thread.start()`` — le minuteur ne peut pas être
        armé. Plutôt que de laisser l'écran périmé, le recalcul se fait alors
        tout de suite : c'est plus coûteux à la frappe, mais juste.
        """
        with self._verrou:
            if self._en_attente is not None:
                self._en_attente.cancel()
            minuteur = self._minuteur(self._delai_s, self._jouer)
            # Un minuteur en attente ne doit pas retenir la fermeture de l'application.
            try:
                minuteur.daemon = True
            except Exception:
                pass
            try:
                minuteur.start()
            except RuntimeError:
                self._en_attente = None
                immediat = True
            else:
                self._en_attente = minuteur
                immediat = False
        if immediat:
            self._jouer()

    def annuler(self) -> None:
        with self._verrou:
            if self._en_attente is not None:
                self._en_attente.cancel()
                self._en_attente = None

    def executer_maintenant(self) -> None:
        """Joue tout de suite le recalcul, qu'il ait été programmé ou non."""
        self.annuler()
        self._action()

    def _jouer(self) -> None:
        with self._verrou:
            self._en_attente = None
        # Le minuteur tourne dans son propre fil : une exception y serait perdue,
        # et l'écran resterait périmé sans que personne ne le sache.
        try:
            self._action()
        except Exception:  # pragma: no cover - filet de sécurité
            pass
