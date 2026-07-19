/**
 * Interactive Excel (.xlsx) export: a standalone workbook that reproduces the
 * app itself. The user picks the profile from a dropdown, fills in the
 * geometry / K / J / Q input cells, and EVERY result recomputes through real
 * spreadsheet formulas:
 *
 *  - "Calcul": inputs (profile dropdown, all dimensions, material dropdown,
 *    K, J, Q) and results — full-section A/P/Rh/Vc/Qc, Qmax (=MAX over the
 *    curve), regime (surface libre / bicritique / en charge / débordement),
 *    both bicritical filling ratios and velocities (MATCH+INDEX interpolation
 *    over the 1 %-step curve table), minimum slope and minimum dimension.
 *    A native Excel scatter chart shows V/Vc and Q/Qc versus filling ratio
 *    with the dynamic operating point(s).
 *  - "Courbe": hydraulic-elements table at 1 % steps, one column block per
 *    profile (circular/ovoid/rectangular/trapezoidal — the ovoid uses exact
 *    closed-form circular-segment formulas), the active block selected with
 *    CHOOSE on the profile index.
 *  - "Matériaux": the material list feeding the dropdown and the K lookup.
 *
 * SheetJS cannot write data validations or charts, so after XLSX.write the
 * zip is post-processed with JSZip to inject the dropdowns, the drawing and
 * the chart parts (plain OOXML). Every formula cell carries a cached value
 * (SheetJS drops formula-only cells; Excel wants a cached display value).
 */

import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import * as XLSX from 'xlsx';
import JSZip from 'jszip';
import { MATERIALS } from '../hydraulics/materials';
import { ProfileId } from '../hydraulics/profiles';
import { ExportData } from './exportData';

// ---------------------------------------------------------------------------
// Fixed layout of sheet "Calcul" (rows referenced from formulas — keep in sync)
// ---------------------------------------------------------------------------
const R = {
  TITLE: 1, DATE: 2, SEC1: 4, PROFIL: 5, PIDX: 6,
  D: 7, L: 8, RB: 9, RH: 10, TB: 11, TT: 12, TH: 13, HMAX: 14,
  SEC2: 16, MAT: 17, K: 18, JPCT: 19, J: 20, QL: 21, QM: 22,
  SEC3: 24, A: 25, P: 26, RHY: 27, VC: 28, QCM: 29, QCL: 30, QMAX: 31, FQMAX: 32,
  SEC4: 34, REGIME: 35, PEAK: 36, ILOW: 37, TLOW: 38, FLOW: 39, ALOW: 40, VLOW: 41,
  BIC: 42, POSH: 43, IH: 44, TH2: 45, FH: 46, AH: 47, VH: 48,
  SEC5: 50, MINJ: 51, MIND: 52,
  SEC6: 54, GQ1: 55, GQ2: 56, GV1: 57, GV2: 58,
};
// Ovoid geometry constants live in Calcul column F (labels in E)
const OV = { R: 5, r: 6, rho: 7, b: 8, a: 9, yb: 10, yt: 11, Ayb: 12, Ayt: 13, Pyb: 14, Pyt: 15, Afull: 16, Pfull: 17, S2yb: 18, S3yt: 19, As2: 20, As3: 21, mTrap: 22 };

const PROFILE_NAMES = ['Circulaire fermé', 'Ovoïde fermé', 'Caniveau rectangulaire', 'Caniveau trapézoïdal'];
const PROFILE_INDEX: Record<ProfileId, number> = { circular: 1, ovoid: 2, rectangular: 3, trapezoidal: 4 };
const N_ROWS = 100; // curve rows: fill = 1..100 %
const CURVE_FIRST = 6; // first data row on sheet "Courbe"
const CURVE_LAST = CURVE_FIRST + N_ROWS - 1; // 105

type CellVal = string | number | { f: string; v?: number; na?: boolean };

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------
export async function exportExcel(data: ExportData): Promise<void> {
  const caches = computeCaches(data);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, buildCalcSheet(data, caches), 'Calcul');
  XLSX.utils.book_append_sheet(wb, buildCurveSheet(data, caches), 'Courbe');
  XLSX.utils.book_append_sheet(wb, buildMaterialsSheet(), 'Matériaux');

  const base = XLSX.write(wb, { type: 'base64', bookType: 'xlsx' });
  const b64 = await injectValidationAndChart(base, caches);

  const uri = `${FileSystem.cacheDirectory}manning-strickler.xlsx`;
  await FileSystem.writeAsStringAsync(uri, b64, { encoding: FileSystem.EncodingType.Base64 });
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(uri, {
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      dialogTitle: 'Exporter le classeur Excel',
    });
  }
}

// ---------------------------------------------------------------------------
// Cached values (displayed before Excel's first recalculation)
// ---------------------------------------------------------------------------
interface Caches {
  pidx: number;
  dims: { D: number; L: number; RB: number; RH: number; TB: number; TT: number; TH: number };
  Hmax: number;
  K: number; J: number; QL: number; QM: number;
  ov: number[]; // ovoid constants indexed like OV rows (5..22)
  Afull: number; Pfull: number; Rh: number; Vc: number; QcM: number; QcL: number;
  QmaxL: number; fillQmax: number;
  // per-profile curve blocks at 1 % steps (index 0 => fill 1 %)
  circA: number[]; circP: number[]; theta: number[];
  ovA: number[]; ovP: number[]; hOv: number[];
  rectA: number[]; rectP: number[]; trapA: number[]; trapP: number[];
  hSel: number[]; selA: number[]; selP: number[]; rh: number[]; v: number[]; q: number[];
  qRatio: (number | null)[]; vRatio: (number | null)[];
  // operating point
  regime: string; peak: number; iLow: number; tLow: number; fillLow: number | null;
  aLow: number; vLow: number | null; bic: boolean; posH: number; iH: number; tH: number;
  fillH: number | null; aH: number; vH: number | null;
  minJ: number | null; minDim: number | null;
  gq1: number | null; gq2: number | null; gv1: number | null; gv2: number | null;
  gy1: number | null; gy2: number | null;
}

const asinC = (x: number) => Math.asin(Math.max(-1, Math.min(1, x)));
const sq = (x: number) => Math.sqrt(Math.max(0, x));

/** Doubled circular-segment integral 2∫√(c²-(y-yc)²)dy antiderivative. */
function Sfn(c: number, yc: number, y: number): number {
  return (y - yc) * sq(c * c - (y - yc) * (y - yc)) + c * c * asinC((y - yc) / c);
}

