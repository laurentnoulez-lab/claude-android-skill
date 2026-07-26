/**
 * PDF report export: renders an HTML report (inputs, results, hydraulic
 * curves with the operating point(s)) through expo-print, then opens the
 * system share sheet so the user can save or send the file.
 */

import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { CurvePoint } from '../hydraulics/engine';
import { ExportData, dimensionRows, fnum } from './exportData';
import { APP_VERSION } from '../theme';

const V_COLOR = '#e07a3f';
const Q_COLOR = '#2f7dd1';
const POINT_COLOR = '#d12f4f';

export async function exportPdf(data: ExportData): Promise<void> {
  const html = buildHtml(data);
  const { uri } = await Print.printToFileAsync({ html });
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(uri, {
      mimeType: 'application/pdf',
      dialogTitle: 'Exporter le rapport PDF',
      UTI: 'com.adobe.pdf',
    });
  }
}

function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function row(label: string, value: string, unit = ''): string {
  return `<tr><td>${esc(label)}</td><td class="v">${esc(value)}</td><td class="u">${esc(unit)}</td></tr>`;
}

function buildHtml(data: ExportData): string {
  const r = data.results;
  const dims = dimensionRows(data)
    .map(([label, v]) => row(label, fnum(v * 1000, 0), 'mm'))
    .join('');

  const inputRows = [
    row('Profil', data.profileLabel),
    dims,
    row('Matériau', data.materialName),
    row('Coefficient de Strickler K', fnum(data.K, 1), 'm^(1/3)/s'),
    row('Pente J', fnum(data.slopePct, 4), '%'),
    row('Débit Q', fnum(data.Q_lps, 2), 'L/s'),
  ].join('');

  const resRows: string[] = [];
  if (r.full) {
    resRows.push(row('Débit critique Qc (remplissage 100 %)', fnum(r.full.Q * 1000, 1), 'L/s'));
    resRows.push(row('Vitesse à pleine section Vc', fnum(r.full.V, 3), 'm/s'));
    resRows.push(
      row(
        `Débit maximal Qmax (à ${fnum(r.full.fillAtQmax * 100, 1)} % de remplissage)`,
        fnum(r.full.Qmax * 1000, 1),
        'L/s',
      ),
    );
  }
  if (r.operating) {
    const o = r.operating;
    if (o.surcharged) {
      resRows.push(row('Taux de remplissage', '≥ 100 % — capacité dépassée'));
      resRows.push(row('Vitesse (section pleine)', fnum(o.V, 3), 'm/s'));
    } else if (o.bicritical && o.fillAlt !== undefined) {
      resRows.push(
        row('Taux de remplissage (régime bicritique)', `${fnum(o.fill * 100, 1)} % ou ${fnum(o.fillAlt * 100, 1)} %`),
      );
      resRows.push(
        row('Vitesse d’écoulement (2 solutions)', `${fnum(o.V, 3)} ou ${fnum(o.VAlt, 3)}`, 'm/s'),
      );
    } else {
      resRows.push(row('Taux de remplissage', fnum(o.fill * 100, 1), '%'));
      resRows.push(row('Vitesse d’écoulement', fnum(o.V, 3), 'm/s'));
    }
  }
  if (r.minSlope !== undefined) resRows.push(row('Pente minimale', fnum(r.minSlope * 100, 4), '%'));
  if (r.minSize) resRows.push(row(`${r.minSize.label} minimal(e)`, fnum(r.minSize.value * 1000, 0), 'mm'));

  const bicriticalNote =
    r.operating?.bicritical && r.full
      ? `<p class="note"><b>Régime bicritique :</b> entre Qc = ${fnum(r.full.Q * 1000, 1)} L/s et
         Qmax = ${fnum(r.full.Qmax * 1000, 1)} L/s, la courbe de débit n'est pas monotone
         (remplissage ≈ 80–100 %) : deux hauteurs d'eau distinctes peuvent transiter le même débit.
         La solution effectivement observée dépend des conditions aval/amont du tronçon.</p>`
      : '';

  return `
  <html><head><meta charset="utf-8"><style>
    @page { size: A4 portrait; margin: 16mm 14mm 14mm 16mm; }
    body { font-family: -apple-system, Helvetica, Arial, sans-serif; color: #16212B; margin: 0; }
    table, svg { page-break-inside: avoid; }
    h2 { page-break-after: avoid; }
    h1 { color: #1f4e79; font-size: 21px; margin-bottom: 2px; }
    .sub { color: #666; font-size: 12px; margin-bottom: 18px; }
    h2 { color: #1f4e79; font-size: 15px; margin: 18px 0 6px; border-bottom: 1px solid #dfe6ee; padding-bottom: 3px; }
    table { border-collapse: collapse; width: 100%; font-size: 12px; }
    td { padding: 4px 8px; border-bottom: 1px solid #f0f0f0; }
    td.v { font-weight: 600; text-align: right; white-space: nowrap; }
    td.u { color: #888; width: 70px; }
    .note { font-size: 11px; color: #444; background: #fdf3e7; border-radius: 6px; padding: 8px 10px; }
    .formula { font-size: 11px; color: #888; margin-top: 16px; }
    .legend { font-size: 10px; color: #444; margin-top: 4px; }
  </style></head><body>
    <h1>Manning–Strickler — Note de calcul hydraulique</h1>
    <div class="sub">Écoulement à surface libre · ${new Date().toLocaleDateString('fr-FR')} · version ${APP_VERSION}</div>
    <h2>Données d'entrée</h2>
    <table>${inputRows}</table>
    <h2>Résultats</h2>
    <table>${resRows.join('')}</table>
    ${bicriticalNote}
    <h2>Courbes hydrauliques</h2>
    ${buildSvgChart(r.curve, operatingPointsForChart(data))}
    <div class="legend">
      <span style="color:${V_COLOR}">■</span> V/Vc (vitesse) &nbsp;
      <span style="color:${Q_COLOR}">■</span> Q/Qc (débit) &nbsp;
      <span style="color:${POINT_COLOR}">●</span> Point(s) d'écoulement — ordonnée : taux de remplissage (%)
    </div>
    <div class="formula">V = K · Rh^(2/3) · J^(1/2) &nbsp;·&nbsp; Q = V · A &nbsp;·&nbsp; Rh = A / P</div>
  </body></html>`;
}

