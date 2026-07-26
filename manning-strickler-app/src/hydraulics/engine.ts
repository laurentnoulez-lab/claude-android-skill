/**
 * Manning–Strickler hydraulic engine.
 *
 *   V = K · Rh^(2/3) · J^(1/2)         (velocity, m/s)
 *   Q = V · A                          (discharge, m³/s)
 *
 * with K the Strickler coefficient (m^(1/3)/s), Rh = A/P the hydraulic radius,
 * J the longitudinal slope (m/m), A the flow area and P the wetted perimeter.
 *
 * "Critical / full discharge" Qc is the discharge at 100 % filling (the conduit
 * running just full, or the open channel filled to the brim).
 */

import { GeomTable, ProfileId, ProfileParams, buildGeometry } from './profiles';

export interface EngineInputs {
  profile: ProfileId;
  params: ProfileParams;
  K?: number; // Strickler coefficient
  slope?: number; // J (m/m)
  flow?: number; // Q (m³/s)
}

export interface FullState {
  Q: number; // capacity at 100 % fill (m³/s)
  V: number; // velocity at 100 % fill (m/s)
  A: number; // full area (m²)
  P: number; // full perimeter (m)
  R: number; // full hydraulic radius (m)
  Qmax: number; // true maximum free-surface discharge (m³/s), ≈1.076·Q at ~94 % for a circle
  fillAtQmax: number; // filling ratio (0..1) where Qmax occurs
}

/** Free-surface flow regime from the Froude number. */
export type FlowRegime = 'fluvial' | 'critique' | 'torrentiel';

export interface OperatingState {
  fill: number; // filling ratio 0..1 (lowest solution)
  V: number; // flow velocity at the operating point (m/s)
  y: number; // water depth (m)
  surcharged: boolean; // true if Q > Qmax (pipe "en charge" / channel overflowing)
  bicritical: boolean; // true when a second free-surface solution exists (Qc < Q ≤ Qmax)
  fillAlt?: number; // alternate (higher) filling ratio in the bicritical band
  VAlt?: number; // velocity at the alternate operating point (m/s)
  yAlt?: number; // alternate water depth (m)
  // Froude number Fr = V / sqrt(g·Dh) with Dh = A/T the hydraulic (mean) depth
  // and T the free-surface top width. Undefined when there is no free surface
  // (pressurised flow) or when the surface closes on itself (T -> 0 at the
  // crown of a closed conduit), where Fr is not meaningful.
  froude?: number;
  regime?: FlowRegime;
  topWidth?: number; // T at the operating point (m)
  froudeAlt?: number; // Froude of the alternate (bicritical) solution
  regimeAlt?: FlowRegime;
}

export interface CurvePoint {
  fill: number; // 0..1
  vRatio: number; // V / Vc
  qRatio: number; // Q / Qc
}

export interface EngineResults {
  geometry: GeomTable | null;
  full?: FullState; // needs geometry + K + slope
  operating?: OperatingState; // needs geometry + K + slope + flow
  minSlope?: number; // min slope to carry Q at full (needs geometry + K + flow)
  minSize?: { scale: number; value: number; label: string }; // needs geometry + K + slope + flow
  curve: CurvePoint[]; // hydraulic-elements curves (geometry only)
}

const TWO_THIRDS = 2 / 3;
/** Standard gravity (m/s²). */
const G = 9.81;
/** Half-width of the band around Fr = 1 reported as "critique". */
const CRITICAL_BAND = 0.02;

/** Classify a Froude number into the usual French open-channel regimes. */
export function regimeOf(fr: number): FlowRegime {
  if (fr > 1 + CRITICAL_BAND) return 'torrentiel';
  if (fr < 1 - CRITICAL_BAND) return 'fluvial';
  return 'critique';
}

/** True for a finite, strictly-positive number. */
function isPos(x: number | undefined): x is number {
  return x !== undefined && Number.isFinite(x) && x > 0;
}

const clamp01 = (x: number) => Math.min(1, Math.max(0, x));