/** Closed-form ovoid constants for width L (mirrors the Excel formulas). */
function ovoidConsts(L: number): number[] {
  const c: number[] = new Array(23).fill(0);
  if (!(L > 0)) return c;
  const Rr = L / 2;
  const r = Rr / 3;
  const rho = 3 * Rr;
  const b = (21 * Rr) / 10;
  const a = -sq(4 * Rr * Rr - (b - 2 * Rr) * (b - 2 * Rr));
  const d1 = Math.hypot(a, r - b);
  const d2 = Math.hypot(a, 2 * Rr - b);
  const yb = b + (rho * (r - b)) / d1;
  const yt = b + (rho * (2 * Rr - b)) / d2;
  const Ayb = Sfn(r, r, yb) + (r * r * Math.PI) / 2;
  const S2yb = Sfn(rho, b, yb);
  const Ayt = Ayb + 2 * a * (yt - yb) + Sfn(rho, b, yt) - S2yb;
  const Pyb = 2 * r * (asinC((yb - r) / r) + Math.PI / 2);
  const As2 = asinC((yb - b) / rho);
  const Pyt = Pyb + 2 * rho * (asinC((yt - b) / rho) - As2);
  const S3yt = Sfn(Rr, 2 * Rr, yt);
  const As3 = asinC((yt - 2 * Rr) / Rr);
  const Afull = Ayt + (Rr * Rr * Math.PI) / 2 - S3yt;
  const Pfull = Pyt + 2 * Rr * (Math.PI / 2 - As3);
  c[OV.R] = Rr; c[OV.r] = r; c[OV.rho] = rho; c[OV.b] = b; c[OV.a] = a;
  c[OV.yb] = yb; c[OV.yt] = yt; c[OV.Ayb] = Ayb; c[OV.Ayt] = Ayt;
  c[OV.Pyb] = Pyb; c[OV.Pyt] = Pyt; c[OV.Afull] = Afull; c[OV.Pfull] = Pfull;
  c[OV.S2yb] = S2yb; c[OV.S3yt] = S3yt; c[OV.As2] = As2; c[OV.As3] = As3;
  return c;
}

/** Ovoid A(h), P(h) closed form (same piecewise math as the Excel formulas). */
export function ovoidAP(L: number, h: number, c?: number[]): { A: number; P: number } {
  if (!(L > 0) || !(h > 0)) return { A: 0, P: 0 };
  const k = c ?? ovoidConsts(L);
  const [Rr, r, rho, b] = [k[OV.R], k[OV.r], k[OV.rho], k[OV.b]];
  if (h <= k[OV.yb]) {
    return {
      A: Sfn(r, r, h) + (r * r * Math.PI) / 2,
      P: 2 * r * (asinC((h - r) / r) + Math.PI / 2),
    };
  }
  if (h <= k[OV.yt]) {
    return {
      A: k[OV.Ayb] + 2 * k[OV.a] * (h - k[OV.yb]) + Sfn(rho, b, h) - k[OV.S2yb],
      P: k[OV.Pyb] + 2 * rho * (asinC((h - b) / rho) - k[OV.As2]),
    };
  }
  const hh = Math.min(h, 3 * Rr);
  return {
    A: k[OV.Ayt] + Sfn(Rr, 2 * Rr, hh) - k[OV.S3yt],
    P: k[OV.Pyt] + 2 * Rr * (asinC((hh - 2 * Rr) / Rr) - k[OV.As3]),
  };
}

