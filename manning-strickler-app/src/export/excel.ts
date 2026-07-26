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
  SEC6: 54, NOTE1: 55, NOTE2: 56, CHART_TOP: 58, CHART_BOTTOM: 88,
};
/** Ovoid geometry constants: sheet "Constantes", labels in A, values in B. */
const OV = { R: 5, r: 6, rho: 7, b: 8, a: 9, yb: 10, yt: 11, Ayb: 12, Ayt: 13, Pyb: 14, Pyt: 15, Afull: 16, Pfull: 17, S2yb: 18, S3yt: 19, As2: 20, As3: 21, mTrap: 22 };
/** Chart operating points: sheet "Constantes", x in B, y in C. */
const CH = { HEAD: 24, GQ1: 26, GQ2: 27, GV1: 28, GV2: 29 };
const CONST_SHEET = `'Constantes'`;

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
  // Sheet order defines the sheetN.xml file names used by the injection step.
  const sheets = [
    buildCalcSheet(data, caches), // sheet1
    buildCurveSheet(data, caches), // sheet2
    buildMaterialsSheet(), // sheet3
    buildConstantesSheet(caches), // sheet4
  ];
  ['Calcul', 'Courbe', 'Matériaux', 'Constantes'].forEach((name, i) =>
    XLSX.utils.book_append_sheet(wb, sheets[i], name),
  );
  const styleMaps = sheets.map((ws) => ((ws as any)._st as Map<string, number>) ?? new Map());

  const base = XLSX.write(wb, { type: 'base64', bookType: 'xlsx' });
  const b64 = await injectValidationAndChart(base, caches, styleMaps);

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
  tFlag: number[]; uFlag: number[];
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
  // Helper flags mirroring the Courbe sheet's T/U columns: the largest index
  // on the rising branch whose discharge is still <= Q, and the largest index
  // on the falling branch whose discharge is still >= Q.
  const tFlag: number[] = [];
  const uFlag: number[] = [];
  for (let i = 1; i <= N_ROWS; i++) {
    tFlag.push(hasQ && i <= peak && q[i - 1] <= QL ? i : 0);
    uFlag.push(hasQ && i >= peak && q[i - 1] >= QL ? i : 0);
  }
  const iLow = tFlag.reduce((a, b) => Math.max(a, b), 0);
  const uMax = uFlag.reduce((a, b) => Math.max(a, b), 0);
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
  const posH = uMax;
  let iH = peak, tH = 0, fillH: number | null = null, aH = 0, vH: number | null = null;
  if (bic) {
    iH = uMax > 0 ? uMax : peak;
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
    hSel, selA, selP, rh, v, q, qRatio, vRatio, tFlag, uFlag,
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
/**
 * Style indices into the hand-written xl/styles.xml (see buildStylesXml).
 * SheetJS's community build does not write cell styles, so the indices are
 * injected into the sheet XML afterwards (see applyStyles).
 */
const ST = {
  DEFAULT: 0,
  TITLE: 1,
  SUBTITLE: 2,
  SECTION: 3,
  LABEL: 4,
  INPUT_NUM: 5,
  INPUT_TEXT: 6,
  RESULT: 7,
  UNIT: 8,
  THEAD: 9,
  N3: 10,
  N1: 11,
  N0: 12,
  REGIME: 13,
  AUX: 14,
  N4: 15,
  LABEL_B: 16,
  N2: 17,
  KEY: 18,
} as const;

class SheetMap {
  cells = new Map<string, CellVal>();
  styles = new Map<string, number>();
  merges: string[] = [];
  maxR = 0;
  maxC = 0;
  put(col: string, row: number, val: CellVal | null | undefined, style?: number) {
    if (val === null || val === undefined || val === '') {
      // Still allow an empty styled cell (e.g. a coloured input cell left blank).
      if (style === undefined) return;
      this.styles.set(`${col}${row}`, style);
      this.maxR = Math.max(this.maxR, row);
      this.maxC = Math.max(this.maxC, XLSX.utils.decode_col(col));
      return;
    }
    this.cells.set(`${col}${row}`, val);
    if (style !== undefined) this.styles.set(`${col}${row}`, style);
    this.maxR = Math.max(this.maxR, row);
    this.maxC = Math.max(this.maxC, XLSX.utils.decode_col(col));
  }
  merge(range: string) {
    this.merges.push(range);
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
    if (this.merges.length) {
      ws['!merges'] = this.merges.map((r) => XLSX.utils.decode_range(r));
    }
    // Carried through to the OOXML post-processing step.
    (ws as any)._st = this.styles;
    return ws;
  }
}

/** Formula cell with cached value. */
const F = (f: string, v?: number | null): CellVal => ({ f, v: v ?? undefined });
/** Formula cell whose cache is #N/A when v is null (chart-friendly). */
const FN = (f: string, v?: number | null): CellVal => ({ f, v: v ?? undefined, na: v === null || v === undefined });

function buildMaterialsSheet(): XLSX.WorkSheet {
  const s = new SheetMap();
  s.put('A', 1, 'Matériau', ST.THEAD);
  s.put('B', 1, 'K (m^(1/3)/s)', ST.THEAD);
  MATERIALS.filter((m) => m.id !== 'custom').forEach((m, i) => {
    s.put('A', 2 + i, m.name, ST.LABEL);
    s.put('B', 2 + i, m.K, ST.N0);
  });
  return s.toSheet([30, 16]);
}

/**
 * Auxiliary sheet: ovoid geometry constants, the trapezoid wall slope and the
 * chart's operating-point coordinates. Kept off the printable "Calcul" sheet so
 * that one prints cleanly on A4. Row numbers match the OV.* map.
 */
function buildConstantesSheet(c: Caches): XLSX.WorkSheet {
  const s = new SheetMap();
  s.put('A', 1, 'Valeurs auxiliaires (calculées automatiquement — ne pas modifier)', ST.TITLE);
  s.put('A', 3, 'Géométrie ovoïde et trapèze', ST.SECTION);
  s.put('B', 3, '', ST.SECTION);

  const B = (row: number) => `$B$${row}`;
  const put = (row: number, lab: string, f: string) => {
    s.put('A', row, lab, ST.LABEL);
    s.put('B', row, F(f, c.ov[row]), ST.N4);
  };
  // Ovoid: three-centre profile (invert r = R/3, haunches ρ = 3R, crown R).
  put(OV.R, 'Ovoïde R = L/2', `IF(Calcul!$B$${R.L}="",0,Calcul!$B$${R.L}/2)`);
  put(OV.r, 'r = R/3', `${B(OV.R)}/3`);
  put(OV.rho, 'ρ = 3R', `3*${B(OV.R)}`);
  put(OV.b, 'b (ordonnée centre flancs) = 2,1R', `21*${B(OV.R)}/10`);
  put(OV.a, 'a (abscisse centre flancs)', `-SQRT(MAX(0,4*${B(OV.R)}^2-(${B(OV.b)}-2*${B(OV.R)})^2))`);
  put(OV.yb, 'y jonction radier/flanc', `IF(${B(OV.R)}=0,0,${B(OV.b)}+${B(OV.rho)}*(${B(OV.r)}-${B(OV.b)})/SQRT(${B(OV.a)}^2+(${B(OV.r)}-${B(OV.b)})^2))`);
  put(OV.yt, 'y jonction flanc/voûte', `IF(${B(OV.R)}=0,0,${B(OV.b)}+${B(OV.rho)}*(2*${B(OV.R)}-${B(OV.b)})/SQRT(${B(OV.a)}^2+(2*${B(OV.R)}-${B(OV.b)})^2))`);
  const S = (cc: string, yc: string, y: string) =>
    `(${y}-${yc})*SQRT(MAX(0,${cc}^2-(${y}-${yc})^2))+${cc}^2*ASIN(MAX(-1,MIN(1,IF(${cc}=0,0,(${y}-${yc})/${cc}))))`;
  put(OV.Ayb, 'A(yb)', `${S(B(OV.r), B(OV.r), B(OV.yb))}+${B(OV.r)}^2*PI()/2`);
  put(OV.S2yb, 'S2(yb) auxiliaire', S(B(OV.rho), B(OV.b), B(OV.yb)));
  put(OV.Ayt, 'A(yt)', `${B(OV.Ayb)}+2*${B(OV.a)}*(${B(OV.yt)}-${B(OV.yb)})+${S(B(OV.rho), B(OV.b), B(OV.yt))}-${B(OV.S2yb)}`);
  put(OV.Pyb, 'P(yb)', `2*${B(OV.r)}*(ASIN(MAX(-1,MIN(1,IF(${B(OV.r)}=0,0,(${B(OV.yb)}-${B(OV.r)})/${B(OV.r)}))))+PI()/2)`);
  put(OV.As2, 'asin2(yb) auxiliaire', `ASIN(MAX(-1,MIN(1,IF(${B(OV.rho)}=0,0,(${B(OV.yb)}-${B(OV.b)})/${B(OV.rho)}))))`);
  put(OV.Pyt, 'P(yt)', `${B(OV.Pyb)}+2*${B(OV.rho)}*(ASIN(MAX(-1,MIN(1,IF(${B(OV.rho)}=0,0,(${B(OV.yt)}-${B(OV.b)})/${B(OV.rho)}))))-${B(OV.As2)})`);
  put(OV.S3yt, 'S3(yt) auxiliaire', S(B(OV.R), `2*${B(OV.R)}`, B(OV.yt)));
  put(OV.As3, 'asin3(yt) auxiliaire', `ASIN(MAX(-1,MIN(1,IF(${B(OV.R)}=0,0,(${B(OV.yt)}-2*${B(OV.R)})/${B(OV.R)}))))`);
  put(OV.Afull, 'A pleine section ovoïde', `${B(OV.Ayt)}+${B(OV.R)}^2*PI()/2-${B(OV.S3yt)}`);
  put(OV.Pfull, 'P plein ovoïde', `${B(OV.Pyt)}+2*${B(OV.R)}*(PI()/2-${B(OV.As3)})`);
  s.put('A', OV.mTrap, 'm trapèze = (B−b)/(2H)', ST.LABEL);
  s.put('B', OV.mTrap, F(
    `IF(Calcul!$B$${R.TH}="",0,IF(Calcul!$B$${R.TH}=0,0,(Calcul!$B$${R.TT}-Calcul!$B$${R.TB})/(2*Calcul!$B$${R.TH})))`,
    c.ov[OV.mTrap],
  ), ST.N4);

  // Profile list as real cells: a literal array constant inside MATCH is not
  // reliably evaluated, which silently fell back to profile 1 and froze the
  // whole workbook (results and chart) on the circular profile.
  s.put('E', 1, 'Liste des profils', ST.LABEL_B);
  PROFILE_NAMES.forEach((n, i) => s.put('E', 2 + i, n, ST.LABEL));

  // Chart operating points: x = ratio, y = filling ratio (%).
  s.put('A', CH.HEAD, 'Points du graphique', ST.SECTION);
  s.put('B', CH.HEAD, '', ST.SECTION);
  s.put('C', CH.HEAD, '', ST.SECTION);
  s.put('A', CH.HEAD + 1, 'Série', ST.THEAD);
  s.put('B', CH.HEAD + 1, 'x (ratio)', ST.THEAD);
  s.put('C', CH.HEAD + 1, 'y (remplissage %)', ST.THEAD);
  const cb = (row: number) => `Calcul!$B$${row}`;
  s.put('A', CH.GQ1, 'Q/Qc — solution basse', ST.LABEL);
  s.put('B', CH.GQ1, FN(`IF(OR(${cb(R.QL)}="",${cb(R.QCL)}=0),NA(),${cb(R.QL)}/${cb(R.QCL)})`, c.gq1), ST.N3);
  s.put('C', CH.GQ1, FN(`${cb(R.FLOW)}`, c.gy1), ST.N1);
  s.put('A', CH.GQ2, 'Q/Qc — solution haute', ST.LABEL);
  s.put('B', CH.GQ2, FN(`IF(${cb(R.BIC)}=1,${cb(R.QL)}/${cb(R.QCL)},NA())`, c.gq2), ST.N3);
  s.put('C', CH.GQ2, FN(`IF(${cb(R.BIC)}=1,${cb(R.FH)},NA())`, c.gy2), ST.N1);
  s.put('A', CH.GV1, 'V/Vc — solution basse', ST.LABEL);
  s.put('B', CH.GV1, FN(`IF(OR(${cb(R.QL)}="",${cb(R.VC)}=0),NA(),${cb(R.VLOW)}/${cb(R.VC)})`, c.gv1), ST.N3);
  s.put('C', CH.GV1, FN(`${cb(R.FLOW)}`, c.gy1), ST.N1);
  s.put('A', CH.GV2, 'V/Vc — solution haute', ST.LABEL);
  s.put('B', CH.GV2, FN(`IF(${cb(R.BIC)}=1,${cb(R.VH)}/${cb(R.VC)},NA())`, c.gv2), ST.N3);
  s.put('C', CH.GV2, FN(`IF(${cb(R.BIC)}=1,${cb(R.FH)},NA())`, c.gy2), ST.N1);

  return s.toSheet([34, 16, 18, 2, 30]);
}

// Range helpers on sheet "Courbe"
const CR = (col: string) => `Courbe!$${col}$${CURVE_FIRST}:$${col}$${CURVE_LAST}`;
const CIDX = (col: string, i: string | number) => `INDEX(${CR(col)},${i})`;

function buildCalcSheet(data: ExportData, c: Caches): XLSX.WorkSheet {
  const s = new SheetMap();
  const B = (row: number) => `B${row}`;
  const Fo = (row: number) => `${CONST_SHEET}!$B$${row}`;
  const section = (row: number, text: string) => {
    s.put('A', row, text, ST.SECTION);
    s.put('B', row, '', ST.SECTION);
    s.put('C', row, '', ST.SECTION);
    s.merge(`A${row}:C${row}`);
  };
  /** Label in A (+ unit in C); the value cell in B is written by the caller. */
  const label = (row: number, text: string, unit?: string, bold = false) => {
    s.put('A', row, text, bold ? ST.LABEL_B : ST.LABEL);
    s.put('C', row, unit ?? '', ST.UNIT);
  };

  s.put('A', R.TITLE, 'MANNING–STRICKLER — Note de calcul hydraulique', ST.TITLE);
  s.merge(`A${R.TITLE}:C${R.TITLE}`);
  s.put(
    'A',
    R.DATE,
    `Écoulement à surface libre · généré le ${new Date().toLocaleDateString('fr-FR')} · les cellules sur fond jaune sont modifiables`,
    ST.SUBTITLE,
  );
  s.merge(`A${R.DATE}:C${R.DATE}`);

  // --- 1) Profile and geometry ---
  section(R.SEC1, '1 · PROFIL ET GÉOMÉTRIE');
  label(R.PROFIL, 'Profil (liste déroulante)', '', true);
  s.put('B', R.PROFIL, PROFILE_NAMES[c.pidx - 1], ST.INPUT_TEXT);
  label(R.PIDX, 'Index du profil (auto)');
  // Plain nested text comparisons rather than MATCH over a range or an array
  // literal: MATCH silently fell back to profile 1 (via IFERROR), which froze
  // the entire workbook — results and chart — on the circular profile.
  const E = (i: number) => `${CONST_SHEET}!$E$${i}`;
  s.put('B', R.PIDX, F(
    `IF(B${R.PROFIL}=${E(3)},2,IF(B${R.PROFIL}=${E(4)},3,IF(B${R.PROFIL}=${E(5)},4,1)))`,
    c.pidx,
  ), ST.AUX);
  label(R.D, 'D — diamètre intérieur (circulaire)', 'm');
  s.put('B', R.D, c.dims.D > 0 ? c.dims.D : null, ST.INPUT_NUM);
  label(R.L, 'L — largeur (ovoïde ; hauteur = 1,5·L)', 'm');
  s.put('B', R.L, c.dims.L > 0 ? c.dims.L : null, ST.INPUT_NUM);
  label(R.RB, 'B — base (caniveau rectangulaire)', 'm');
  s.put('B', R.RB, c.dims.RB > 0 ? c.dims.RB : null, ST.INPUT_NUM);
  label(R.RH, 'H — hauteur (caniveau rectangulaire)', 'm');
  s.put('B', R.RH, c.dims.RH > 0 ? c.dims.RH : null, ST.INPUT_NUM);
  label(R.TB, 'b — petite base (trapèze)', 'm');
  s.put('B', R.TB, c.dims.TB > 0 ? c.dims.TB : null, ST.INPUT_NUM);
  label(R.TT, 'B — grande base (trapèze)', 'm');
  s.put('B', R.TT, c.dims.TT > 0 ? c.dims.TT : null, ST.INPUT_NUM);
  label(R.TH, 'H — hauteur (trapèze)', 'm');
  s.put('B', R.TH, c.dims.TH > 0 ? c.dims.TH : null, ST.INPUT_NUM);
  label(R.HMAX, 'Hauteur de la section pleine Hmax', 'm');
  s.put('B', R.HMAX, F(`CHOOSE(B${R.PIDX},B${R.D},1.5*B${R.L},B${R.RH},B${R.TH})`, c.Hmax), ST.N3);

  // --- 2) Material and hydraulic parameters ---
  section(R.SEC2, '2 · MATÉRIAU ET PARAMÈTRES HYDRAULIQUES');
  label(R.MAT, 'Matériau (liste déroulante)', '', true);
  s.put('B', R.MAT, data.materialName, ST.INPUT_TEXT);
  label(R.K, 'Coefficient de Strickler K', 'm^(1/3)/s');
  s.put('B', R.K, F(`IFERROR(VLOOKUP(B${R.MAT},'Matériaux'!$A$2:$B$12,2,0),${c.K || 80})`, c.K || 80), ST.N1);
  label(R.JPCT, 'Pente J', '%', true);
  s.put('B', R.JPCT, data.slopePct !== undefined ? data.slopePct : null, ST.INPUT_NUM);
  label(R.J, 'Pente J (ratio)', 'm/m');
  s.put('B', R.J, F(`IF(B${R.JPCT}="",0,B${R.JPCT}/100)`, c.J), ST.N4);
  label(R.QL, 'Débit Q', 'L/s', true);
  s.put('B', R.QL, data.Q_lps !== undefined ? data.Q_lps : null, ST.INPUT_NUM);
  label(R.QM, 'Débit Q', 'm³/s');
  s.put('B', R.QM, F(`IF(B${R.QL}="",0,B${R.QL}/1000)`, c.QM), ST.N4);

  // --- 3) Full section ---
  section(R.SEC3, '3 · SECTION PLEINE (remplissage 100 %)');
  label(R.A, 'Aire mouillée pleine A', 'm²');
  s.put('B', R.A, F(`CHOOSE(B${R.PIDX},PI()*B${R.D}^2/4,${Fo(OV.Afull)},B${R.RB}*B${R.RH},(B${R.TB}+B${R.TT})/2*B${R.TH})`, c.Afull), ST.N4);
  label(R.P, 'Périmètre mouillé plein P', 'm');
  s.put('B', R.P, F(`CHOOSE(B${R.PIDX},PI()*B${R.D},${Fo(OV.Pfull)},B${R.RB}+2*B${R.RH},B${R.TB}+2*SQRT(((B${R.TT}-B${R.TB})/2)^2+B${R.TH}^2))`, c.Pfull), ST.N3);
  label(R.RHY, 'Rayon hydraulique Rh = A / P', 'm');
  s.put('B', R.RHY, F(`IF(B${R.P}=0,0,B${R.A}/B${R.P})`, c.Rh), ST.N4);
  label(R.VC, 'Vitesse pleine section Vc = K·Rh^(2/3)·√J', 'm/s');
  s.put('B', R.VC, F(`B${R.K}*B${R.RHY}^(2/3)*SQRT(B${R.J})`, c.Vc), ST.N3);
  label(R.QCM, 'Débit critique Qc = Vc·A', 'm³/s');
  s.put('B', R.QCM, F(`B${R.VC}*B${R.A}`, c.QcM), ST.N4);
  label(R.QCL, 'Débit critique Qc', 'L/s', true);
  s.put('B', R.QCL, F(`B${R.QCM}*1000`, c.QcL), ST.KEY);
  label(R.QMAX, 'Débit maximal Qmax (maximum de la courbe)', 'L/s', true);
  s.put('B', R.QMAX, F(`MAX(${CR('Q')})`, c.QmaxL), ST.KEY);
  label(R.FQMAX, 'Remplissage à Qmax', '%');
  s.put('B', R.FQMAX, F(`IFERROR(${CIDX('A', `MATCH(B${R.QMAX},${CR('Q')},0)`)},100)`, c.peak), ST.N0);

  // --- 4) Operating point ---
  section(R.SEC4, '4 · POINT DE FONCTIONNEMENT');
  label(R.REGIME, 'Régime d’écoulement', '', true);
  // String-valued formula: written directly on the worksheet after toSheet().
  const regimeF = `IF(OR(B${R.QL}="",B${R.VC}=0),"",IF(B${R.QL}>B${R.QMAX},IF(B${R.PIDX}<=2,"EN CHARGE (Q > Qmax)","DÉBORDEMENT (Q > Qmax)"),IF(AND(B${R.PIDX}<=2,B${R.QL}>B${R.QCL}),"BICRITIQUE : 2 solutions","Écoulement à surface libre")))`;
  label(R.PEAK, '(aux.) ligne du pic');
  s.put('B', R.PEAK, F(`IFERROR(MATCH(B${R.QMAX},${CR('Q')},0),${N_ROWS})`, c.peak), ST.AUX);
  label(R.ILOW, '(aux.) i solution basse');
  s.put('B', R.ILOW, F(`MAX(${CR('T')})`, c.iLow), ST.AUX);
  label(R.TLOW, '(aux.) t interpolation basse');
  s.put('B', R.TLOW, F(
    `IF(OR(B${R.ILOW}=0,B${R.ILOW}>=B${R.PEAK}),0,(B${R.QL}-${CIDX('Q', `B${R.ILOW}`)})/(${CIDX('Q', `B${R.ILOW}+1`)}-${CIDX('Q', `B${R.ILOW}`)}))`,
    c.tLow,
  ), ST.AUX);
  label(R.FLOW, 'Taux de remplissage — solution basse', '%', true);
  s.put('B', R.FLOW, FN(
    `IF(OR(B${R.QL}="",B${R.VC}=0),NA(),IF(B${R.QL}>B${R.QMAX},100,IF(B${R.ILOW}=0,IFERROR(B${R.QL}/${CIDX('Q', 1)},0),${CIDX('A', `B${R.ILOW}`)}+B${R.TLOW})))`,
    c.fillLow,
  ), ST.KEY);
  label(R.ALOW, '(aux.) aire mouillée basse', 'm²');
  s.put('B', R.ALOW, F(
    `IF(B${R.QL}="",0,IF(B${R.QL}>B${R.QMAX},B${R.A},IF(B${R.ILOW}=0,${CIDX('M', 1)}*IFERROR(B${R.QL}/${CIDX('Q', 1)},0),${CIDX('M', `B${R.ILOW}`)}+B${R.TLOW}*(${CIDX('M', `MIN(B${R.ILOW}+1,${N_ROWS})`)}-${CIDX('M', `B${R.ILOW}`)}))))`,
    c.aLow,
  ), ST.AUX);
  label(R.VLOW, 'Vitesse d’écoulement — solution basse', 'm/s', true);
  s.put('B', R.VLOW, FN(`IF(OR(B${R.QL}="",B${R.ALOW}=0),NA(),B${R.QM}/B${R.ALOW})`, c.vLow), ST.KEY);
  label(R.BIC, '(aux.) régime bicritique ?');
  s.put('B', R.BIC, F(`IF(AND(B${R.PIDX}<=2,B${R.QL}<>"",B${R.QL}>B${R.QCL},B${R.QL}<=B${R.QMAX}),1,0)`, c.bic ? 1 : 0), ST.AUX);
  label(R.POSH, '(aux.) i haute brut');
  s.put('B', R.POSH, F(`MAX(${CR('U')})`, c.posH), ST.AUX);
  label(R.IH, '(aux.) i solution haute');
  s.put('B', R.IH, F(`IF(OR(B${R.BIC}=0,B${R.POSH}=0),B${R.PEAK},B${R.POSH})`, c.iH), ST.AUX);
  label(R.TH2, '(aux.) t interpolation haute');
  s.put('B', R.TH2, F(
    `IF(OR(B${R.BIC}=0,B${R.IH}>=${N_ROWS}),0,IFERROR((${CIDX('Q', `B${R.IH}`)}-B${R.QL})/(${CIDX('Q', `B${R.IH}`)}-${CIDX('Q', `B${R.IH}+1`)}),0))`,
    c.tH,
  ), ST.AUX);
  label(R.FH, 'Taux de remplissage — solution haute (bicritique)', '%', true);
  s.put('B', R.FH, FN(`IF(B${R.BIC}=1,${CIDX('A', `B${R.IH}`)}+B${R.TH2},NA())`, c.fillH), ST.KEY);
  label(R.AH, '(aux.) aire mouillée haute', 'm²');
  s.put('B', R.AH, F(
    `IF(B${R.BIC}=1,${CIDX('M', `B${R.IH}`)}+B${R.TH2}*(${CIDX('M', `MIN(B${R.IH}+1,${N_ROWS})`)}-${CIDX('M', `B${R.IH}`)}),0)`,
    c.aH,
  ), ST.AUX);
  label(R.VH, 'Vitesse d’écoulement — solution haute', 'm/s', true);
  s.put('B', R.VH, FN(`IF(OR(B${R.BIC}=0,B${R.AH}=0),NA(),B${R.QM}/B${R.AH})`, c.vH), ST.KEY);

  // --- 5) Sizing ---
  section(R.SEC5, '5 · DIMENSIONNEMENT');
  label(R.MINJ, 'Pente minimale (Q à pleine section)', '%', true);
  s.put('B', R.MINJ, FN(`IF(OR(B${R.QL}="",B${R.A}=0,B${R.K}=0),NA(),(B${R.QM}/(B${R.K}*B${R.A}*B${R.RHY}^(2/3)))^2*100)`, c.minJ), ST.KEY);
  label(R.MIND, 'Dimension minimale D / L / H (pente indiquée)', 'm', true);
  s.put('B', R.MIND, FN(
    `IF(OR(B${R.QL}="",B${R.QCM}=0),NA(),CHOOSE(B${R.PIDX},B${R.D},B${R.L},B${R.RH},B${R.TH})*(B${R.QM}/B${R.QCM})^(3/8))`,
    c.minDim,
  ), ST.KEY);

  // --- Notes + chart area ---
  section(R.SEC6, '6 · COURBES HYDRAULIQUES');
  s.put('A', R.NOTE1, 'V = K · Rh^(2/3) · J^(1/2)     Q = V · A     Rh = A / P', ST.SUBTITLE);
  s.merge(`A${R.NOTE1}:C${R.NOTE1}`);
  s.put(
    'A',
    R.NOTE2,
    'Zone bicritique (sections fermées) : entre Qc et Qmax, deux hauteurs d’eau transitent le même débit.',
    ST.SUBTITLE,
  );
  s.merge(`A${R.NOTE2}:C${R.NOTE2}`);
  // Reserve the chart rows so the print area covers them.
  s.put('A', R.CHART_BOTTOM, '', ST.DEFAULT);

  const ws = s.toSheet([46, 17, 12]);
  // Regime is a string-valued formula: cache as a formula-string cell
  ws[`B${R.REGIME}`] = { t: 'str', v: c.regime, f: regimeF } as any;
  (ws as any)._st.set(`B${R.REGIME}`, ST.REGIME);
  return ws;
}

function buildCurveSheet(data: ExportData, c: Caches): XLSX.WorkSheet {
  const s = new SheetMap();
  s.put('A', 1, 'COURBES HYDRAULIQUES — table à pas de 1 %', ST.TITLE);
  s.put('A', 2, 'Toutes les valeurs sont des formules ; un bloc de colonnes par profil, le bloc actif est choisi par l’index de profil.', ST.SUBTITLE);
  // Local parameter row, linked to the Calcul sheet
  s.put('A', 3, 'pidx', ST.LABEL_B);
  s.put('A', 4, F(`Calcul!$B$${R.PIDX}`, c.pidx), ST.N0);
  s.put('B', 3, 'Hmax (m)', ST.LABEL_B);
  s.put('B', 4, F(`Calcul!$B$${R.HMAX}`, c.Hmax), ST.N3);
  s.put('C', 3, 'K', ST.LABEL_B);
  s.put('C', 4, F(`Calcul!$B$${R.K}`, c.K), ST.N1);
  s.put('D', 3, 'J (m/m)', ST.LABEL_B);
  s.put('D', 4, F(`Calcul!$B$${R.J}`, c.J), ST.N4);

  const headers = ['Remplissage (%)', 'h (m)', 'θ circ (rad)', 'A circ', 'P circ', 'h ovoïde', 'A ovoïde', 'P ovoïde', 'A rect', 'P rect', 'A trap', 'P trap', 'A (m²)', 'P (m)', 'Rh (m)', 'V (m/s)', 'Q (L/s)', 'Q/Qc', 'V/Vc', 'idx bas', 'idx haut'];
  headers.forEach((h, i) => s.put(XLSX.utils.encode_col(i), 5, h, ST.THEAD));

  const Ca = (row: number) => `${CONST_SHEET}!$B$${row}`; // ovoid constants
  const S = (cc: string, yc: string, y: string) =>
    `(${y}-${yc})*SQRT(MAX(0,${cc}^2-(${y}-${yc})^2))+${cc}^2*ASIN(MAX(-1,MIN(1,IF(${cc}=0,0,(${y}-${yc})/${cc}))))`;

  for (let i = 1; i <= N_ROWS; i++) {
    const n = CURVE_FIRST + i - 1;
    const k = i - 1;
    s.put('A', n, i, ST.N0);
    s.put('B', n, F(`A${n}/100*$B$4`, c.hSel[k]), ST.N3);
    s.put('C', n, F(`2*ACOS(1-A${n}/50)`, c.theta[k]), ST.N3);
    s.put('D', n, F(`Calcul!$B$${R.D}^2/8*(C${n}-SIN(C${n}))`, c.circA[k]), ST.N4);
    s.put('E', n, F(`Calcul!$B$${R.D}*C${n}/2`, c.circP[k]), ST.N3);
    s.put('F', n, F(`A${n}/100*1.5*Calcul!$B$${R.L}`, c.hOv[k]), ST.N3);
    const h = `F${n}`;
    s.put('G', n, F(
      `IFERROR(IF(${Ca(OV.R)}=0,0,IF(${h}<=${Ca(OV.yb)},${S(Ca(OV.r), Ca(OV.r), h)}+${Ca(OV.r)}^2*PI()/2,IF(${h}<=${Ca(OV.yt)},${Ca(OV.Ayb)}+2*${Ca(OV.a)}*(${h}-${Ca(OV.yb)})+${S(Ca(OV.rho), Ca(OV.b), h)}-${Ca(OV.S2yb)},${Ca(OV.Ayt)}+${S(Ca(OV.R), `2*${Ca(OV.R)}`, h)}-${Ca(OV.S3yt)}))),0)`,
      c.ovA[k],
    ), ST.N4);
    s.put('H', n, F(
      `IFERROR(IF(${Ca(OV.R)}=0,0,IF(${h}<=${Ca(OV.yb)},2*${Ca(OV.r)}*(ASIN(MAX(-1,MIN(1,IF(${Ca(OV.r)}=0,0,(${h}-${Ca(OV.r)})/${Ca(OV.r)}))))+PI()/2),IF(${h}<=${Ca(OV.yt)},${Ca(OV.Pyb)}+2*${Ca(OV.rho)}*(ASIN(MAX(-1,MIN(1,(${h}-${Ca(OV.b)})/${Ca(OV.rho)})))-${Ca(OV.As2)}),${Ca(OV.Pyt)}+2*${Ca(OV.R)}*(ASIN(MAX(-1,MIN(1,(${h}-2*${Ca(OV.R)})/${Ca(OV.R)})))-${Ca(OV.As3)})))),0)`,
      c.ovP[k],
    ), ST.N3);
    s.put('I', n, F(`Calcul!$B$${R.RB}*(A${n}/100*Calcul!$B$${R.RH})`, c.rectA[k]), ST.N4);
    s.put('J', n, F(`Calcul!$B$${R.RB}+2*(A${n}/100*Calcul!$B$${R.RH})`, c.rectP[k]), ST.N3);
    s.put('K', n, F(
      `(Calcul!$B$${R.TB}+${CONST_SHEET}!$B$${OV.mTrap}*(A${n}/100*Calcul!$B$${R.TH}))*(A${n}/100*Calcul!$B$${R.TH})`,
      c.trapA[k],
    ), ST.N4);
    s.put('L', n, F(
      `Calcul!$B$${R.TB}+2*(A${n}/100*Calcul!$B$${R.TH})*SQRT(1+${CONST_SHEET}!$B$${OV.mTrap}^2)`,
      c.trapP[k],
    ), ST.N3);
    s.put('M', n, F(`CHOOSE($A$4,D${n},G${n},I${n},K${n})`, c.selA[k]), ST.N4);
    s.put('N', n, F(`CHOOSE($A$4,E${n},H${n},J${n},L${n})`, c.selP[k]), ST.N3);
    s.put('O', n, F(`IF(N${n}=0,0,M${n}/N${n})`, c.rh[k]), ST.N4);
    s.put('P', n, F(`$C$4*O${n}^(2/3)*SQRT($D$4)`, c.v[k]), ST.N3);
    s.put('Q', n, F(`P${n}*M${n}*1000`, c.q[k]), ST.N2);
    s.put('R', n, FN(`IFERROR(IF(Calcul!$B$${R.QCL}=0,NA(),Q${n}/Calcul!$B$${R.QCL}),NA())`, c.qRatio[k]), ST.N3);
    s.put('S', n, FN(`IFERROR(IF(Calcul!$B$${R.VC}=0,NA(),P${n}/Calcul!$B$${R.VC}),NA())`, c.vRatio[k]), ST.N3);
    // Helper flags for the operating-point lookup. Using a plain per-row test
    // plus MAX() avoids dynamic ranges (Q6:INDEX(...)), which Excel rejects,
    // and makes no assumption about the ordering of the discharge column.
    s.put('T', n, F(
      `IF(OR(Calcul!$B$${R.QL}="",A${n}>Calcul!$B$${R.PEAK},Q${n}>Calcul!$B$${R.QL}),0,A${n})`,
      c.tFlag[k],
    ), ST.N0);
    s.put('U', n, F(
      `IF(OR(Calcul!$B$${R.QL}="",A${n}<Calcul!$B$${R.PEAK},Q${n}<Calcul!$B$${R.QL}),0,A${n})`,
      c.uFlag[k],
    ), ST.N0);
  }
  return s.toSheet([13, 9, 10, 10, 9, 9, 10, 9, 10, 9, 10, 9, 10, 9, 9, 9, 10, 8, 8, 8, 8]);
}

// ---------------------------------------------------------------------------
// OOXML post-processing: styles, print setup, data validations, native chart
// ---------------------------------------------------------------------------

/** Per-sheet print configuration (A4). */
interface PrintCfg {
  landscape: boolean;
  printArea?: string; // e.g. "Calcul!$A$1:$C$88"
  printTitles?: string; // e.g. "Courbe!$5:$5"
  hiddenRows?: number[];
  rowHeights?: Record<number, number>;
  footerLeft: string;
}

/** Cells of a parsed <sheetData> row. */
interface PRow {
  open: string; // the full <row ...> opening tag
  cells: Map<number, string>; // column index -> cell XML
}

const colOf = (ref: string) => XLSX.utils.decode_cell(ref).c;

/**
 * Rewrite <sheetData>: attach style indices to existing cells, materialise
 * styled-but-empty cells (e.g. blank input cells that must still show their
 * yellow fill), hide auxiliary rows and set row heights.
 */
function applyStyles(
  sheetXml: string,
  styles: Map<string, number>,
  hiddenRows: number[] = [],
  rowHeights: Record<number, number> = {},
): string {
  const sdOpen = sheetXml.indexOf('<sheetData');
  if (sdOpen === -1) return sheetXml;
  const selfClosed = /^<sheetData\s*\/>/.test(sheetXml.slice(sdOpen));
  const sdEnd = selfClosed
    ? sheetXml.indexOf('>', sdOpen) + 1
    : sheetXml.indexOf('</sheetData>', sdOpen) + '</sheetData>'.length;
  const inner = selfClosed
    ? ''
    : sheetXml.slice(sheetXml.indexOf('>', sdOpen) + 1, sheetXml.indexOf('</sheetData>', sdOpen));

  const rows = new Map<number, PRow>();
  const rowRe = /<row\s([^>]*?)\/>|<row\s([^>]*?)>([\s\S]*?)<\/row>/g;
  let m: RegExpExecArray | null;
  while ((m = rowRe.exec(inner)) !== null) {
    const attrs = m[1] ?? m[2];
    const body = m[3] ?? '';
    const rNum = Number(/\br="(\d+)"/.exec(attrs)?.[1] ?? 0);
    if (!rNum) continue;
    const cells = new Map<number, string>();
    const cellRe = /<c\s[^>]*?\/>|<c\s[^>]*?>[\s\S]*?<\/c>/g;
    let cm: RegExpExecArray | null;
    while ((cm = cellRe.exec(body)) !== null) {
      const ref = /\br="([A-Z]+\d+)"/.exec(cm[0])?.[1];
      if (ref) cells.set(colOf(ref), cm[0]);
    }
    rows.set(rNum, { open: `<row ${attrs}>`, cells });
  }

  // Apply / create styled cells.
  for (const [addr, st] of styles) {
    if (st === ST.DEFAULT) continue;
    const { r, c } = XLSX.utils.decode_cell(addr);
    const rNum = r + 1;
    let row = rows.get(rNum);
    if (!row) {
      row = { open: `<row r="${rNum}">`, cells: new Map() };
      rows.set(rNum, row);
    }
    const existing = row.cells.get(c);
    if (existing) {
      const withoutStyle = existing.replace(/\ss="\d+"/, '');
      row.cells.set(c, withoutStyle.replace(/^<c\s/, `<c s="${st}" `));
    } else {
      row.cells.set(c, `<c r="${addr}" s="${st}"/>`);
    }
  }

  // Row attributes: hidden + custom heights.
  const hidden = new Set(hiddenRows);
  for (const rNum of new Set([...hidden, ...Object.keys(rowHeights).map(Number)])) {
    let row = rows.get(rNum);
    if (!row) {
      row = { open: `<row r="${rNum}">`, cells: new Map() };
      rows.set(rNum, row);
    }
    let open = row.open.replace(/\shidden="\d"/, '').replace(/\sht="[\d.]+"/, '').replace(/\scustomHeight="\d"/, '');
    const extra =
      (hidden.has(rNum) ? ' hidden="1"' : '') +
      (rowHeights[rNum] ? ` ht="${rowHeights[rNum]}" customHeight="1"` : '');
    row.open = open.replace(/>$/, `${extra}>`);
  }

  const rebuilt = [...rows.keys()]
    .sort((a, b) => a - b)
    .map((rNum) => {
      const row = rows.get(rNum)!;
      const body = [...row.cells.keys()].sort((a, b) => a - b).map((cc) => row.cells.get(cc)).join('');
      return body ? `${row.open}${body}</row>` : `${row.open}</row>`;
    })
    .join('');

  return sheetXml.slice(0, sdOpen) + `<sheetData>${rebuilt}</sheetData>` + sheetXml.slice(sdEnd);
}

/** Insert a block respecting CT_Worksheet's element order. */
function insertBefore(sheetXml: string, block: string, followers: string[]): string {
  let anchor = sheetXml.length;
  for (const tag of followers) {
    const p = sheetXml.indexOf(tag);
    if (p !== -1 && p < anchor) anchor = p;
  }
  return sheetXml.slice(0, anchor) + block + sheetXml.slice(anchor);
}

/** A4 page setup, margins and header/footer for one worksheet. */
function applyPrintSetup(sheetXml: string, cfg: PrintCfg): string {
  let xml = sheetXml.replace(/<pageMargins[^>]*\/>/g, '').replace(/<pageSetup[^>]*\/>/g, '');
  // <sheetPr> must be the first child of <worksheet>.
  if (!xml.includes('<sheetPr')) {
    xml = xml.replace(/(<worksheet[^>]*>)/, `$1<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>`);
  }
  const block =
    `<printOptions horizontalCentered="1"/>` +
    `<pageMargins left="0.55" right="0.4" top="0.7" bottom="0.65" header="0.3" footer="0.3"/>` +
    `<pageSetup paperSize="9" orientation="${cfg.landscape ? 'landscape' : 'portrait'}" fitToWidth="1" fitToHeight="0" horizontalDpi="300" verticalDpi="300"/>` +
    `<headerFooter><oddHeader>&amp;L&amp;"Calibri,Bold"&amp;12Manning–Strickler&amp;R&amp;9&amp;D</oddHeader>` +
    `<oddFooter>&amp;L&amp;9${cfg.footerLeft}&amp;R&amp;9Page &amp;P / &amp;N</oddFooter></headerFooter>`;
  return insertBefore(xml, block, [
    '<rowBreaks',
    '<colBreaks',
    '<customProperties',
    '<cellWatches',
    '<ignoredErrors',
    '<smartTags',
    '<drawing',
    '</worksheet>',
  ]);
}

/** Hand-written styles.xml matching the ST.* indices. */
function buildStylesXml(): string {
  const numFmts =
    `<numFmts count="5">` +
    `<numFmt numFmtId="164" formatCode="0.000"/>` +
    `<numFmt numFmtId="165" formatCode="0.0"/>` +
    `<numFmt numFmtId="166" formatCode="0"/>` +
    `<numFmt numFmtId="167" formatCode="0.0000"/>` +
    `<numFmt numFmtId="168" formatCode="0.00"/>` +
    `</numFmts>`;
  const fonts =
    `<fonts count="9">` +
    `<font><sz val="11"/><color theme="1"/><name val="Calibri"/></font>` +
    `<font><b/><sz val="11"/><color rgb="FF1F4E79"/><name val="Calibri"/></font>` +
    `<font><b/><sz val="16"/><color rgb="FF1F4E79"/><name val="Calibri"/></font>` +
    `<font><i/><sz val="9"/><color rgb="FF767676"/><name val="Calibri"/></font>` +
    `<font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>` +
    `<font><b/><sz val="12"/><color rgb="FF1F4E79"/><name val="Calibri"/></font>` +
    `<font><i/><sz val="9"/><color rgb="FF767676"/><name val="Calibri"/></font>` +
    `<font><b/><sz val="10"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>` +
    `<font><b/><sz val="11"/><color rgb="FF9C2A2A"/><name val="Calibri"/></font>` +
    `</fonts>`;
  const fills =
    `<fills count="8">` +
    `<fill><patternFill patternType="none"/></fill>` +
    `<fill><patternFill patternType="gray125"/></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FF1F4E79"/><bgColor indexed="64"/></patternFill></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FFDCE6F1"/><bgColor indexed="64"/></patternFill></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FFFFF2CC"/><bgColor indexed="64"/></patternFill></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FFF2F2F2"/><bgColor indexed="64"/></patternFill></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FFFDF3E7"/><bgColor indexed="64"/></patternFill></fill>` +
    `<fill><patternFill patternType="solid"><fgColor rgb="FFEAF3FB"/><bgColor indexed="64"/></patternFill></fill>` +
    `</fills>`;
  const thin = `<left style="thin"><color rgb="FFBFBFBF"/></left><right style="thin"><color rgb="FFBFBFBF"/></right><top style="thin"><color rgb="FFBFBFBF"/></top><bottom style="thin"><color rgb="FFBFBFBF"/></bottom>`;
  const borders =
    `<borders count="3">` +
    `<border><left/><right/><top/><bottom/><diagonal/></border>` +
    `<border>${thin}<diagonal/></border>` +
    `<border><left/><right/><top/><bottom style="medium"><color rgb="FF1F4E79"/></bottom><diagonal/></border>` +
    `</borders>`;
  // cellXfs — index order must match ST.*
  const xf = (
    numFmtId: number,
    fontId: number,
    fillId: number,
    borderId: number,
    align?: string,
  ) =>
    `<xf numFmtId="${numFmtId}" fontId="${fontId}" fillId="${fillId}" borderId="${borderId}" applyNumberFormat="1" applyFont="1" applyFill="1" applyBorder="1"${align ? ` applyAlignment="1"><alignment ${align}/></xf>` : '/>'}`;
  const cellXfs =
    `<cellXfs count="19">` +
    xf(0, 0, 0, 0) + // 0 DEFAULT
    xf(0, 2, 0, 2, 'vertical="center"') + // 1 TITLE
    xf(0, 3, 0, 0, 'vertical="center" wrapText="1"') + // 2 SUBTITLE
    xf(0, 4, 2, 0, 'vertical="center"') + // 3 SECTION
    xf(0, 0, 0, 1, 'vertical="center" wrapText="1"') + // 4 LABEL
    xf(164, 1, 4, 1, 'horizontal="right" vertical="center"') + // 5 INPUT_NUM
    xf(0, 1, 4, 1, 'vertical="center"') + // 6 INPUT_TEXT
    xf(164, 5, 3, 1, 'horizontal="right" vertical="center"') + // 7 RESULT
    xf(0, 6, 0, 1, 'horizontal="center" vertical="center"') + // 8 UNIT
    xf(0, 7, 2, 1, 'horizontal="center" vertical="center" wrapText="1"') + // 9 THEAD
    xf(164, 0, 0, 1, 'horizontal="right"') + // 10 N3
    xf(165, 0, 0, 1, 'horizontal="right"') + // 11 N1
    xf(166, 0, 0, 1, 'horizontal="right"') + // 12 N0
    xf(0, 8, 6, 1, 'vertical="center" wrapText="1"') + // 13 REGIME
    xf(167, 3, 5, 1, 'horizontal="right"') + // 14 AUX
    xf(167, 0, 0, 1, 'horizontal="right"') + // 15 N4
    xf(0, 1, 0, 1, 'vertical="center" wrapText="1"') + // 16 LABEL_B
    xf(168, 0, 0, 1, 'horizontal="right"') + // 17 N2
    xf(164, 5, 7, 1, 'horizontal="right" vertical="center"') + // 18 KEY
    `</cellXfs>`;
  return (
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">` +
    numFmts +
    fonts +
    fills +
    borders +
    `<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>` +
    cellXfs +
    `<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>` +
    `<dxfs count="0"/><tableStyles count="0" defaultTableStyle="TableStyleMedium9"/>` +
    `</styleSheet>`
  );
}

async function injectValidationAndChart(
  base64: string,
  c: Caches,
  styleMaps: Map<string, number>[],
): Promise<string> {
  const zip = await JSZip.loadAsync(base64, { base64: true });

  // Auxiliary rows on "Calcul" are hidden so the printed note stays readable.
  const auxRows = [R.PIDX, R.PEAK, R.ILOW, R.TLOW, R.ALOW, R.BIC, R.POSH, R.IH, R.TH2, R.AH];
  const prints: PrintCfg[] = [
    {
      landscape: false,
      printArea: `Calcul!$A$1:$C$${R.CHART_BOTTOM}`,
      hiddenRows: auxRows,
      rowHeights: { [R.TITLE]: 26, [R.DATE]: 26, [R.SEC1]: 20, [R.SEC2]: 20, [R.SEC3]: 20, [R.SEC4]: 20, [R.SEC5]: 20, [R.SEC6]: 20, [R.REGIME]: 20 },
      footerLeft: 'Note de calcul — écoulement à surface libre',
    },
    {
      landscape: true,
      printArea: `Courbe!$A$1:$S$${CURVE_LAST}`,
      printTitles: `Courbe!$5:$5`,
      rowHeights: { 5: 30 },
      footerLeft: 'Table hydraulique (pas de 1 %)',
    },
    { landscape: false, printArea: `Matériaux!$A$1:$B$12`, footerLeft: 'Coefficients de Strickler' },
    { landscape: false, footerLeft: 'Valeurs auxiliaires' },
  ];

  for (let i = 0; i < 4; i++) {
    const path = `xl/worksheets/sheet${i + 1}.xml`;
    const file = zip.file(path);
    if (!file) continue;
    let xml = await file.async('string');
    const cfg = prints[i];
    xml = applyStyles(xml, styleMaps[i] ?? new Map(), cfg.hiddenRows, cfg.rowHeights);

    // Data validations (dropdowns) belong to the Calcul sheet only.
    if (i === 0) {
      const validations =
        `<dataValidations count="2">` +
        `<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="B${R.PROFIL}"><formula1>${CONST_SHEET}!$E$2:$E$5</formula1></dataValidation>` +
        `<dataValidation type="list" allowBlank="1" showInputMessage="1" showErrorMessage="1" sqref="B${R.MAT}"><formula1>'Matériaux'!$A$2:$A$12</formula1></dataValidation>` +
        `</dataValidations>`;
      xml = insertBefore(xml, validations, [
        '<hyperlinks',
        '<printOptions',
        '<pageMargins',
        '<pageSetup',
        '<headerFooter',
        '<rowBreaks',
        '<colBreaks',
        '<customProperties',
        '<cellWatches',
        '<ignoredErrors',
        '<smartTags',
        '<drawing',
        '</worksheet>',
      ]);
    }

    xml = applyPrintSetup(xml, cfg);

    if (i === 0) {
      xml = xml.replace('</worksheet>', `<drawing r:id="rIdDrw1"/></worksheet>`);
      if (!xml.includes('xmlns:r=')) {
        xml = xml.replace(
          '<worksheet ',
          '<worksheet xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" ',
        );
      }
    }
    zip.file(path, xml);
  }

  // Sheet1 relationships -> drawing
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

  // Drawing part: the chart sits under the table, inside the A4 print width.
  zip.file(
    'xl/drawings/drawing1.xml',
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
      `<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
      `<xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${R.CHART_TOP - 1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>` +
      `<xdr:to><xdr:col>3</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>${R.CHART_BOTTOM - 1}</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>` +
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

  // Chart part
  zip.file('xl/charts/chart1.xml', buildChartXml(c));

  // Custom styles (SheetJS's community build writes a minimal styles.xml).
  zip.file('xl/styles.xml', buildStylesXml());

  // Workbook: print areas / repeated header rows, then force a full recalc so
  // no cached value can be stale when Excel opens the file.
  const wbPath = 'xl/workbook.xml';
  let wbXml = await zip.file(wbPath)!.async('string');
  const defs: string[] = [];
  prints.forEach((cfg, i) => {
    if (cfg.printArea) {
      defs.push(`<definedName name="_xlnm.Print_Area" localSheetId="${i}">${cfg.printArea}</definedName>`);
    }
    if (cfg.printTitles) {
      defs.push(`<definedName name="_xlnm.Print_Titles" localSheetId="${i}">${cfg.printTitles}</definedName>`);
    }
  });
  if (defs.length && !wbXml.includes('<definedNames')) {
    wbXml = wbXml.replace('</sheets>', `</sheets><definedNames>${defs.join('')}</definedNames>`);
  }
  if (!wbXml.includes('<calcPr')) {
    wbXml = wbXml.replace('</workbook>', `<calcPr calcId="171027" fullCalcOnLoad="1"/></workbook>`);
  }
  zip.file(wbPath, wbXml);

  // Content types
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
  // Operating-point coordinates live on the "Constantes" sheet (x in B, y in C)
  // so that they follow every input change, profile switch included.
  const series =
    scatterSeries(0, 'Q/Qc (débit)', `Courbe!$R$${CURVE_FIRST}:$R$${CURVE_LAST}`, `Courbe!$A$${CURVE_FIRST}:$A$${CURVE_LAST}`, c.qRatio, fills, '2F7DD1', false) +
    scatterSeries(1, 'V/Vc (vitesse)', `Courbe!$S$${CURVE_FIRST}:$S$${CURVE_LAST}`, `Courbe!$A$${CURVE_FIRST}:$A$${CURVE_LAST}`, c.vRatio, fills, 'E07A3F', false) +
    scatterSeries(2, 'Point Q/Qc', `${CONST_SHEET}!$B$${CH.GQ1}:$B$${CH.GQ2}`, `${CONST_SHEET}!$C$${CH.GQ1}:$C$${CH.GQ2}`, [c.gq1, c.gq2], [c.gy1, c.gy2], '2F7DD1', true) +
    scatterSeries(3, 'Point V/Vc', `${CONST_SHEET}!$B$${CH.GV1}:$B$${CH.GV2}`, `${CONST_SHEET}!$C$${CH.GV1}:$C$${CH.GV2}`, [c.gv1, c.gv2], [c.gy1, c.gy2], 'D12F4F', true);

  return (
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>` +
    `<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">` +
    `<c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>Courbes hydrauliques — V/Vc et Q/Qc vs remplissage (%)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title><c:autoTitleDeleted val="0"/>` +
    `<c:plotArea><c:layout/><c:scatterChart><c:scatterStyle val="smoothMarker"/><c:varyColors val="0"/>${series}` +
    `<c:axId val="111111111"/><c:axId val="222222222"/></c:scatterChart>` +
    `<c:valAx><c:axId val="111111111"/><c:scaling><c:orientation val="minMax"/><c:min val="0"/></c:scaling><c:delete val="0"/><c:axPos val="b"/><c:majorGridlines/>` +
    `<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>V/Vc et Q/Qc</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>` +
    `<c:numFmt formatCode="0.0" sourceLinked="0"/><c:majorTickMark val="out"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="222222222"/></c:valAx>` +
    `<c:valAx><c:axId val="222222222"/><c:scaling><c:orientation val="minMax"/><c:max val="100"/><c:min val="0"/></c:scaling><c:delete val="0"/><c:axPos val="l"/><c:majorGridlines/>` +
    `<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>Taux de remplissage (%)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>` +
    `<c:numFmt formatCode="0" sourceLinked="0"/><c:majorTickMark val="out"/><c:minorTickMark val="none"/><c:tickLblPos val="nextTo"/><c:crossAx val="111111111"/></c:valAx>` +
    `</c:plotArea><c:legend><c:legendPos val="b"/><c:overlay val="0"/></c:legend><c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>`
  );
}
