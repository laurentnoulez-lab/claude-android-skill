/**
 * Excel (.xlsx) export with REAL formulas written into the cells.
 *
 * Sheet "Calcul": input cells (dimensions, K, J, Q) and result cells whose
 * formulas reference the inputs — editing an input in Excel recomputes the
 * whole sheet. Sheet "Courbe": the hydraulic-elements table (one row per
 * filling ratio) also built from formulas, so Q/Qc and V/Vc update too.
 *
 * Every formula cell also carries its cached numeric value: SheetJS drops
 * formula-only cells at write time, and Excel needs a cached value to display
 * before the first recalculation.
 *
 * Only the operating point (filling ratio at the given Q) is exported as a
 * numeric value: the discharge curve of a closed conduit is non-monotonic in
 * the bicritical band, so that inversion has no closed-form spreadsheet
 * formula and is solved numerically by the app.
 *
 * Formulas use English function names (ACOS, SQRT, PI…) as stored in the file
 * format; Excel displays them translated in the user's locale.
 */

import * as FileSystem from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import * as XLSX from 'xlsx';
import { ExportData, dimensionRows, fnum } from './exportData';

type Cell = string | number | { f: string; v: number } | null;

/** Formula cell with its cached (displayed) value. */
function F(f: string, v: number | undefined): { f: string; v: number } {
  return { f, v: v !== undefined && Number.isFinite(v) ? v : 0 };
}

export async function exportExcel(data: ExportData): Promise<void> {
  const wb = XLSX.utils.book_new();
  const calc = buildCalcSheet(data);
  XLSX.utils.book_append_sheet(wb, calc.sheet, 'Calcul');
  XLSX.utils.book_append_sheet(wb, buildCurveSheet(data, calc.refs), 'Courbe');

  const b64 = XLSX.write(wb, { type: 'base64', bookType: 'xlsx' });
  const uri = `${FileSystem.cacheDirectory}manning-strickler.xlsx`;
  await FileSystem.writeAsStringAsync(uri, b64, { encoding: FileSystem.EncodingType.Base64 });
  if (await Sharing.isAvailableAsync()) {
    await Sharing.shareAsync(uri, {
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      dialogTitle: 'Exporter le classeur Excel',
    });
  }
}

/** Addresses (on sheet "Calcul") that the curve sheet needs to reference. */
interface CalcRefs {
  dimRows: number[]; // rows of the dimension cells, in dimensionRows() order
  kRow: number;
  jRatioRow: number;
  vcRow: number;
  qcLpsRow: number;
}