interface ChartPoint {
  fill: number;
  vRatio: number;
  qRatio: number;
  alt: boolean;
}

function operatingPointsForChart(data: ExportData): ChartPoint[] {
  const r = data.results;
  if (!r.operating || !r.full || !(r.full.V > 0) || !(r.full.Q > 0)) return [];
  const Q = data.Q_lps !== undefined ? data.Q_lps / 1000 : undefined;
  const qRatio = Q !== undefined ? Q / r.full.Q : 0;
  const pts: ChartPoint[] = [
    { fill: Math.min(r.operating.fill, 1), vRatio: r.operating.V / r.full.V, qRatio, alt: false },
  ];
  if (r.operating.bicritical && r.operating.fillAlt !== undefined && r.operating.VAlt !== undefined) {
    pts.push({ fill: r.operating.fillAlt, vRatio: r.operating.VAlt / r.full.V, qRatio, alt: true });
  }
  return pts.filter((p) => [p.fill, p.vRatio, p.qRatio].every(Number.isFinite));
}

function buildSvgChart(curve: CurvePoint[], points: ChartPoint[]): string {
  const W = 520;
  const H = 300;
  const padL = 40;
  const padB = 30;
  const padT = 10;
  const padR = 10;
  const plotW = W - padL - padR;
  const plotH = H - padT - padB;

  let xMax = 1.2;
  for (const p of curve) xMax = Math.max(xMax, p.vRatio, p.qRatio);
  for (const p of points) xMax = Math.max(xMax, p.vRatio, p.qRatio);
  xMax = Math.min(2.6, Math.ceil(xMax / 0.2) * 0.2);

  const sx = (v: number) => padL + Math.min(Math.max(v / xMax, 0), 1) * plotW;
  const sy = (fill: number) => padT + (1 - Math.min(Math.max(fill, 0), 1)) * plotH;

  const poly = (get: (p: CurvePoint) => number, color: string) =>
    `<polyline fill="none" stroke="${color}" stroke-width="2" points="${curve
      .filter((p) => Number.isFinite(get(p)) && Number.isFinite(p.fill))
      .map((p) => `${sx(get(p)).toFixed(1)},${sy(p.fill).toFixed(1)}`)
      .join(' ')}"/>`;

  const grid: string[] = [];
  for (let t = 0; t <= xMax + 1e-9; t += 0.2) {
    grid.push(
      `<line x1="${sx(t)}" y1="${padT}" x2="${sx(t)}" y2="${padT + plotH}" stroke="#eee"/>`,
      `<text x="${sx(t)}" y="${H - 12}" font-size="9" fill="#666" text-anchor="middle">${t.toFixed(1)}</text>`,
    );
  }
  for (let f = 0; f <= 100; f += 20) {
    grid.push(
      `<line x1="${padL}" y1="${sy(f / 100)}" x2="${padL + plotW}" y2="${sy(f / 100)}" stroke="#eee"/>`,
      `<text x="${padL - 5}" y="${sy(f / 100) + 3}" font-size="9" fill="#666" text-anchor="end">${f}</text>`,
    );
  }

  const markers = points
    .map(
      (p) =>
        `<line x1="${padL}" y1="${sy(p.fill)}" x2="${padL + plotW}" y2="${sy(p.fill)}" stroke="${POINT_COLOR}" stroke-dasharray="5 3" stroke-width="1"/>` +
        `<circle cx="${sx(p.qRatio)}" cy="${sy(p.fill)}" r="4.5" fill="${p.alt ? '#fff' : Q_COLOR}" stroke="${Q_COLOR}" stroke-width="2"/>` +
        `<circle cx="${sx(p.vRatio)}" cy="${sy(p.fill)}" r="4.5" fill="${p.alt ? '#fff' : V_COLOR}" stroke="${V_COLOR}" stroke-width="2"/>`,
    )
    .join('');

  return `<svg width="${W}" height="${H}" xmlns="http://www.w3.org/2000/svg">
    <rect x="${padL}" y="${padT}" width="${plotW}" height="${plotH}" fill="#fafafa" stroke="#ddd"/>
    ${grid.join('')}
    <line x1="${sx(1)}" y1="${padT}" x2="${sx(1)}" y2="${padT + plotH}" stroke="#bbb" stroke-dasharray="4 3"/>
    ${poly((p) => p.qRatio, Q_COLOR)}
    ${poly((p) => p.vRatio, V_COLOR)}
    ${markers}
  </svg>`;
}
