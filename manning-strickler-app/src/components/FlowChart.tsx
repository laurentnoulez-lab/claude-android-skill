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

/**
 * Hydraulic-elements diagram: filling ratio (% on the Y axis) versus the
 * normalised ratios V/Vc and Q/Qc (X axis). Both curves share the same grid.
 * The user's operating point is shown on each curve.
 */
export default function FlowChart({ width, curve, operating }: Props) {
  const height = 320;
  const padL = 44;
  const padR = 16;
  const padT = 16;
  const padB = 40;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  // X axis range from data (round up a bit so curves & point fit).
  let xMaxData = 1;
  for (const p of curve) xMaxData = Math.max(xMaxData, p.vRatio, p.qRatio);
  if (operating) xMaxData = Math.max(xMaxData, operating.vRatio, operating.qRatio);
  const xMax = Math.max(1.2, Math.ceil(xMaxData * 10) / 10);

  const sx = (r: number) => padL + (r / xMax) * plotW;
  const sy = (fill: number) => padT + (1 - fill) * plotH; // fill 0..1 -> bottom..top

  const vPts = curve.map((p) => `${sx(p.vRatio)},${sy(p.fill)}`).join(' ');
  const qPts = curve.map((p) => `${sx(p.qRatio)},${sy(p.fill)}`).join(' ');

  // Grid ticks
  const xTicks: number[] = [];
  for (let t = 0; t <= xMax + 1e-9; t += 0.2) xTicks.push(Math.round(t * 100) / 100);
  const yTicks = [0, 20, 40, 60, 80, 100];

  return (
    <View>
      <Svg width={width} height={height}>
        {/* plot background */}
        <Rect x={padL} y={padT} width={plotW} height={plotH} fill="#fafafa" stroke="#ddd" />

        {/* vertical grid + x labels */}
        {xTicks.map((t) => (
          <React.Fragment key={`x${t}`}>
            <Line x1={sx(t)} y1={padT} x2={sx(t)} y2={padT + plotH} stroke="#eee" />
            <SvgText x={sx(t)} y={height - padB + 16} fontSize={10} fill="#666" textAnchor="middle">
              {t.toFixed(1)}
            </SvgText>
          </React.Fragment>
        ))}

        {/* horizontal grid + y labels */}
        {yTicks.map((t) => (
          <React.Fragment key={`y${t}`}>
            <Line x1={padL} y1={sy(t / 100)} x2={padL + plotW} y2={sy(t / 100)} stroke="#eee" />
            <SvgText x={padL - 6} y={sy(t / 100) + 3} fontSize={10} fill="#666" textAnchor="end">
              {t}
            </SvgText>
          </React.Fragment>
        ))}

        {/* reference line at ratio = 1 */}
        <Line x1={sx(1)} y1={padT} x2={sx(1)} y2={padT + plotH} stroke="#bbb" strokeDasharray="4 3" />

        {/* curves */}
        {qPts.length > 0 && <Polyline points={qPts} fill="none" stroke={Q_COLOR} strokeWidth={2} />}
        {vPts.length > 0 && <Polyline points={vPts} fill="none" stroke={V_COLOR} strokeWidth={2} />}

        {/* operating point */}
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

        {/* axis titles */}
        <SvgText x={padL + plotW / 2} y={height - 4} fontSize={11} fill="#333" textAnchor="middle">
          V/Vc  et  Q/Qc
        </SvgText>
      </Svg>

      <View style={styles.legend}>
        <Legend color={V_COLOR} label="V/Vc (vitesse)" />
        <Legend color={Q_COLOR} label="Q/Qc (débit)" />
        <Legend color={POINT_COLOR} label="Point d'écoulement" />
      </View>
      <Text style={styles.axisNote}>Ordonnée : taux de remplissage (%)</Text>
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