function computeCaches(data: ExportData): Caches {
  const p = data.params;
  const dims = {
    D: p.diameter ?? 0, L: p.ovoidWidth ?? 0,
    RB: p.rectWidth ?? 0, RH: p.rectHeight ?? 0,
    TB: p.trapBottom ?? 0, TT: p.trapTop ?? 0, TH: p.trapHeight ?? 0,
  };
  const pidx = PROFILE_INDEX[data.profile];
  const K = data.K ?? 0;
  const J = data.slopePct !== undefined ? data.slopePct / 100 : 0;
  const QL = data.Q_lps ?? 0;
  const QM = QL / 1000;
  const ov = ovoidConsts(dims.L);
  const mTrap = dims.TH > 0 ? (dims.TT - dims.TB) / (2 * dims.TH) : 0;
  ov[OV.mTrap] = mTrap;

  const Hmax = [0, dims.D, 1.5 * dims.L, dims.RH, dims.TH][pidx] ?? 0;

  const circA: number[] = [], circP: number[] = [], theta: number[] = [];
  const ovA: number[] = [], ovP: number[] = [], hOv: number[] = [];
  const rectA: number[] = [], rectP: number[] = [], trapA: number[] = [], trapP: number[] = [];
  const hSel: number[] = [], selA: number[] = [], selP: number[] = [];
  const rh: number[] = [], v: number[] = [], q: number[] = [];
  const qRatio: (number | null)[] = [], vRatio: (number | null)[] = [];

  // Full section first (needed for ratios)
  const AfullBy = [0, (Math.PI * dims.D * dims.D) / 4, ov[OV.Afull], dims.RB * dims.RH, ((dims.TB + dims.TT) / 2) * dims.TH];
  const PfullBy = [0, Math.PI * dims.D, ov[OV.Pfull], dims.RB + 2 * dims.RH, dims.TB + 2 * Math.hypot((dims.TT - dims.TB) / 2, dims.TH)];
  const Afull = AfullBy[pidx] ?? 0;
  const Pfull = PfullBy[pidx] ?? 0;
  const Rh = Pfull > 0 ? Afull / Pfull : 0;
  const Vc = K > 0 && J > 0 ? K * Math.pow(Rh, 2 / 3) * Math.sqrt(J) : 0;
  const QcM = Vc * Afull;
  const QcL = QcM * 1000;

  for (let i = 1; i <= N_ROWS; i++) {
    const f = i / 100;
    const th = 2 * Math.acos(1 - 2 * f);
    theta.push(th);
    circA.push(((dims.D * dims.D) / 8) * (th - Math.sin(th)));
    circP.push((dims.D * th) / 2);
    const ho = f * 1.5 * dims.L;
    hOv.push(ho);
    const oap = ovoidAP(dims.L, ho, ov);
    ovA.push(oap.A);
    ovP.push(oap.P);
    const hr = f * dims.RH;
    rectA.push(dims.RB * hr);
    rectP.push(dims.RB + 2 * hr);
    const ht = f * dims.TH;
    trapA.push((dims.TB + mTrap * ht) * ht);
    trapP.push(dims.TB + 2 * ht * Math.sqrt(1 + mTrap * mTrap));
    hSel.push(f * Hmax);
    const A = [0, circA[i - 1], ovA[i - 1], rectA[i - 1], trapA[i - 1]][pidx] ?? 0;
    const P = [0, circP[i - 1], ovP[i - 1], rectP[i - 1], trapP[i - 1]][pidx] ?? 0;
    selA.push(A);
    selP.push(P);
    const rhi = P > 0 ? A / P : 0;
    rh.push(rhi);
    const vi = K > 0 && J > 0 ? K * Math.pow(rhi, 2 / 3) * Math.sqrt(J) : 0;
    v.push(vi);
    q.push(vi * A * 1000);
    qRatio.push(QcL > 0 ? q[i - 1] / QcL : null);
    vRatio.push(Vc > 0 ? vi / Vc : null);
  }

  // Qmax and operating-point caches (mirrors the sheet formulas)
  let QmaxL = 0, peak = N_ROWS;
  for (let i = 0; i < N_ROWS; i++) if (q[i] > QmaxL) { QmaxL = q[i]; peak = i + 1; }
  const hasQ = QL > 0;
  const closed = pidx <= 2;
  const surcharged = hasQ && QmaxL > 0 && QL > QmaxL;
  let iLow = 0;
  if (hasQ) for (let i = 0; i < peak; i++) { if (q[i] <= QL) iLow = i + 1; else break; }
  const tLow = iLow === 0 || iLow >= peak ? 0 : (QL - q[iLow - 1]) / (q[iLow] - q[iLow - 1]);
  let fillLow: number | null = null, aLow = 0, vLow: number | null = null;
  if (hasQ && Vc > 0) {
    if (surcharged) { fillLow = 100; aLow = Afull; vLow = QM / Afull; }
    else if (iLow === 0) {
      fillLow = q[0] > 0 ? QL / q[0] : 0;
      aLow = selA[0] * (q[0] > 0 ? QL / q[0] : 0);
      vLow = aLow > 0 ? QM / aLow : 0;
    } else {
      fillLow = iLow + tLow;
      aLow = selA[iLow - 1] + tLow * (selA[Math.min(iLow, N_ROWS - 1)] - selA[iLow - 1]);
      vLow = aLow > 0 ? QM / aLow : 0;
    }
  }
  const bic = closed && hasQ && QcL > 0 && QL > QcL && QL <= QmaxL;
  let posH = 1, iH = peak, tH = 0, fillH: number | null = null, aH = 0, vH: number | null = null;
  if (bic) {
    for (let i = peak - 1; i < N_ROWS; i++) { if (q[i] >= QL) posH = i - (peak - 1) + 1; else break; }
    iH = peak - 1 + posH;
    tH = iH >= N_ROWS ? 0 : (q[iH - 1] - QL) / (q[iH - 1] - q[iH]);
    fillH = iH + tH;
    aH = selA[iH - 1] + tH * (selA[Math.min(iH, N_ROWS - 1)] - selA[iH - 1]);
    vH = aH > 0 ? QM / aH : 0;
  }
  const regime = !hasQ || Vc <= 0 ? '' : surcharged ? (closed ? 'EN CHARGE (Q > Qmax)' : 'DÉBORDEMENT (Q > Qmax)') : bic ? 'BICRITIQUE : 2 solutions' : 'Écoulement à surface libre';
  const minJ = hasQ && K > 0 && Afull > 0 && Rh > 0 ? Math.pow(QM / (K * Afull * Math.pow(Rh, 2 / 3)), 2) * 100 : null;
  const dimPrincipal = [0, dims.D, dims.L, dims.RH, dims.TH][pidx] ?? 0;
  const minDim = hasQ && QcM > 0 ? dimPrincipal * Math.pow(QM / QcM, 3 / 8) : null;

  return {
    pidx, dims, Hmax, K, J, QL, QM, ov,
    Afull, Pfull, Rh, Vc, QcM, QcL, QmaxL, fillQmax: peak,
    circA, circP, theta, ovA, ovP, hOv, rectA, rectP, trapA, trapP,
    hSel, selA, selP, rh, v, q, qRatio, vRatio,
    regime, peak, iLow, tLow, fillLow, aLow, vLow, bic, posH, iH, tH, fillH, aH, vH,
    minJ, minDim,
    gq1: hasQ && QcL > 0 ? QL / QcL : null, gq2: bic && QcL > 0 ? QL / QcL : null,
    gv1: vLow !== null && Vc > 0 ? vLow / Vc : null, gv2: vH !== null && Vc > 0 ? vH / Vc : null,
    gy1: fillLow, gy2: fillH,
  };
}

// ---------------------------------------------------------------------------
// Sheet builders
// ---------------------------------------------------------------------------
class SheetMap {
  cells = new Map<string, CellVal>();
  maxR = 0;
  maxC = 0;
  put(col: string, row: number, val: CellVal | null | undefined) {
    if (val === null || val === undefined || val === '') return;
    this.cells.set(`${col}${row}`, val);
    this.maxR = Math.max(this.maxR, row);
    this.maxC = Math.max(this.maxC, XLSX.utils.decode_col(col));
  }
  toSheet(colWidths: number[]): XLSX.WorkSheet {
    const ws: XLSX.WorkSheet = {};
    for (const [addr, cell] of this.cells) {
      if (typeof cell === 'string') ws[addr] = { t: 's', v: cell };
      else if (typeof cell === 'number') ws[addr] = { t: 'n', v: cell };
      else if (cell.na && (cell.v === undefined || !Number.isFinite(cell.v))) {
        ws[addr] = { t: 'e', v: 0x2a, w: '#N/A', f: cell.f };
      } else {
        ws[addr] = { t: 'n', f: cell.f, v: cell.v !== undefined && Number.isFinite(cell.v) ? cell.v : 0 };
      }
    }
    ws['!ref'] = `A1:${XLSX.utils.encode_col(this.maxC)}${this.maxR}`;
    ws['!cols'] = colWidths.map((wch) => ({ wch }));
    return ws;
  }
}

/** Formula cell with cached value. */
const F = (f: string, v?: number | null): CellVal => ({ f, v: v ?? undefined });
/** Formula cell whose cache is #N/A when v is null (chart-friendly). */
const FN = (f: string, v?: number | null): CellVal => ({ f, v: v ?? undefined, na: v === null || v === undefined });

