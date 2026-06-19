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
}

export interface OperatingState {
  fill: number; // filling ratio 0..1 (capped display done in UI)
  V: number; // flow velocity at the operating point (m/s)
  y: number; // water depth (m)
  surcharged: boolean; // true if Q > Qc (pipe "en charge")
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
  if (K && K > 0 && J && J > 0) {
    const Vc = K * Math.pow(R_f, TWO_THIRDS) * Math.sqrt(J);
    out.full = { Q: Vc * A_f, V: Vc, A: A_f, P: P_f, R: R_f };
  }

  // --- Minimum slope to carry Q at full (needs K + flow; slope not required) ---
  if (K && K > 0 && Q && Q > 0) {
    const denom = K * A_f * Math.pow(R_f, TWO_THIRDS);
    if (denom > 0) {
      const sqrtJ = Q / denom;
      out.minSlope = sqrtJ * sqrtJ;
    }
  }

  // --- Operating point + minimum size (needs K + slope + flow) ---
  if (K && K > 0 && J && J > 0 && Q && Q > 0) {
    const Qfull = out.full!.Q;
    const surcharged = Q > Qfull;

    if (surcharged) {
      // Pressurised: water fills the section, velocity from full area.
      out.operating = { fill: 1, V: Q / A_f, y: geometry.yMax, surcharged: true };
    } else {
      // Find the first (lowest) depth whose discharge reaches Q.
      let yOp = geometry.yMax;
      let prevQ = 0;
      let prevY = 0;
      for (let i = 1; i < rows.length; i++) {
        const qi = rowDischarge(rows[i].A, rows[i].P, K, J);
        if (qi >= Q) {
          const t = qi === prevQ ? 0 : (Q - prevQ) / (qi - prevQ);
          yOp = prevY + t * (rows[i].y - prevY);
          break;
        }
        prevQ = qi;
        prevY = rows[i].y;
      }
      const Aop = areaAtDepth(geometry, yOp);
      out.operating = {
        fill: yOp / geometry.yMax,
        V: Aop > 0 ? Q / Aop : 0,
        y: yOp,
        surcharged: false,
      };
    }

    // Minimum size: scale all linear dimensions by s so the section runs full
    // at exactly Q. Q scales as s^(8/3) → s = (Q / Qfull)^(3/8).
    const scale = Math.pow(Q / Qfull, 3 / 8);
    out.minSize = {
      scale,
      value: geometry.principalDim * scale,
      label: geometry.principalLabel,
    };
  }

  return out;
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