/** Discharge at a given table row, given K and slope. */
function rowDischarge(A: number, P: number, K: number, slope: number): number {
  if (A <= 0 || P <= 0) return 0;
  const R = A / P;
  return K * Math.pow(R, TWO_THIRDS) * Math.sqrt(slope) * A;
}

export function computeResults(inputs: EngineInputs): EngineResults {
  const geometry = buildGeometry(inputs.profile, inputs.params);
  const out: EngineResults = { geometry, curve: [] };
  if (!geometry) return out;

  const rows = geometry.rows;
  const last = rows[rows.length - 1];
  const A_f = last.A;
  const P_f = last.P;
  const R_f = A_f / P_f;

  const K = inputs.K;
  const J = inputs.slope;
  const Q = inputs.flow;

  // --- Hydraulic-elements curves (geometry only; K and J cancel in ratios) ---
  // Reference at 100 % fill.
  const Vref = Math.pow(R_f, TWO_THIRDS); // V_full / (K·√J)
  const Qref = Vref * A_f; // Q_full / (K·√J)
  for (const row of rows) {
    if (row.A <= 0 || row.P <= 0) {
      out.curve.push({ fill: row.y / geometry.yMax, vRatio: 0, qRatio: 0 });
      continue;
    }
    const v = Math.pow(row.A / row.P, TWO_THIRDS);
    const q = v * row.A;
    out.curve.push({
      fill: row.y / geometry.yMax,
      vRatio: v / Vref,
      qRatio: q / Qref,
    });
  }

  // --- Full / critical state (needs K + slope) ---
  if (isPos(K) && isPos(J) && A_f > 0 && P_f > 0) {
    const Vc = K! * Math.pow(R_f, TWO_THIRDS) * Math.sqrt(J!);
    const Qc = Vc * A_f;
    if (Number.isFinite(Vc) && Number.isFinite(Qc)) {
      // True maximum free-surface discharge. For closed conduits Q(y) peaks
      // below the crown (≈94 % for a circle) then falls back to Qc at 100 %:
      // that non-monotonic stretch is the bicritical band [Qc, Qmax].
      let Qmax = Qc;
      let fillAtQmax = 1;
      for (const row of rows) {
        const qi = rowDischarge(row.A, row.P, K!, J!);
        if (qi > Qmax) {
          Qmax = qi;
          fillAtQmax = row.y / geometry.yMax;
        }
      }
      out.full = { Q: Qc, V: Vc, A: A_f, P: P_f, R: R_f, Qmax, fillAtQmax };
    }
  }

  // --- Minimum slope to carry Q at full (needs K + flow; slope not required) ---
  if (isPos(K) && isPos(Q)) {
    const denom = K! * A_f * Math.pow(R_f, TWO_THIRDS);
    const sqrtJ = denom > 0 ? Q! / denom : NaN;
    const j = sqrtJ * sqrtJ;
    if (Number.isFinite(j)) out.minSlope = j;
  }

  // --- Operating point + minimum size (needs K + slope + flow) ---
  if (isPos(K) && isPos(J) && isPos(Q) && out.full && out.full.Q > 0) {
    const Qfull = out.full.Q;
    const surcharged = Q! > out.full.Qmax;

    if (surcharged) {
      // Beyond the true maximum: pressurised (closed) / overflowing (open).
      out.operating = {
        fill: 1,
        V: A_f > 0 ? Q! / A_f : 0,
        y: geometry.yMax,
        surcharged: true,
        bicritical: false,
      };
    } else {
      // Q(y) is non-monotonic for closed conduits, so collect EVERY depth
      // where the discharge curve crosses Q. In the bicritical band
      // (Qc < Q ≤ Qmax) this yields two solutions: one on the rising branch
      // (~80–94 %) and one on the falling branch (94–100 %).
      const sols: number[] = [];
      let prevQ = 0;
      for (let i = 1; i < rows.length; i++) {
        const qi = rowDischarge(rows[i].A, rows[i].P, K!, J!);
        if ((prevQ < Q! && qi >= Q!) || (prevQ >= Q! && qi < Q!)) {
          const t = qi === prevQ ? 0 : (Q! - prevQ) / (qi - prevQ);
          sols.push(rows[i - 1].y + t * (rows[i].y - rows[i - 1].y));
        }
        prevQ = qi;
      }
      const yOp = sols.length > 0 ? sols[0] : geometry.yMax;
      const yAlt = sols.length > 1 ? sols[sols.length - 1] : undefined;
      // Ignore a numerically-duplicated crossing right at the peak.
      const distinctAlt =
        yAlt !== undefined && Math.abs(yAlt - yOp) / geometry.yMax > 0.005 ? yAlt : undefined;

      const Aop = areaAtDepth(geometry, yOp);
      const Vop = Aop > 0 ? Q! / Aop : 0;
      const fLow = froudeAt(geometry, yOp, Aop, Vop);
      out.operating = {
        fill: clamp01(yOp / geometry.yMax),
        V: Vop,
        y: yOp,
        surcharged: false,
        bicritical: distinctAlt !== undefined,
        topWidth: fLow.T,
        froude: fLow.fr,
        regime: fLow.fr !== undefined ? regimeOf(fLow.fr) : undefined,
      };
      if (distinctAlt !== undefined) {
        const Aalt = areaAtDepth(geometry, distinctAlt);
        const Valt = Aalt > 0 ? Q! / Aalt : 0;
        const fAlt = froudeAt(geometry, distinctAlt, Aalt, Valt);
        out.operating.fillAlt = clamp01(distinctAlt / geometry.yMax);
        out.operating.VAlt = Valt;
        out.operating.yAlt = distinctAlt;
        out.operating.froudeAlt = fAlt.fr;
        out.operating.regimeAlt = fAlt.fr !== undefined ? regimeOf(fAlt.fr) : undefined;
      }
    }

    // Minimum size: scale all linear dimensions by s so the section runs full
    // at exactly Q. Q scales as s^(8/3) → s = (Q / Qfull)^(3/8).
    const scale = Math.pow(Q! / Qfull, 3 / 8);
    if (Number.isFinite(scale) && scale > 0) {
      out.minSize = {
        scale,
        value: geometry.principalDim * scale,
        label: geometry.principalLabel,
      };
    }
  }

  return out;
}

