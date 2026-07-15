/*
 * state.js — État applicatif : projet courant, résultats, sélection,
 * sauvegarde de session (localStorage) et import/export JSON.
 */
(function () {
  const CLE_STOCKAGE = 'rd-eau-projet-session';

  const state = {
    projet: null,
    resultats: null,       // sortie de RD.network.calculComplet
    diametres: null,       // Map(tronconId → {designation, de, di, force})
    controles: null,       // liste des contrôles calculés
    selection: null,       // {type: 'noeud'|'troncon', id}
    mode: 'selection',     // mode de l'éditeur
    casAffiche: 'pointe',
    abonnes: [],
  };

  /** Projet vide. */
  function projetVide() {
    return {
      version: 1,
      meta: {
        nom: 'Nouveau projet', auteur: '', bureau: '', maitreOuvrage: '',
        distributeur: '', indice: 'A', date: new Date().toISOString().slice(0, 10),
        description: '',
      },
      hypotheses: {
        nu: 1.31e-6, coeffPointe: 2.5, dureeDistribution: 10,
        debitIncendie: 60, deuxHydrants: false,
        pressionMinimale: null, // volontairement SANS défaut (exigence distributeur)
        pertesSingulieresPct: 0, rugosites: {},
      },
      alimentation: { noeudId: null, mode: 'essai', p0: null, q1: null, p1: null },
      noeuds: [], troncons: [], resultats: null,
    };
  }

  /** Abonnement aux changements ('projet', 'resultats', 'selection', 'mode'). */
  function abonner(fn) { state.abonnes.push(fn); }
  function notifier(evenement) { state.abonnes.forEach((fn) => fn(evenement)); }

  /** À appeler après toute modification du projet : les résultats deviennent obsolètes. */
  function marquerModifie() {
    state.resultats = null;
    state.diametres = null;
    state.controles = null;
    sauvegarderSession();
    notifier('projet');
  }

  function definirResultats(resultats, diametres, controles) {
    state.resultats = resultats;
    state.diametres = diametres;
    state.controles = controles;
    state.projet.resultats = null; // les résultats détaillés vivent en mémoire
    sauvegarderSession();
    notifier('resultats');
  }

  function selectionner(sel) { state.selection = sel; notifier('selection'); }
  function definirMode(mode) { state.mode = mode; state.selection = null; notifier('mode'); }

  /* ── Sauvegarde de session (navigateur) ─────────────────────────────── */
  function sauvegarderSession() {
    try {
      localStorage.setItem(CLE_STOCKAGE, JSON.stringify(state.projet));
      const el = document.getElementById('info-sauvegarde');
      if (el) el.textContent = `Session sauvegardée dans ce navigateur à ${new Date().toLocaleTimeString('fr-BE')} — pensez à exporter le fichier JSON.`;
    } catch (e) { /* stockage indisponible : l'export fichier reste possible */ }
  }

  function chargerSession() {
    try {
      const brut = localStorage.getItem(CLE_STOCKAGE);
      if (brut) return JSON.parse(brut);
    } catch (e) { /* ignorer */ }
    return null;
  }

  /* ── Export / import JSON ───────────────────────────────────────────── */
  function exporterJSON() {
    const contenu = {
      ...state.projet,
      exportePar: 'Dimensionnement réseau eau — avant-projet',
      exporteLe: new Date().toISOString(),
      resultats: state.resultats
        ? {
            horodatage: state.resultats.horodatage,
            note: 'Résultats indicatifs au moment de l’export ; recalculer après import.',
            synthese: syntheseResultats(),
          }
        : null,
    };
    const blob = new Blob([JSON.stringify(contenu, null, 2)], { type: 'application/json' });
    const nom = (state.projet.meta.nom || 'projet').replace(/[^\wàâäéèêëïîôöùûüç -]/gi, '_');
    telechargerBlob(blob, `${nom} — indice ${state.projet.meta.indice || 'A'}.json`);
  }

  function syntheseResultats() {
    if (!state.resultats) return null;
    const r = state.resultats;
    return {
      qPointe_m3h: r.casPointe.qTotal,
      qIncendieCritique_m3h: r.casIncendie ? r.casIncendie.resultat.qTotal : null,
      hydrantCritique: r.casIncendie ? r.casIncendie.combo : null,
      diametres: state.diametres
        ? Object.fromEntries([...state.diametres].map(([id, d]) => [id, { de: d.de, di: d.di, force: d.force }]))
        : null,
    };
  }

  function importerJSON(fichier, surErreur) {
    const lecteur = new FileReader();
    lecteur.onload = () => {
      try {
        const p = JSON.parse(lecteur.result);
        if (!p.noeuds || !p.troncons || !p.hypotheses) {
          throw new Error('Ce fichier ne ressemble pas à un projet de dimensionnement.');
        }
        delete p.exportePar; delete p.exporteLe;
        p.resultats = null;
        state.projet = p;
        marquerModifie();
      } catch (e) {
        surErreur(`Import impossible : ${e.message}`);
      }
    };
    lecteur.readAsText(fichier);
  }

  function telechargerBlob(blob, nomFichier) {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = nomFichier;
    document.body.appendChild(a); a.click();
    setTimeout(() => { document.body.removeChild(a); URL.revokeObjectURL(url); }, 100);
  }

  /* ── Aides sur le projet ────────────────────────────────────────────── */
  let compteur = 1;
  function idUnique(prefixe, existants) {
    let id;
    do { id = `${prefixe}${compteur++}`; } while (existants.some((e) => e.id === id));
    return id;
  }

  function ajouterNoeud(props) {
    const p = state.projet;
    const id = idUnique('N', p.noeuds);
    const n = {
      id, nom: id, x: props.x, y: props.y, cote: 0, consommation: 0,
      hydrant: !!props.hydrant, type: props.type || 'consommation',
    };
    p.noeuds.push(n);
    if (props.type === 'alimentation') p.alimentation.noeudId = id;
    marquerModifie();
    return n;
  }

  function ajouterTroncon(deId, versId) {
    const p = state.projet;
    if (deId === versId) return null;
    if (p.troncons.some((t) => (t.de === deId && t.vers === versId) || (t.de === versId && t.vers === deId))) {
      return null; // tronçon déjà existant entre ces nœuds
    }
    const id = idUnique('T', p.troncons);
    const t = {
      id, nom: id, de: deId, vers: versId, longueur: null, // longueur À SAISIR
      materiau: 'pehd-sdr17', diametreForce: null, sommeK: 0,
    };
    p.troncons.push(t);
    marquerModifie();
    return t;
  }

  function supprimerElement(sel) {
    const p = state.projet;
    if (sel.type === 'noeud') {
      p.noeuds = p.noeuds.filter((n) => n.id !== sel.id);
      p.troncons = p.troncons.filter((t) => t.de !== sel.id && t.vers !== sel.id);
      if (p.alimentation.noeudId === sel.id) p.alimentation.noeudId = null;
    } else {
      p.troncons = p.troncons.filter((t) => t.id !== sel.id);
    }
    state.selection = null;
    marquerModifie();
  }

  const S = {
    state, projetVide, abonner, notifier, marquerModifie, definirResultats,
    selectionner, definirMode, sauvegarderSession, chargerSession,
    exporterJSON, importerJSON, telechargerBlob,
    ajouterNoeud, ajouterTroncon, supprimerElement,
  };
  const g = typeof globalThis !== 'undefined' ? globalThis : window;
  g.RD = g.RD || {};
  g.RD.stateModule = S;
})();