function buildCalcSheet(data: ExportData): { sheet: XLSX.WorkSheet; refs: CalcRefs } {
  const r = data.results;
  const geom = r.geometry;
  const last = geom ? geom.rows[geom.rows.length - 1] : undefined;
  const Afull = last?.A;
  const Pfull = last?.P;
  const Rh = Afull !== undefined && Pfull ? Afull / Pfull : undefined;
  const jRatio = data.slopePct !== undefined ? data.slopePct / 100 : undefined;
  const qM3 = data.Q_lps !== undefined ? data.Q_lps / 1000 : undefined;

  const rows: Cell[][] = [];
  const add = (cells: Cell[]): number => {
    rows.push(cells);
    return rows.length; // 1-based row index
  };
  const B = (row: number) => `B${row}`;

  add(['Manning–Strickler — Rapport de calcul', null, null]);
  add([`Édité le ${new Date().toLocaleDateString('fr-FR')}`, null, null]);
  add([]);
  add(['DONNÉES D’ENTRÉE (cellules modifiables : le classeur recalcule)', null, null]);
  add(['Profil', data.profileLabel, null]);

  const dimRows: number[] = [];
  for (const [label, v] of dimensionRows(data)) {
    dimRows.push(add([`${label}`, Number.isFinite(v) ? v : '', 'm']));
  }
  add(['Matériau', data.materialName, null]);
  const kRow = add(['Coefficient de Strickler K', data.K ?? '', 'm^(1/3)/s']);
  const jPctRow = add(['Pente J', data.slopePct ?? '', '%']);
  const jRatioRow = add(['Pente J (ratio m/m)', F(`${B(jPctRow)}/100`, jRatio), 'm/m']);
  const qLpsRow = add(['Débit Q', data.Q_lps ?? '', 'L/s']);
  const qM3Row = add(['Débit Q (m³/s)', F(`${B(qLpsRow)}/1000`, qM3), 'm³/s']);

  add([]);
  add(['SECTION PLEINE (remplissage 100 %)', null, null]);
  const geomFull = fullSectionFormulas(data, dimRows.map(B), Afull, Pfull);
  const aRow = add(['Aire mouillée pleine A', geomFull.A, 'm²']);
  const pRow = add(['Périmètre mouillé plein P', geomFull.P, 'm']);
  const rhRow = add(['Rayon hydraulique Rh = A/P', F(`${B(aRow)}/${B(pRow)}`, Rh), 'm']);
  const vcRow = add([
    'Vitesse pleine section Vc = K·Rh^(2/3)·√J',
    F(`${B(kRow)}*${B(rhRow)}^(2/3)*SQRT(${B(jRatioRow)})`, r.full?.V),
    'm/s',
  ]);
  const qcM3Row = add(['Débit critique Qc = Vc·A', F(`${B(vcRow)}*${B(aRow)}`, r.full?.Q), 'm³/s']);
  const qcLpsRow = add([
    'Débit critique Qc',
    F(`${B(qcM3Row)}*1000`, r.full ? r.full.Q * 1000 : undefined),
    'L/s',
  ]);
  if (r.full) {
    add([
      `Débit maximal Qmax (à ${fnum(r.full.fillAtQmax * 100, 1)} % — courbe non monotone)`,
      Number((r.full.Qmax * 1000).toFixed(2)),
      'L/s (valeur)',
    ]);
  }

  add([]);
  add(['DIMENSIONNEMENT', null, null]);
  add([
    'Pente minimale (Q à pleine section) = (Q/(K·A·Rh^(2/3)))²',
    F(
      `(${B(qM3Row)}/(${B(kRow)}*${B(aRow)}*${B(rhRow)}^(2/3)))^2*100`,
      r.minSlope !== undefined ? r.minSlope * 100 : undefined,
    ),
    '%',
  ]);
  add([
    `${r.minSize ? r.minSize.label : 'Dimension'} minimale = dim·(Q/Qc)^(3/8)`,
    F(`${B(dimRows[0])}*(${B(qM3Row)}/${B(qcM3Row)})^(3/8)`, r.minSize?.value),
    'm',
  ]);

  add([]);
  add(['POINT DE FONCTIONNEMENT (résolution numérique de Q(h)=Q, valeurs)', null, null]);
  if (r.operating) {
    const o = r.operating;
    if (o.surcharged) {
      add(['Taux de remplissage', '≥ 100 % — capacité dépassée', null]);
      add(['Vitesse (section pleine)', Number(o.V.toFixed(3)), 'm/s']);
    } else {
      add(['Taux de remplissage (solution basse)', Number((o.fill * 100).toFixed(1)), '%']);
      add(['Vitesse d’écoulement (solution basse)', Number(o.V.toFixed(3)), 'm/s']);
      if (o.bicritical && o.fillAlt !== undefined && o.VAlt !== undefined) {
        add(['Taux de remplissage (solution haute — bicritique)', Number((o.fillAlt * 100).toFixed(1)), '%']);
        add(['Vitesse d’écoulement (solution haute)', Number(o.VAlt.toFixed(3)), 'm/s']);
        add(['Régime', 'BICRITIQUE : deux hauteurs possibles pour ce débit (Qc < Q ≤ Qmax)', null]);
      } else {
        add(['Régime', 'Écoulement à surface libre', null]);
      }
    }
  } else {
    add(['(Complétez K, J et Q pour obtenir le point de fonctionnement)', null, null]);
  }

  const sheet = sheetFromRows(rows, [52, 24, 14]);
  return { sheet, refs: { dimRows, kRow, jRatioRow, vcRow, qcLpsRow } };
}