/** Interpolate the free-surface top width at an arbitrary depth. */
function topWidthAtDepth(geometry: GeomTable, y: number): number {
  const rows = geometry.rows;
  if (y <= 0) return rows[0].T;
  if (y >= geometry.yMax) return rows[rows.length - 1].T;
  for (let i = 1; i < rows.length; i++) {
    if (rows[i].y >= y) {
      const t = (y - rows[i - 1].y) / (rows[i].y - rows[i - 1].y);
      return rows[i - 1].T + t * (rows[i].T - rows[i - 1].T);
    }
  }
  return rows[rows.length - 1].T;
}

/** Froude number at a given depth, or undefined where it is not meaningful. */
function froudeAt(geometry: GeomTable, y: number, A: number, V: number) {
  const T = topWidthAtDepth(geometry, y);
  if (!(T > 1e-9) || !(A > 0) || !Number.isFinite(V)) return { T, fr: undefined };
  const Dh = A / T;
  const fr = V / Math.sqrt(G * Dh);
  return { T, fr: Number.isFinite(fr) ? fr : undefined };
}

/** Interpolate flow area at an arbitrary depth from the geometry table. */
function areaAtDepth(geometry: GeomTable, y: number): number {
  const rows = geometry.rows;
  if (y <= 0) return 0;
  if (y >= geometry.yMax) return rows[rows.length - 1].A;
  for (let i = 1; i < rows.length; i++) {
    if (rows[i].y >= y) {
      const t = (y - rows[i - 1].y) / (rows[i].y - rows[i - 1].y);
      return rows[i - 1].A + t * (rows[i].A - rows[i - 1].A);
    }
  }
  return rows[rows.length - 1].A;
}