function buildMaterialsSheet(): XLSX.WorkSheet {
  const s = new SheetMap();
  s.put('A', 1, 'Matériau');
  s.put('B', 1, 'K (m^(1/3)/s)');
  MATERIALS.filter((m) => m.id !== 'custom').forEach((m, i) => {
    s.put('A', 2 + i, m.name);
    s.put('B', 2 + i, m.K);
  });
  return s.toSheet([26, 14]);
}

// Range helpers on sheet "Courbe"
const CR = (col: string) => `Courbe!$${col}$${CURVE_FIRST}:$${col}$${CURVE_LAST}`;
const CIDX = (col: string, i: string | number) => `INDEX(${CR(col)},${i})`;

function buildCalcSheet(data: ExportData, c: Caches): XLSX.WorkSheet {
  const s = new SheetMap();
  const B = (row: number) => `B${row}`;
  const label = (row: number, text: string, unit?: string) => {
    s.put('A', row, text);
    if (unit) s.put('C', row, unit);
  };

  s.put('A', R.TITLE, 'Manning–Strickler — Classeur de calcul interactif');
  s.put('A', R.DATE, `Généré le ${new Date().toLocaleDateString('fr-FR')} — modifiez les cellules jaunes conceptuelles (B5, B7–B13, B17–B21) : tout recalcule, graphique compris.`);

  label(R.SEC1, '1) PROFIL ET GÉOMÉTRIE (liste déroulante en B5 ; renseignez les dimensions du profil choisi)');
  label(R.PROFIL, 'Profil (liste déroulante)');
  s.put('B', R.PROFIL, PROFILE_NAMES[c.pidx - 1]);
  label(R.PIDX, 'Index du profil (auto)');
  s.put('B', R.PIDX, F(`IFERROR(MATCH(B${R.PROFIL},{"${PROFILE_NAMES.join('","')}"},0),1)`, c.pidx));
  label(R.D, 'D — diamètre intérieur (circulaire)', 'm');
  if (c.dims.D > 0) s.put('B', R.D, c.dims.D);
  label(R.L, 'L — largeur (ovoïde, hauteur = 1,5·L)', 'm');
  if (c.dims.L > 0) s.put('B', R.L, c.dims.L);
  label(R.RB, 'B — base (rectangulaire)', 'm');
  if (c.dims.RB > 0) s.put('B', R.RB, c.dims.RB);
  label(R.RH, 'H — hauteur (rectangulaire)', 'm');
  if (c.dims.RH > 0) s.put('B', R.RH, c.dims.RH);
  label(R.TB, 'b — petite base (trapèze)', 'm');
  if (c.dims.TB > 0) s.put('B', R.TB, c.dims.TB);
  label(R.TT, 'B — grande base (trapèze)', 'm');
  if (c.dims.TT > 0) s.put('B', R.TT, c.dims.TT);
  label(R.TH, 'H — hauteur (trapèze)', 'm');
  if (c.dims.TH > 0) s.put('B', R.TH, c.dims.TH);
  label(R.HMAX, 'Hauteur de la section pleine Hmax', 'm');
  s.put('B', R.HMAX, F(`CHOOSE(B${R.PIDX},B${R.D},1.5*B${R.L},B${R.RH},B${R.TH})`, c.Hmax));

  label(R.SEC2, '2) MATÉRIAU (liste déroulante) ET PARAMÈTRES HYDRAULIQUES');
  label(R.MAT, 'Matériau (liste déroulante)');
  s.put('B', R.MAT, data.materialName);
  label(R.K, 'Coefficient de Strickler K (modifiable)', 'm^(1/3)/s');
  s.put('B', R.K, F(`IFERROR(VLOOKUP(B${R.MAT},'Matériaux'!$A$2:$B$12,2,0),${c.K || 80})`, c.K || 80));
  label(R.JPCT, 'Pente J', '%');
  if (data.slopePct !== undefined) s.put('B', R.JPCT, data.slopePct);
  label(R.J, 'Pente J (ratio m/m)', 'm/m');
  s.put('B', R.J, F(`IF(B${R.JPCT}="",0,B${R.JPCT}/100)`, c.J));
  label(R.QL, 'Débit Q', 'L/s');
  if (data.Q_lps !== undefined) s.put('B', R.QL, data.Q_lps);
  label(R.QM, 'Débit Q (m³/s)', 'm³/s');
  s.put('B', R.QM, F(`IF(B${R.QL}="",0,B${R.QL}/1000)`, c.QM));

  // --- Ovoid constants + trapezoid helper (columns E/F) ---
  s.put('E', 4, 'Constantes géométrie (auto)');
  const ovPut = (row: number, lab: string, f: string) => {
    s.put('E', row, lab);
    s.put('F', row, F(f, c.ov[row]));
  };
  const Fo = (row: number) => `F${row}`;
  ovPut(OV.R, 'Ovoïde R = L/2', `IF(B${R.L}="",0,B${R.L}/2)`);
  ovPut(OV.r, 'r = R/3', `${Fo(OV.R)}/3`);
  ovPut(OV.rho, 'ρ = 3R', `3*${Fo(OV.R)}`);
  ovPut(OV.b, 'b centre flancs = 2,1R', `21*${Fo(OV.R)}/10`);
  ovPut(OV.a, 'a centre flancs', `-SQRT(MAX(0,4*${Fo(OV.R)}^2-(${Fo(OV.b)}-2*${Fo(OV.R)})^2))`);
  ovPut(OV.yb, 'y jonction radier', `IF(${Fo(OV.R)}=0,0,${Fo(OV.b)}+${Fo(OV.rho)}*(${Fo(OV.r)}-${Fo(OV.b)})/SQRT(${Fo(OV.a)}^2+(${Fo(OV.r)}-${Fo(OV.b)})^2))`);
  ovPut(OV.yt, 'y jonction voûte', `IF(${Fo(OV.R)}=0,0,${Fo(OV.b)}+${Fo(OV.rho)}*(2*${Fo(OV.R)}-${Fo(OV.b)})/SQRT(${Fo(OV.a)}^2+(2*${Fo(OV.R)}-${Fo(OV.b)})^2))`);
  const S = (cc: string, yc: string, y: string) =>
    `(${y}-${yc})*SQRT(MAX(0,${cc}^2-(${y}-${yc})^2))+${cc}^2*ASIN(MAX(-1,MIN(1,IF(${cc}=0,0,(${y}-${yc})/${cc}))))`;
  ovPut(OV.Ayb, 'A(yb)', `${S(Fo(OV.r), Fo(OV.r), Fo(OV.yb))}+${Fo(OV.r)}^2*PI()/2`);
  ovPut(OV.S2yb, 'S2(yb) aux.', S(Fo(OV.rho), Fo(OV.b), Fo(OV.yb)));
  ovPut(OV.Ayt, 'A(yt)', `${Fo(OV.Ayb)}+2*${Fo(OV.a)}*(${Fo(OV.yt)}-${Fo(OV.yb)})+${S(Fo(OV.rho), Fo(OV.b), Fo(OV.yt))}-${Fo(OV.S2yb)}`);
  ovPut(OV.Pyb, 'P(yb)', `2*${Fo(OV.r)}*(ASIN(MAX(-1,MIN(1,IF(${Fo(OV.r)}=0,0,(${Fo(OV.yb)}-${Fo(OV.r)})/${Fo(OV.r)}))))+PI()/2)`);
  ovPut(OV.As2, 'asin2(yb) aux.', `ASIN(MAX(-1,MIN(1,IF(${Fo(OV.rho)}=0,0,(${Fo(OV.yb)}-${Fo(OV.b)})/${Fo(OV.rho)}))))`);
  ovPut(OV.Pyt, 'P(yt)', `${Fo(OV.Pyb)}+2*${Fo(OV.rho)}*(ASIN(MAX(-1,MIN(1,IF(${Fo(OV.rho)}=0,0,(${Fo(OV.yt)}-${Fo(OV.b)})/${Fo(OV.rho)}))))-${Fo(OV.As2)})`);
  ovPut(OV.S3yt, 'S3(yt) aux.', S(Fo(OV.R), `2*${Fo(OV.R)}`, Fo(OV.yt)));
  ovPut(OV.As3, 'asin3(yt) aux.', `ASIN(MAX(-1,MIN(1,IF(${Fo(OV.R)}=0,0,(${Fo(OV.yt)}-2*${Fo(OV.R)})/${Fo(OV.R)}))))`);
  ovPut(OV.Afull, 'A pleine ovoïde', `${Fo(OV.Ayt)}+${Fo(OV.R)}^2*PI()/2-${Fo(OV.S3yt)}`);
  ovPut(OV.Pfull, 'P plein ovoïde', `${Fo(OV.Pyt)}+2*${Fo(OV.R)}*(PI()/2-${Fo(OV.As3)})`);
  s.put('E', OV.mTrap, 'm trapèze = (B−b)/(2H)');
  s.put('F', OV.mTrap, F(`IF(B${R.TH}=0,0,IF(B${R.TH}="",0,(B${R.TT}-B${R.TB})/(2*B${R.TH})))`, c.ov[OV.mTrap]));

  // --- Full section ---
  label(R.SEC3, '3) SECTION PLEINE (remplissage 100 %)');
  label(R.A, 'Aire mouillée pleine A', 'm²');
  s.put('B', R.A, F(`CHOOSE(B${R.PIDX},PI()*B${R.D}^2/4,${Fo(OV.Afull)},B${R.RB}*B${R.RH},(B${R.TB}+B${R.TT})/2*B${R.TH})`, c.Afull));
  label(R.P, 'Périmètre mouillé plein P', 'm');
  s.put('B', R.P, F(`CHOOSE(B${R.PIDX},PI()*B${R.D},${Fo(OV.Pfull)},B${R.RB}+2*B${R.RH},B${R.TB}+2*SQRT(((B${R.TT}-B${R.TB})/2)^2+B${R.TH}^2))`, c.Pfull));
  label(R.RHY, 'Rayon hydraulique Rh = A/P', 'm');
  s.put('B', R.RHY, F(`IF(B${R.P}=0,0,B${R.A}/B${R.P})`, c.Rh));
  label(R.VC, 'Vitesse pleine section Vc = K·Rh^(2/3)·√J', 'm/s');
  s.put('B', R.VC, F(`B${R.K}*B${R.RHY}^(2/3)*SQRT(B${R.J})`, c.Vc));
  label(R.QCM, 'Débit critique Qc = Vc·A', 'm³/s');
  s.put('B', R.QCM, F(`B${R.VC}*B${R.A}`, c.QcM));
  label(R.QCL, 'Débit critique Qc', 'L/s');
  s.put('B', R.QCL, F(`B${R.QCM}*1000`, c.QcL));
  label(R.QMAX, 'Débit maximal Qmax (max de la courbe)', 'L/s');
  s.put('B', R.QMAX, F(`MAX(${CR('Q')})`, c.QmaxL));
  label(R.FQMAX, 'Remplissage à Qmax', '%');
  s.put('B', R.FQMAX, F(`IFERROR(${CIDX('A', `MATCH(B${R.QMAX},${CR('Q')},0)`)},100)`, c.peak));

  // --- Operating point ---
  label(R.SEC4, '4) POINT DE FONCTIONNEMENT (interpolation sur la feuille Courbe, pas de 1 %)');
  label(R.REGIME, 'Régime');
  // String-valued formula: set directly on the worksheet after toSheet (below).
  const regimeF = `IF(OR(B${R.QL}="",B${R.VC}=0),"",IF(B${R.QL}>B${R.QMAX},IF(B${R.PIDX}<=2,"EN CHARGE (Q > Qmax)","DÉBORDEMENT (Q > Qmax)"),IF(AND(B${R.PIDX}<=2,B${R.QL}>B${R.QCL}),"BICRITIQUE : 2 solutions","Écoulement à surface libre")))`;
  label(R.PEAK, '(aux.) ligne du pic');
  s.put('B', R.PEAK, F(`IFERROR(MATCH(B${R.QMAX},${CR('Q')},0),${N_ROWS})`, c.peak));
  label(R.ILOW, '(aux.) i solution basse');
  s.put('B', R.ILOW, F(`IF(B${R.QL}="",0,IFERROR(MATCH(B${R.QL},Courbe!$Q$${CURVE_FIRST}:INDEX(${CR('Q')},B${R.PEAK}),1),0))`, c.iLow));
  label(R.TLOW, '(aux.) t interpolation basse');
  s.put('B', R.TLOW, F(
    `IF(OR(B${R.ILOW}=0,B${R.ILOW}>=B${R.PEAK}),0,(B${R.QL}-${CIDX('Q', `B${R.ILOW}`)})/(${CIDX('Q', `B${R.ILOW}+1`)}-${CIDX('Q', `B${R.ILOW}`)}))`,
    c.tLow,
  ));
  label(R.FLOW, 'Taux de remplissage — solution basse', '%');
  s.put('B', R.FLOW, FN(
    `IF(OR(B${R.QL}="",B${R.VC}=0),NA(),IF(B${R.QL}>B${R.QMAX},100,IF(B${R.ILOW}=0,IFERROR(B${R.QL}/${CIDX('Q', 1)},0),${CIDX('A', `B${R.ILOW}`)}+B${R.TLOW})))`,
    c.fillLow,
  ));
  label(R.ALOW, '(aux.) aire mouillée basse', 'm²');
  s.put('B', R.ALOW, F(
    `IF(B${R.QL}="",0,IF(B${R.QL}>B${R.QMAX},B${R.A},IF(B${R.ILOW}=0,${CIDX('M', 1)}*IFERROR(B${R.QL}/${CIDX('Q', 1)},0),${CIDX('M', `B${R.ILOW}`)}+B${R.TLOW}*(${CIDX('M', `MIN(B${R.ILOW}+1,${N_ROWS})`)}-${CIDX('M', `B${R.ILOW}`)}))))`,
    c.aLow,
  ));
  label(R.VLOW, 'Vitesse d’écoulement — solution basse', 'm/s');
  s.put('B', R.VLOW, FN(`IF(OR(B${R.QL}="",B${R.ALOW}=0),NA(),B${R.QM}/B${R.ALOW})`, c.vLow));
  label(R.BIC, 'Régime bicritique ?');
  s.put('B', R.BIC, F(`IF(AND(B${R.PIDX}<=2,B${R.QL}<>"",B${R.QL}>B${R.QCL},B${R.QL}<=B${R.QMAX}),1,0)`, c.bic ? 1 : 0));
  label(R.POSH, '(aux.) position haute');
  s.put('B', R.POSH, F(
    `IF(B${R.BIC}=1,IFERROR(MATCH(B${R.QL},INDEX(${CR('Q')},B${R.PEAK}):Courbe!$Q$${CURVE_LAST},-1),1),1)`,
    c.posH,
  ));
  label(R.IH, '(aux.) i solution haute');
  s.put('B', R.IH, F(`B${R.PEAK}-1+B${R.POSH}`, c.iH));
  label(R.TH2, '(aux.) t interpolation haute');
  s.put('B', R.TH2, F(
    `IF(OR(B${R.BIC}=0,B${R.IH}>=${N_ROWS}),0,IFERROR((${CIDX('Q', `B${R.IH}`)}-B${R.QL})/(${CIDX('Q', `B${R.IH}`)}-${CIDX('Q', `B${R.IH}+1`)}),0))`,
    c.tH,
  ));
  label(R.FH, 'Taux de remplissage — solution haute (bicritique)', '%');
  s.put('B', R.FH, FN(`IF(B${R.BIC}=1,${CIDX('A', `B${R.IH}`)}+B${R.TH2},NA())`, c.fillH));
  label(R.AH, '(aux.) aire mouillée haute', 'm²');
  s.put('B', R.AH, F(
    `IF(B${R.BIC}=1,${CIDX('M', `B${R.IH}`)}+B${R.TH2}*(${CIDX('M', `MIN(B${R.IH}+1,${N_ROWS})`)}-${CIDX('M', `B${R.IH}`)}),0)`,
    c.aH,
  ));
  label(R.VH, 'Vitesse d’écoulement — solution haute', 'm/s');
  s.put('B', R.VH, FN(`IF(OR(B${R.BIC}=0,B${R.AH}=0),NA(),B${R.QM}/B${R.AH})`, c.vH));

  // --- Sizing ---
  label(R.SEC5, '5) DIMENSIONNEMENT');
  label(R.MINJ, 'Pente minimale (Q à pleine section)', '%');
  s.put('B', R.MINJ, FN(`IF(OR(B${R.QL}="",B${R.A}=0,B${R.K}=0),NA(),(B${R.QM}/(B${R.K}*B${R.A}*B${R.RHY}^(2/3)))^2*100)`, c.minJ));
  label(R.MIND, 'Dimension minimale D/L/H (pente indiquée)', 'm');
  s.put('B', R.MIND, FN(
    `IF(OR(B${R.QL}="",B${R.QCM}=0),NA(),CHOOSE(B${R.PIDX},B${R.D},B${R.L},B${R.RH},B${R.TH})*(B${R.QM}/B${R.QCM})^(3/8))`,
    c.minDim,
  ));

  // --- Chart data block ---
  label(R.SEC6, 'Données du graphique (aux.) — x = ratio, y = remplissage %');
  s.put('A', R.GQ1, 'Q/Qc — point bas');
  s.put('H', R.GQ1, FN(`IF(OR(B${R.QL}="",B${R.QCL}=0),NA(),B${R.QL}/B${R.QCL})`, c.gq1));
  s.put('I', R.GQ1, FN(`B${R.FLOW}`, c.gy1));
  s.put('A', R.GQ2, 'Q/Qc — point haut');
  s.put('H', R.GQ2, FN(`IF(B${R.BIC}=1,B${R.QL}/B${R.QCL},NA())`, c.gq2));
  s.put('I', R.GQ2, FN(`IF(B${R.BIC}=1,B${R.FH},NA())`, c.gy2));
  s.put('A', R.GV1, 'V/Vc — point bas');
  s.put('H', R.GV1, FN(`IF(OR(B${R.QL}="",B${R.VC}=0),NA(),B${R.VLOW}/B${R.VC})`, c.gv1));
  s.put('I', R.GV1, FN(`B${R.FLOW}`, c.gy1));
  s.put('A', R.GV2, 'V/Vc — point haut');
  s.put('H', R.GV2, FN(`IF(B${R.BIC}=1,B${R.VH}/B${R.VC},NA())`, c.gv2));
  s.put('I', R.GV2, FN(`IF(B${R.BIC}=1,B${R.FH},NA())`, c.gy2));

  const ws = s.toSheet([46, 16, 12, 2, 22, 14, 2, 10, 10]);
  // Regime is a string-valued formula: cache as a formula-string cell
  ws[`B${R.REGIME}`] = { t: 'str', v: c.regime, f: regimeF } as any;
  return ws;
}