/** Full-section A and P: formulas per profile (numeric values for the ovoid). */
function fullSectionFormulas(
  data: ExportData,
  dim: string[],
  Afull: number | undefined,
  Pfull: number | undefined,
): { A: Cell; P: Cell } {
  switch (data.profile) {
    case 'circular':
      return { A: F(`PI()*${dim[0]}^2/4`, Afull), P: F(`PI()*${dim[0]}`, Pfull) };
    case 'rectangular':
      return { A: F(`${dim[0]}*${dim[1]}`, Afull), P: F(`${dim[0]}+2*${dim[1]}`, Pfull) };
    case 'trapezoidal':
      return {
        A: F(`(${dim[0]}+${dim[1]})/2*${dim[2]}`, Afull),
        P: F(`${dim[0]}+2*SQRT(((${dim[1]}-${dim[0]})/2)^2+${dim[2]}^2)`, Pfull),
      };
    case 'ovoid':
      // Three-centre ovoid: no simple closed formula — exact numeric values.
      return {
        A: Afull !== undefined ? Number(Afull.toFixed(6)) : '',
        P: Pfull !== undefined ? Number(Pfull.toFixed(6)) : '',
      };
    default:
      return { A: '', P: '' };
  }
}

function buildCurveSheet(data: ExportData, refs: CalcRefs): XLSX.WorkSheet {
  const geometry = data.results.geometry;
  const curve = data.results.curve;
  const K = data.K;
  const J = data.slopePct !== undefined ? data.slopePct / 100 : undefined;
  const Vc = data.results.full?.V;
  const QcLps = data.results.full ? data.results.full.Q * 1000 : undefined;

  const rows: Cell[][] = [];
  const add = (cells: Cell[]): number => {
    rows.push(cells);
    return rows.length;
  };

  add(['Courbes hydrauliques — une ligne par taux de remplissage', null]);
  add(['Formules dans les cellules : modifiez les entrées de la feuille Calcul pour recalculer.', null]);
  add([]);

  // Local parameter block (formulas pointing at the Calcul sheet).
  const dims = dimensionRows(data);
  const pRows: number[] = [];
  dims.forEach(([label, v], i) => {
    pRows.push(add([label, F(`Calcul!B${refs.dimRows[i]}`, v)]));
  });
  const kRow = add(['K', F(`Calcul!B${refs.kRow}`, K)]);
  const jRow = add(['J (ratio)', F(`Calcul!B${refs.jRatioRow}`, J)]);
  // Trapezoid slope-of-wall helper m = (B−b)/(2H).
  let mRow = 0;
  let mVal = 0;
  if (data.profile === 'trapezoidal') {
    const [b, T, H] = [dims[0][1], dims[1][1], dims[2][1]];
    mVal = Number.isFinite(b) && Number.isFinite(T) && H > 0 ? (T - b) / (2 * H) : 0;
    mRow = add(['m = (B−b)/(2H)', F(`(B${pRows[1]}-B${pRows[0]})/(2*B${pRows[2]})`, mVal)]);
  }
  add([]);

  const circular = data.profile === 'circular';
  const header = circular
    ? ['Remplissage (%)', 'h (m)', 'θ (rad)', 'A (m²)', 'P (m)', 'Rh (m)', 'V (m/s)', 'Q (L/s)', 'Q/Qc', 'V/Vc']
    : ['Remplissage (%)', 'h (m)', 'A (m²)', 'P (m)', 'Rh (m)', 'V (m/s)', 'Q (L/s)', 'Q/Qc', 'V/Vc'];
  add(header);

  const Kref = `$B$${kRow}`;
  const Jref = `$B$${jRow}`;

  for (let fillPct = 5; fillPct <= 100; fillPct += 5) {
    const r = rows.length + 1; // row this data line will occupy
    // Column letters: with θ (circular) A..J, otherwise A..I.
    const [colH, colTheta, colA, colP, colRh, colV, colQ] = circular
      ? ['B', 'C', 'D', 'E', 'F', 'G', 'H']
      : ['B', '', 'C', 'D', 'E', 'F', 'G'];

    // Cached values from the engine's geometry table (uniform steps).
    const yMax = geometry ? geometry.yMax : 0;
    const idx = geometry ? Math.round((fillPct / 100) * (geometry.rows.length - 1)) : 0;
    const grow = geometry ? geometry.rows[idx] : undefined;
    const h = (fillPct / 100) * yMax;
    const A = grow?.A ?? 0;
    const P = grow?.P ?? 0;
    const Rh = P > 0 ? A / P : 0;
    const V = K !== undefined && J !== undefined ? K * Math.pow(Rh, 2 / 3) * Math.sqrt(J) : undefined;
    const QLps = V !== undefined ? V * A * 1000 : undefined;
    const cpt = curve[idx];

    const line: Cell[] = [fillPct];
    switch (data.profile) {
      case 'circular': {
        const D = `$B$${pRows[0]}`;
        const dVal = dims[0][1];
        const theta = dVal > 0 ? 2 * Math.acos(1 - (2 * h) / dVal) : 0;
        line.push(F(`${D}*A${r}/100`, h)); // h
        line.push(F(`2*ACOS(1-2*${colH}${r}/${D})`, theta)); // θ
        line.push(F(`${D}^2/8*(${colTheta}${r}-SIN(${colTheta}${r}))`, A)); // A
        line.push(F(`${D}*${colTheta}${r}/2`, P)); // P
        break;
      }
      case 'rectangular': {
        const Bb = `$B$${pRows[0]}`;
        line.push(F(`$B$${pRows[1]}*A${r}/100`, h));
        line.push(F(`${Bb}*${colH}${r}`, A));
        line.push(F(`${Bb}+2*${colH}${r}`, P));
        break;
      }
      case 'trapezoidal': {
        const b = `$B$${pRows[0]}`;
        const m = `$B$${mRow}`;
        line.push(F(`$B$${pRows[2]}*A${r}/100`, h));
        line.push(F(`(${b}+${m}*${colH}${r})*${colH}${r}`, A));
        line.push(F(`${b}+2*${colH}${r}*SQRT(1+${m}^2)`, P));
        break;
      }
      case 'ovoid': {
        // Geometry integrated numerically (three-centre profile): A and P are
        // exact numeric values; everything downstream stays formula-based.
        line.push(Number(h.toFixed(5)));
        line.push(Number(A.toFixed(6)));
        line.push(Number(P.toFixed(6)));
        break;
      }
    }

    line.push(F(`IF(${colP}${r}=0,0,${colA}${r}/${colP}${r})`, Rh)); // Rh
    line.push(F(`${Kref}*${colRh}${r}^(2/3)*SQRT(${Jref})`, V)); // V
    line.push(F(`${colV}${r}*${colA}${r}*1000`, QLps)); // Q (L/s)
    line.push(F(`${colQ}${r}/Calcul!$B$${refs.qcLpsRow}`, cpt?.qRatio)); // Q/Qc
    line.push(F(`${colV}${r}/Calcul!$B$${refs.vcRow}`, cpt?.vRatio)); // V/Vc
    add(line);
  }

  return sheetFromRows(rows, circular ? [16, 10, 10, 11, 11, 10, 10, 10, 8, 8] : [16, 10, 11, 11, 10, 10, 10, 8, 8]);
}

/** Build a worksheet from a grid of values / {f, v} formula cells. */
function sheetFromRows(rows: Cell[][], colWidths: number[]): XLSX.WorkSheet {
  const ws: XLSX.WorkSheet = {};
  let maxCol = 0;
  rows.forEach((line, ri) => {
    line.forEach((cell, ci) => {
      if (cell === null || cell === '') return;
      const addr = XLSX.utils.encode_cell({ r: ri, c: ci });
      if (typeof cell === 'object') ws[addr] = { t: 'n', f: cell.f, v: cell.v };
      else if (typeof cell === 'number') ws[addr] = { t: 'n', v: cell };
      else ws[addr] = { t: 's', v: cell };
      maxCol = Math.max(maxCol, ci);
    });
  });
  ws['!ref'] = XLSX.utils.encode_range({ s: { r: 0, c: 0 }, e: { r: rows.length - 1, c: maxCol } });
  ws['!cols'] = colWidths.map((wch) => ({ wch }));
  return ws;
}
