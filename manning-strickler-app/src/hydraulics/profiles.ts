/**
 * Cross-section geometry for open-channel / closed-conduit flow.
 *
 * Each profile is described by a single-valued half-width function x(y), where
 * `x` is the horizontal half-width of the wetted section at vertical level `y`
 * (measured from the invert / bottom point, y = 0).  From that we build a
 * cumulative geometry table that the hydraulic engine consumes:
 *
 *   A(y)  flow area              = 2 * integral_0^y x(eta) d(eta)
 *   P(y)  wetted perimeter       = bottomWidth + 2 * (boundary arc length 0..y)
 *   T(y)  free-surface top width = 2 * x(y)
 *
 * Closed conduits (circular, ovoid) have x(0) = 0 (pointed/curved invert) so
 * the flat-bottom term vanishes automatically.  Open channels (rectangular,
 * trapezoidal) have a flat bottom of width 2*x(0+).
 */

export type ProfileId = 'circular' | 'ovoid' | 'rectangular' | 'trapezoidal';

export interface ProfileParams {
  // circular (closed) — all lengths in metres
  diameter?: number; // D

  // ovoid (closed) — classic three-centre "ovoïde normalisé"
  ovoidWidth?: number; // largeur L (height H = 1.5 * L)

  // rectangular open channel
  rectWidth?: number; // B  (base width)
  rectHeight?: number; // H (wall / channel height)

  // trapezoidal open channel (regular / symmetric)
  trapBottom?: number; // petite base b
  trapTop?: number; // grande base T (> b)
  trapHeight?: number; // hauteur H
}

export interface GeomRow {
  y: number; // level above invert (m)
  A: number; // cumulative flow area (m²)
  P: number; // cumulative wetted perimeter (m)
  T: number; // top (free-surface) width at this level (m)
}

export interface GeomTable {
  yMax: number; // full / brim level (m)
  rows: GeomRow[]; // monotonically increasing y, cumulative A & P
  principalDim: number; // characteristic linear dimension (for size scaling)
  principalLabel: string; // e.g. "Diamètre", "Largeur ovoïde", "Hauteur"
  closed: boolean; // closed conduit vs open channel
}

const N_STEPS = 400;

/** Build a geometry table from a single-valued half-width function. */
function buildTable(
  yMax: number,
  halfWidth: (y: number) => number,
  flatBottom: boolean,
  principalDim: number,
  principalLabel: string,
  closed: boolean,
): GeomTable {
  const rows: GeomRow[] = [];
  let A = 0;
  let P = flatBottom ? 2 * halfWidth(1e-9) : 0; // flat-bottom segment
  let prevX = halfWidth(0);
  let prevY = 0;
  rows.push({ y: 0, A: 0, P, T: 2 * prevX });

  for (let i = 1; i <= N_STEPS; i++) {
    const y = (yMax * i) / N_STEPS;
    const x = halfWidth(y);
    const dy = y - prevY;
    A += (x + prevX) * dy; // 2 * trapezoidal integral of x dy
    P += 2 * Math.hypot(x - prevX, dy); // both walls (chord length)
    rows.push({ y, A, P, T: 2 * x });
    prevX = x;
    prevY = y;
  }
  return { yMax, rows, principalDim, principalLabel, closed };
}

// --- Circular ---------------------------------------------------------------
function circularTable(D: number): GeomTable {
  const R = D / 2;
  const halfWidth = (y: number) => Math.sqrt(Math.max(0, R * R - (y - R) * (y - R)));
  return buildTable(D, halfWidth, false, D, 'Diamètre', true);
}

// --- Ovoid (classic three-centre "ovoïde normalisé", H = 1.5 L) -------------
function ovoidTable(L: number): GeomTable {
  const R = L / 2; // radius of top large semicircle (width = 2R = L)
  const r = R / 3; // radius of bottom invert circle
  const rho = 3 * R; // radius of the two side arcs
  const Cb = { x: 0, y: r }; // bottom circle centre
  const Ct = { x: 0, y: 2 * R }; // top circle centre

  // Side-arc centre (a, b), a < 0 for the right-hand boundary.
  const b = (21 * R) / 10;
  const a = -Math.sqrt(Math.max(0, 4 * R * R - (b - 2 * R) * (b - 2 * R)));
  const Cs = { x: a, y: b };

  // Tangency (junction) points: along the line of centres, distance rho from Cs.
  const junction = (to: { x: number; y: number }) => {
    const dx = to.x - Cs.x;
    const dy = to.y - Cs.y;
    const len = Math.hypot(dx, dy);
    return { x: Cs.x + (rho * dx) / len, y: Cs.y + (rho * dy) / len };
  };
  const yb = junction(Cb).y; // bottom-circle / side-arc junction
  const yt = junction(Ct).y; // side-arc / top-circle junction
  const yMax = 3 * R; // total height = 1.5 L

  const halfWidth = (y: number) => {
    if (y <= 0 || y >= yMax) return 0;
    if (y <= yb) return Math.sqrt(Math.max(0, r * r - (y - r) * (y - r)));
    if (y <= yt) return a + Math.sqrt(Math.max(0, rho * rho - (y - b) * (y - b)));
    return Math.sqrt(Math.max(0, R * R - (y - 2 * R) * (y - 2 * R)));
  };
  return buildTable(yMax, halfWidth, false, L, 'Largeur ovoïde', true);
}

// --- Rectangular open channel ----------------------------------------------
function rectangularTable(B: number, H: number): GeomTable {
  const halfWidth = () => B / 2;
  return buildTable(H, halfWidth, true, H, 'Hauteur', false);
}

// --- Trapezoidal open channel (regular / symmetric) ------------------------
function trapezoidalTable(b: number, T: number, H: number): GeomTable {
  const m = (T / 2 - b / 2) / H; // horizontal run per unit height
  const halfWidth = (y: number) => b / 2 + m * y;
  return buildTable(H, halfWidth, true, H, 'Hauteur', false);
}

/** Build the geometry table for the selected profile, or null if params invalid. */
export function buildGeometry(profile: ProfileId, p: ProfileParams): GeomTable | null {
  switch (profile) {
    case 'circular':
      return p.diameter && p.diameter > 0 ? circularTable(p.diameter) : null;
    case 'ovoid':
      return p.ovoidWidth && p.ovoidWidth > 0 ? ovoidTable(p.ovoidWidth) : null;
    case 'rectangular':
      return p.rectWidth && p.rectWidth > 0 && p.rectHeight && p.rectHeight > 0
        ? rectangularTable(p.rectWidth, p.rectHeight)
        : null;
    case 'trapezoidal':
      return p.trapBottom !== undefined &&
        p.trapTop &&
        p.trapHeight &&
        p.trapTop > 0 &&
        p.trapHeight > 0 &&
        p.trapBottom >= 0 &&
        p.trapTop >= p.trapBottom
        ? trapezoidalTable(p.trapBottom, p.trapTop, p.trapHeight)
        : null;
    default:
      return null;
  }
}