function buildCurveSheet(data: ExportData, c: Caches): XLSX.WorkSheet {
  const s = new SheetMap();
  s.put('A', 1, 'Courbes hydrauliques — table à pas de 1 % (toutes formules ; blocs par profil, bloc actif choisi via l’index B6 de la feuille Calcul)');
  // Local helper row
  s.put('A', 2, 'pidx');
  s.put('A', 3, F(`Calcul!$B$${R.PIDX}`, c.pidx));
  s.put('B', 2, 'Hmax (m)');
  s.put('B', 3, F(`Calcul!$B$${R.HMAX}`, c.Hmax));
  s.put('C', 2, 'K');
  s.put('C', 3, F(`Calcul!$B$${R.K}`, c.K));
  s.put('D', 2, 'J (m/m)');
  s.put('D', 3, F(`Calcul!$B$${R.J}`, c.J));

  const headers = ['Remplissage (%)', 'h (m)', 'θ circ (rad)', 'A circ', 'P circ', 'h ovoïde', 'A ovoïde', 'P ovoïde', 'A rect', 'P rect', 'A trap', 'P trap', 'A (m²)', 'P (m)', 'Rh (m)', 'V (m/s)', 'Q (L/s)', 'Q/Qc', 'V/Vc'];
  headers.forEach((h, i) => s.put(XLSX.utils.encode_col(i), 5, h));

  const Ca = (row: number) => `Calcul!$F$${row}`; // ovoid constants
  const S = (cc: string, yc: string, y: string) =>
    `(${y}-${yc})*SQRT(MAX(0,${cc}^2-(${y}-${yc})^2))+${cc}^2*ASIN(MAX(-1,MIN(1,IF(${cc}=0,0,(${y}-${yc})/${cc}))))`;

  for (let i = 1; i <= N_ROWS; i++) {
    const n = CURVE_FIRST + i - 1;
    const k = i - 1;
    s.put('A', n, i);
    s.put('B', n, F(`A${n}/100*$B$3`, c.hSel[k]));
    s.put('C', n, F(`2*ACOS(1-A${n}/50)`, c.theta[k]));
    s.put('D', n, F(`Calcul!$B$${R.D}^2/8*(C${n}-SIN(C${n}))`, c.circA[k]));
    s.put('E', n, F(`Calcul!$B$${R.D}*C${n}/2`, c.circP[k]));
    s.put('F', n, F(`A${n}/100*1.5*Calcul!$B$${R.L}`, c.hOv[k]));
    const h = `F${n}`;
    s.put('G', n, F(
      `IFERROR(IF(${Ca(OV.R)}=0,0,IF(${h}<=${Ca(OV.yb)},${S(Ca(OV.r), Ca(OV.r), h)}+${Ca(OV.r)}^2*PI()/2,IF(${h}<=${Ca(OV.yt)},${Ca(OV.Ayb)}+2*${Ca(OV.a)}*(${h}-${Ca(OV.yb)})+${S(Ca(OV.rho), Ca(OV.b), h)}-${Ca(OV.S2yb)},${Ca(OV.Ayt)}+${S(Ca(OV.R), `2*${Ca(OV.R)}`, h)}-${Ca(OV.S3yt)}))),0)`,
      c.ovA[k],
    ));
    s.put('H', n, F(
      `IFERROR(IF(${Ca(OV.R)}=0,0,IF(${h}<=${Ca(OV.yb)},2*${Ca(OV.r)}*(ASIN(MAX(-1,MIN(1,IF(${Ca(OV.r)}=0,0,(${h}-${Ca(OV.r)})/${Ca(OV.r)}))))+PI()/2),IF(${h}<=${Ca(OV.yt)},${Ca(OV.Pyb)}+2*${Ca(OV.rho)}*(ASIN(MAX(-1,MIN(1,(${h}-${Ca(OV.b)})/${Ca(OV.rho)})))-${Ca(OV.As2)}),${Ca(OV.Pyt)}+2*${Ca(OV.R)}*(ASIN(MAX(-1,MIN(1,(${h}-2*${Ca(OV.R)})/${Ca(OV.R)})))-${Ca(OV.As3)})))),0)`,
      c.ovP[k],
    ));
    s.put('I', n, F(`Calcul!$B$${R.RB}*(A${n}/100*Calcul!$B$${R.RH})`, c.rectA[k]));
    s.put('J', n, F(`Calcul!$B$${R.RB}+2*(A${n}/100*Calcul!$B$${R.RH})`, c.rectP[k]));
    s.put('K', n, F(
      `(Calcul!$B$${R.TB}+Calcul!$F$${OV.mTrap}*(A${n}/100*Calcul!$B$${R.TH}))*(A${n}/100*Calcul!$B$${R.TH})`,
      c.trapA[k],
    ));
    s.put('L', n, F(
      `Calcul!$B$${R.TB}+2*(A${n}/100*Calcul!$B$${R.TH})*SQRT(1+Calcul!$F$${OV.mTrap}^2)`,
      c.trapP[k],
    ));
    s.put('M', n, F(`CHOOSE($A$3,D${n},G${n},I${n},K${n})`, c.selA[k]));
    s.put('N', n, F(`CHOOSE($A$3,E${n},H${n},J${n},L${n})`, c.selP[k]));
    s.put('O', n, F(`IF(N${n}=0,0,M${n}/N${n})`, c.rh[k]));
    s.put('P', n, F(`$C$3*O${n}^(2/3)*SQRT($D$3)`, c.v[k]));
    s.put('Q', n, F(`P${n}*M${n}*1000`, c.q[k]));
    s.put('R', n, FN(`IFERROR(IF(Calcul!$B$${R.QCL}=0,NA(),Q${n}/Calcul!$B$${R.QCL}),NA())`, c.qRatio[k]));
    s.put('S', n, FN(`IFERROR(IF(Calcul!$B$${R.VC}=0,NA(),P${n}/Calcul!$B$${R.VC}),NA())`, c.vRatio[k]));
  }
  return s.toSheet([14, 9, 10, 9, 9, 9, 9, 9, 9, 9, 9, 9, 10, 10, 9, 9, 10, 8, 8]);
}

