/*
 * build.js — Construit le fichier unique distribuable :
 *   node build.js  →  dist/dimensionnement-reseau-eau.html
 *
 * Le fichier produit est 100 % autonome (CSS, JS métier et bibliothèques
 * embarqués) : il s'ouvre par double-clic, sans serveur ni connexion.
 */
const fs = require('fs');
const path = require('path');

const RACINE = __dirname;
const SORTIE = path.join(RACINE, 'dist', 'dimensionnement-reseau-eau.html');

function lire(rel) {
  return fs.readFileSync(path.join(RACINE, rel), 'utf8');
}

/** Neutralise les séquences </script> dans du JS inliné (sans en changer le sens). */
function protegerScript(js) {
  return js.replace(/<\/script/gi, '<\\/script');
}

let html = lire('index.html');

// Inline des feuilles de style
html = html.replace(/<link rel="stylesheet" href="([^"]+)"( media="print")?>/g, (m, href, media) =>
  `<style${media || ''}>\n${lire(href)}\n</style>`);

// Inline des scripts
html = html.replace(/<script src="([^"]+)"><\/script>/g, (m, src) =>
  `<script>\n${protegerScript(lire(src))}\n</script>`);

// Bandeau de version dans le fichier généré
html = html.replace('</title>', `</title>\n<!-- Fichier autonome généré le ${new Date().toISOString()} — sources : app-reseau-eau/ -->`);

fs.mkdirSync(path.dirname(SORTIE), { recursive: true });
fs.writeFileSync(SORTIE, html);
const taille = (fs.statSync(SORTIE).size / 1024 / 1024).toFixed(2);
console.log(`OK : ${SORTIE} (${taille} Mo)`);
