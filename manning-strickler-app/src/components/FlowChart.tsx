import React from 'react';
import { View, Text, StyleSheet } from 'react-native';
import Svg, { Line, Polyline, Circle, Text as SvgText, Rect } from 'react-native-svg';
import { CurvePoint } from '../hydraulics/engine';

interface Props {
  width: number;
  curve: CurvePoint[];
  /** Operating point as ratios, fill in 0..1; null when not computable. */
  operating: { fill: number; vRatio: number; qRatio: number } | null;
}

const V_COLOR = '#e07a3f'; // V/Vc curve
const Q_COLOR = '#2f7dd1'; // Q/Qc curve
const POINT_COLOR = '#d12f4f';

const fin = (x: number) => (Number.isFinite(x) ? x : 0);
const clamp = (x: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, fin(x)));

/**
 * Hydraulic-elements diagram: filling ratio (% on the Y axis) versus the
 * normalised ratios V/Vc and Q/Qc (X axis). Both curves share the same grid.
 * The user's operating point is shown on each curve. All coordinates are kept
 * finite and clamped so malformed inputs can never feed NaN to the SVG layer.
 */
export default function FlowChart({ width, curve, operating }: Props) {
  const height = 320;
  const padL = 44;
  const padR = 16;
  const padT = 16;
  const padB = 40;
  const plotW = Math.max(40, width - padL - padR);
  const plotH = height - padT - padB;

  // Keep only finite curve points.
  const pts = curve
    .filter((p) => Number.isFinite(p.fill) && Number.isFinite(p.vRatio) && Number.isFinite(p.qRatio))
    .map((p) => ({
      fill: clamp(p.fill, 0, 1),
      vRatio: Math.max(0, fin(p.vRatio)),
      qRatio: Math.max(0, fin(p.qRatio)),
    }));

  // X axis range from data, capped so extreme inputs cannot explode the grid.
  let xMaxData = 1;
  for (const p of pts) xMaxData = Math.max(xMaxData, p.vRatio, p.qRatio);
  if (operating) xMaxData = Math.max(xMaxData, fin(operating.vRatio), fin(operating.qRatio));
  const xMax = clamp(Math.ceil(xMaxData / 0.2) * 0.2, 1.2, 2.6);

  const sx = (r: number) => clamp(padL + (fin(r) / xMax) * plotW, padL, padL + plotW);
  const sy = (fill: number) => clamp(padT + (1 - clamp(fill, 0, 1)) * plotH, padT, padT + plotH);

  const vPts = pts.map((p) => `${sx(p.vRatio)},${sy(p.fill)}`).join(' ');
  const qPts = pts.map((p) => `${sx(p.qRatio)},${sy(p.fill)}`).join(' ');

  // Bounded tick set (step chosen so there are never more than ~13 ticks).
  const step = xMax <= 1.4 ? 0.2 : xMax <= 2.0 ? 0.25 : 0.5;
  const xTicks: number[] = [];
  for (let t = 0; t <= xMax + 1e-9 && xTicks.length < 20; t += step) {
    xTicks.push(Math.round(t * 100) / 100);
  }
  const yTicks = [0, 20, 40, 60, 80, 100];

  // Operating point clamped onto the plot; flag when it lies beyond the axis.
  const opBeyond = operating ? Math.max(operating.vRatio, operating.qRatio) > xMax + 1e-6 : false;

  return (
    <View>
      <Svg width={width} height={height}>
        <Rect x={padL} y={padT} width={plotW} height={plotH} fill="#fafafa" stroke="#ddd" />

        {xTicks.map((t) => (
          <React.Fragment key={`x${t}`}>
            <Line x1={sx(t)} y1={padT} x2={sx(t)} y2={padT + plotH} stroke="#eee" />
            <SvgText x={sx(t)} y={height - padB + 16} fontSize={10} fill="#666" textAnchor="middle">
              {t.toFixed(t < 10 ? 1 : 0)}
            </SvgText>
          </React.Fragment>
        ))}

        {yTicks.map((t) => (
          <React.Fragment key={`y${t}`}>
            <Line x1={padL} y1={sy(t / 100)} x2={padL + plotW} y2={sy(t / 100)} stroke="#eee" />
            <SvgText x={padL - 6} y={sy(t / 100) + 3} fontSize={10} fill="#666" textAnchor="end">
              {t}
            </SvgText>
          </React.Fragment>
        ))}

        {/* reference line at ratio = 1 */}
        {xMax >= 1 && (
          <Line x1={sx(1)} y1={padT} x2={sx(1)} y2={padT + plotH} stroke="#bbb" strokeDasharray="4 3" />
        )}

        {qPts.length > 0 && <Polyline points={qPts} fill="none" stroke={Q_COLOR} strokeWidth={2} />}
        {vPts.length > 0 && <Polyline points={vPts} fill="none" stroke={V_COLOR} strokeWidth={2} />}

        {operating && (
          <>
            <Line
              x1={padL}
              y1={sy(operating.fill)}
              x2={padL + plotW}
              y2={sy(operating.fill)}
              stroke={POINT_COLOR}
              strokeDasharray="5 3"
              strokeWidth={1}
            />
            <Circle cx={sx(operating.qRatio)} cy={sy(operating.fill)} r={5} fill={Q_COLOR} stroke="#fff" />
            <Circle cx={sx(operating.vRatio)} cy={sy(operating.fill)} r={5} fill={V_COLOR} stroke="#fff" />
          </>
        )}

        <SvgText x={padL + plotW / 2} y={height - 4} fontSize={11} fill="#333" textAnchor="middle">
          V/Vc et Q/Qc
        </SvgText>
      </Svg>

      <View style={styles.legend}>
        <Legend color={V_COLOR} label="V/Vc (vitesse)" />
        <Legend color={Q_COLOR} label="Q/Qc (débit)" />
        <Legend color={POINT_COLOR} label="Point d'écoulement" />
      </View>
      <Text style={styles.axisNote}>Ordonnée : taux de remplissage (%)</Text>
      {opBeyond && (
        <Text style={styles.axisNote}>
          (point d'écoulement au-delà de l'axe : débit critique largement dépassé)
        </Text>
      )}
    </View>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <View style={styles.legendItem}>
      <View style={[styles.swatch, { backgroundColor: color }]} />
      <Text style={styles.legendText}>{label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  legend: { flexDirection: 'row', flexWrap: 'wrap', justifyContent: 'center', marginTop: 6 },
  legendItem: { flexDirection: 'row', alignItems: 'center', marginHorizontal: 8, marginVertical: 2 },
  swatch: { width: 12, height: 12, borderRadius: 2, marginRight: 4 },
  legendText: { fontSize: 12, color: '#444' },
  axisNote: { fontSize: 11, color: '#666', textAlign: 'center', marginTop: 2 },
});