// ---------------------------------------------------------------------------
// OOXML post-processing: data validations + native chart
// ---------------------------------------------------------------------------
async function injectValidationAndChart(base64: string, c: Caches): Promise<string> {
  const zip = await JSZip.loadAsync(base64, { base64: true });

  // 1) Data validations + drawing reference on sheet1 (Calcul)
  const sheetPath = 'xl/worksheets/sheet1.xml';
  let sheetXml = await zip.file(sheetPath)!.async('string');
  const validations =
    `<dataValidations count="2">` +
    `<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="B${R.PROFIL}"><formula1>"${PROFILE_NAMES.join(',')}"</formula1></dataValidation>` +
    `<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="B${R.MAT}"><formula1>'Matériaux'!$A$2:$A$12</formula1></dataValidation>` +
    `</dataValidations>`;
  // dataValidations must precede pageMargins (schema order); drawing goes last.
  if (sheetXml.includes('<pageMargins')) {
    sheetXml = sheetXml.replace('<pageMargins', `${validations}<pageMargins`);
  } else {
    sheetXml = sheetXml.replace('</worksheet>', `${validations}</worksheet>`);
  }
  sheetXml = sheetXml.replace('</worksheet>', `<drawing r:id="rIdDrw1"/></worksheet>`);
  if (!sheetXml.includes('xmlns:r=')) {
    sheetXml = sheetXml.replace(
      '<worksheet ',
      '<worksheet xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" ',
    );
  }
  zip.file(sheetPath, sheetXml);

  // 2) Sheet1 relationships -> drawing
  const relPath = 'xl/worksheets/_rels/sheet1.xml.rels';
  const relFile = zip.file(relPath);
  const drawingRel = `<Relationship Id="rIdDrw1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>`;
  if (relFile) {
    const rels = (await relFile.async('string')).replace('</Relationships>', `${drawingRel}</Relationships>`);
    zip.file(relPath, rels);
  } else {
    zip.file(
      relPath,
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${drawingRel}</Relationships>`,
    );
  }

  // 3) Drawing part (anchors the chart on Calcul, columns H..S, rows 4..32)
  zip.file(
    'xl/drawings/drawing1.xml',
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
      `<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
      `<xdr:twoCellAnchor><xdr:from><xdr:col>7</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>3</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>` +
      `<xdr:to><xdr:col>18</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>32</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>` +
      `<xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="2" name="Courbes hydrauliques"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>` +
      `<xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>` +
      `<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">` +
      `<c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" r:id="rId1"/>` +
      `</a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>`,
  );
  zip.file(
    'xl/drawings/_rels/drawing1.xml.rels',
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">` +
      `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart1.xml"/></Relationships>`,
  );

  // 4) Chart part
  zip.file('xl/charts/chart1.xml', buildChartXml(c));

  // 5) Content types
  const ctPath = '[Content_Types].xml';
  let ct = await zip.file(ctPath)!.async('string');
  ct = ct.replace(
    '</Types>',
    `<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>` +
      `<Override PartName="/xl/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/></Types>`,
  );
  zip.file(ctPath, ct);

  return zip.generateAsync({ type: 'base64', compression: 'DEFLATE' });
}

function numCache(values: (number | null)[]): string {
  const pts = values
    .map((v, i) => (v !== null && Number.isFinite(v) ? `<c:pt idx="${i}"><c:v>${v}</c:v></c:pt>` : ''))
    .join('');
  return `<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${values.length}"/>${pts}</c:numCache>`;
}

function scatterSeries(
  idx: number,
  name: string,
  xRef: string,
  yRef: string,
  xVals: (number | null)[],
  yVals: (number | null)[],
  color: string,
  pointsOnly: boolean,
): string {
  const line = pointsOnly
    ? `<c:spPr><a:ln><a:noFill/></a:ln></c:spPr>` +
      `<c:marker><c:symbol val="circle"/><c:size val="7"/><c:spPr><a:solidFill><a:srgbClr val="${color}"/></a:solidFill></c:spPr></c:marker>`
    : `<c:spPr><a:ln w="19050"><a:solidFill><a:srgbClr val="${color}"/></a:solidFill></a:ln></c:spPr>` +
      `<c:marker><c:symbol val="none"/></c:marker>`;
  return (
    `<c:ser><c:idx val="${idx}"/><c:order val="${idx}"/><c:tx><c:v>${name}</c:v></c:tx>${line}` +
    `<c:xVal><c:numRef><c:f>${xRef}</c:f>${numCache(xVals)}</c:numRef></c:xVal>` +
    `<c:yVal><c:numRef><c:f>${yRef}</c:f>${numCache(yVals)}</c:numRef></c:yVal>` +
    `<c:smooth val="${pointsOnly ? 0 : 1}"/></c:ser>`
  );
}

function buildChartXml(c: Caches): string {
  const fills = Array.from({ length: N_ROWS }, (_, i) => i + 1);
  const series =
    scatterSeries(0, 'Q/Qc (débit)', `Courbe!$R$${CURVE_FIRST}:$R$${CURVE_LAST}`, `Courbe!$A$${CURVE_FIRST}:$A$${CURVE_LAST}`, c.qRatio, fills, '2F7DD1', false) +
    scatterSeries(1, 'V/Vc (vitesse)', `Courbe!$S$${CURVE_FIRST}:$S$${CURVE_LAST}`, `Courbe!$A$${CURVE_FIRST}:$A$${CURVE_LAST}`, c.vRatio, fills, 'E07A3F', false) +
    scatterSeries(2, 'Point Q/Qc', `Calcul!$H$${R.GQ1}:$H$${R.GQ2}`, `Calcul!$I$${R.GQ1}:$I$${R.GQ2}`, [c.gq1, c.gq2], [c.gy1, c.gy2], '2F7DD1', true) +
    scatterSeries(3, 'Point V/Vc', `Calcul!$H$${R.GV1}:$H$${R.GV2}`, `Calcul!$I$${R.GV1}:$I$${R.GV2}`, [c.gv1, c.gv2], [c.gy1, c.gy2], 'D12F4F', true);

  return (
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
    `<c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>Courbes hydrauliques — V/Vc et Q/Qc vs remplissage (%)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title><c:autoTitleDeleted val="0"/>` +
    `<c:plotArea><c:layout/><c:scatterChart><c:scatterStyle val="smoothMarker"/><c:varyColors val="0"/>${series}` +
    `<c:axId val="111111111"/><c:axId val="222222222"/></c:scatterChart>` +
    `<c:valAx><c:axId val="111111111"/><c:scaling><c:orientation val="minMax"/><c:min val="0"/></c:scaling><c:delete val="0"/><c:axPos val="b"/><c:majorGridlines/>` +
    `<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>V/Vc et Q/Qc</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>` +
    `<c:numFmt formatCode="0.0" sourceLinked="0"/><c:majorTickMark val="out"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="222222222"/></c:valAx>` +
    `<c:valAx><c:axId val="222222222"/><c:scaling><c:orientation val="minMax"/><c:min val="0"/><c:max val="100"/></c:scaling><c:delete val="0"/><c:axPos val="l"/><c:majorGridlines/>` +
    `<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>Taux de remplissage (%)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>` +
    `<c:numFmt formatCode="0" sourceLinked="0"/><c:majorTickMark val="out"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="111111111"/></c:valAx>` +
    `</c:plotArea><c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend><c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>`
  );
}
