/*
 * app.js — Amorçage : navigation par étapes, boutons globaux,
 * chargement de la session ou du projet d'exemple.
 */
(function () {
  const SM = RD.stateModule;

  function naviguer(etape) {
    document.querySelectorAll('.etape').forEach((s) => s.classList.remove('visible'));
    document.getElementById(`etape-${etape}`).classList.add('visible');
    document.querySelectorAll('#nav-etapes button').forEach((b) => {
      b.classList.toggle('actif', b.dataset.etape === etape);
    });
  }

  function initialiser() {
    // Projet initial : session sauvegardée sinon exemple pré-chargé
    SM.state.projet = SM.chargerSession() || RD.example.projetExemple();

    // Navigation libre entre les étapes du parcours guidé
    document.querySelectorAll('#nav-etapes button').forEach((b) => {
      b.addEventListener('click', () => naviguer(b.dataset.etape));
    });

    // Boutons projet
    document.getElementById('btn-export-json').addEventListener('click', () => SM.exporterJSON());
    document.getElementById('btn-export-json2').addEventListener('click', () => SM.exporterJSON());
    document.getElementById('btn-import-json').addEventListener('click', () =>
      document.getElementById('input-import-json').click());
    document.getElementById('input-import-json').addEventListener('change', (e) => {
      if (e.target.files[0]) SM.importerJSON(e.target.files[0], (msg) => alert(msg));
      e.target.value = '';
    });
    document.getElementById('btn-exemple').addEventListener('click', () => {
      if (confirm('Remplacer le projet courant par le projet d’exemple ?')) {
        SM.state.projet = RD.example.projetExemple();
        SM.marquerModifie();
        RD.editor.ajusterVue();
      }
    });
    document.getElementById('btn-nouveau').addEventListener('click', () => {
      if (confirm('Créer un nouveau projet vide ? Le projet courant sera perdu s’il n’a pas été exporté.')) {
        SM.state.projet = SM.projetVide();
        SM.marquerModifie();
      }
    });

    // Calcul
    document.getElementById('btn-calculer').addEventListener('click', () => RD.results.calculer());

    // Exports
    document.getElementById('btn-export-docx').addEventListener('click', async () => {
      const statut = document.getElementById('statut-export');
      statut.textContent = 'Génération du DOCX…';
      try { await RD.exportDocx.exporter(); statut.textContent = 'DOCX généré.'; }
      catch (e) { statut.textContent = `Erreur DOCX : ${e.message}`; console.error(e); }
    });
    document.getElementById('btn-export-pdf').addEventListener('click', async () => {
      await RD.rapport.imprimerPDF();
    });
    document.getElementById('btn-export-xlsx').addEventListener('click', () => {
      const statut = document.getElementById('statut-export');
      try { RD.exportXlsx.exporter(); statut.textContent = 'XLSX généré.'; }
      catch (e) { statut.textContent = `Erreur XLSX : ${e.message}`; console.error(e); }
    });

    // Re-rendu des formulaires quand le projet change
    SM.abonner((ev) => {
      if (ev === 'projet') {
        RD.forms.rendreTout();
        RD.results.rendreResultats();
        RD.results.rendreControles();
        const statut = document.getElementById('statut-calcul');
        if (statut && !SM.state.resultats) statut.textContent = 'Projet modifié — résultats à recalculer.';
      }
      if (ev === 'resultats') RD.forms.rendreTableTroncons();
    });

    RD.forms.rendreTout();
    RD.editor.initialiser();
    RD.editor.ajusterVue();
    RD.results.rendreControles();
    SM.sauvegarderSession();
  }

  document.addEventListener('DOMContentLoaded', initialiser);
})();
