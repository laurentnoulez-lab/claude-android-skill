/*
 * Application de bureau « Gabarit Tranchées 2.0 » (Electron).
 *
 * Identité distincte de la version 1 (appId, nom de produit, dossier de données) :
 * les deux versions s'installent et fonctionnent côte à côte.
 *
 * Coque native autour de l'application web autonome (app/index.html) :
 *  - 100 % hors-ligne ;
 *  - mémoire des projets : le projet en cours est conservé automatiquement
 *    (localStorage, persisté dans le profil utilisateur de l'application),
 *    et les projets s'exportent/s'importent en fichiers .json ;
 *  - les exports (Excel, Word, JSON) passent par la boîte de dialogue
 *    « Enregistrer sous » native de Windows.
 */
const { app, BrowserWindow, Menu, dialog, shell } = require('electron');
const path = require('path');

let win = null;

function createWindow() {
  win = new BrowserWindow({
    width: 1360,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    icon: path.join(__dirname, 'build', 'icon.ico'),
    autoHideMenuBar: false,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      spellcheck: false
    }
  });

  // Les téléchargements déclenchés par l'application web (blob:) ouvrent la
  // boîte « Enregistrer sous » avec le nom de fichier proposé.
  win.webContents.session.on('will-download', (event, item) => {
    // Comportement par défaut d'Electron : boîte de dialogue d'enregistrement.
    // On garde le nom suggéré par l'application (projet.xlsx, projet.docx, …).
  });

  // Les liens externes éventuels s'ouvrent dans le navigateur, pas dans l'app.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('http')) shell.openExternal(url);
    return { action: 'deny' };
  });

  win.loadFile(path.join(__dirname, 'app', 'index.html'));
}

function buildMenu() {
  const template = [
    {
      label: 'Fichier',
      submenu: [
        { role: 'reload', label: 'Recharger l\'application' },
        { type: 'separator' },
        { role: 'quit', label: 'Quitter' }
      ]
    },
    {
      label: 'Affichage',
      submenu: [
        { role: 'zoomIn', label: 'Zoom avant' },
        { role: 'zoomOut', label: 'Zoom arrière' },
        { role: 'resetZoom', label: 'Zoom normal' },
        { type: 'separator' },
        { role: 'togglefullscreen', label: 'Plein écran' }
      ]
    },
    {
      label: 'Aide',
      submenu: [
        {
          label: 'À propos',
          click: () => {
            dialog.showMessageBox(win, {
              type: 'info',
              title: 'À propos',
              message: 'Gabarit Tranchées 2.0 — version ' + app.getVersion(),
              detail: 'Calcul des largeurs, volumes et répartition des tranchées communes par impétrant.\n\n'
                + 'Le projet en cours est enregistré automatiquement. Utilisez « Exporter » pour sauvegarder '
                + 'un projet en fichier .json et « Importer… » pour le rouvrir.\n\n'
                + 'Les boutons « Générer Excel » et « Word » produisent les livrables. Cette version s\'installe à côté de la version 1 sans la remplacer.'
            });
          }
        }
      ]
    }
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

app.whenReady().then(() => {
  buildMenu();
  createWindow();
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow(); });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
