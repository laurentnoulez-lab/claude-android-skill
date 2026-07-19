import React, { useMemo, useState } from 'react';
import {
  SafeAreaView,
  ScrollView,
  View,
  Text,
  StyleSheet,
  useWindowDimensions,
  StatusBar,
  Platform,
  Pressable,
  Alert,
} from 'react-native';
import { Picker } from '@react-native-picker/picker';
import Field, { parseNum } from './src/components/Field';
import FlowChart, { OperatingChartPoint } from './src/components/FlowChart';
import ErrorBoundary from './src/components/ErrorBoundary';
import { MATERIALS } from './src/hydraulics/materials';
import { ProfileId, ProfileParams } from './src/hydraulics/profiles';
import { computeResults } from './src/hydraulics/engine';
import { ExportData } from './src/export/exportData';
import { exportPdf } from './src/export/pdf';
import { exportExcel } from './src/export/excel';

const PROFILES: { id: ProfileId; label: string }[] = [
  { id: 'circular', label: 'Circulaire fermé' },
  { id: 'ovoid', label: 'Ovoïde fermé (classique)' },
  { id: 'rectangular', label: 'Caniveau rectangulaire (ciel ouvert)' },
  { id: 'trapezoidal', label: 'Caniveau trapézoïdal (ciel ouvert)' },
];

export default function App() {
  const { width } = useWindowDimensions();

  const [profile, setProfile] = useState<ProfileId>('circular');
  const [materialId, setMaterialId] = useState<string>('pvc');
  const [kText, setKText] = useState<string>('100');

  // Dimension inputs (strings, kept per-field so switching profiles preserves entries)
  const [diameter, setDiameter] = useState(''); // mm
  const [ovoidWidth, setOvoidWidth] = useState(''); // mm
  const [rectWidth, setRectWidth] = useState(''); // m
  const [rectHeight, setRectHeight] = useState(''); // m
  const [trapBottom, setTrapBottom] = useState(''); // m (petite base)
  const [trapTop, setTrapTop] = useState(''); // m (grande base)
  const [trapHeight, setTrapHeight] = useState(''); // m

  const [slope, setSlope] = useState(''); // m/m
  const [flow, setFlow] = useState(''); // L/s

  const onMaterialChange = (id: string) => {
    setMaterialId(id);
    const mat = MATERIALS.find((m) => m.id === id);
    if (mat && id !== 'custom') setKText(String(mat.K));
  };

  // Convert UI units -> SI (metres, m/m, m³/s)
  const params: ProfileParams = {
    diameter: mm2m(parseNum(diameter)),
    ovoidWidth: mm2m(parseNum(ovoidWidth)),
    rectWidth: parseNum(rectWidth),
    rectHeight: parseNum(rectHeight),
    trapBottom: parseNum(trapBottom),
    trapTop: parseNum(trapTop),
    trapHeight: parseNum(trapHeight),
  };
  // Only finite, strictly-positive physical inputs are accepted; anything else
  // (empty, negative, non-numeric) is treated as "not provided" so no NaN can
  // ever propagate into the calculations or the chart.
  const K = pos(parseNum(kText));
  const slopePct = pos(parseNum(slope)); // pente saisie en %
  const J = slopePct !== undefined ? slopePct / 100 : undefined; // ratio (m/m) pour le calcul
  const Q_lps = pos(parseNum(flow));
  const Q = Q_lps !== undefined ? Q_lps / 1000 : undefined;

  const results = useMemo(
    () => computeResults({ profile, params, K, slope: J, flow: Q }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [
      profile,
      diameter,
      ovoidWidth,
      rectWidth,
      rectHeight,
      trapBottom,
      trapTop,
      trapHeight,
      kText,
      slope,
      flow,
    ],
  );

  const operatingForChart: OperatingChartPoint[] = (() => {
    const o = results.operating;
    const f = results.full;
    if (!o || !f || !(f.V > 0) || !(f.Q > 0)) return [];
    const qRatio = (Q ?? 0) / f.Q;
    const pts: OperatingChartPoint[] = [
      { fill: Math.min(Math.max(o.fill, 0), 1), vRatio: o.V / f.V, qRatio },
    ];
    if (o.bicritical && o.fillAlt !== undefined && o.VAlt !== undefined) {
      pts.push({ fill: o.fillAlt, vRatio: o.VAlt / f.V, qRatio, alt: true });
    }
    return pts.filter((p) =>
      [p.fill, p.vRatio, p.qRatio].every(Number.isFinite),
    );
  })();

  const exportData: ExportData = {
    profile,
    profileLabel: PROFILES.find((p) => p.id === profile)?.label ?? profile,
    params,
    materialName: MATERIALS.find((m) => m.id === materialId)?.name ?? 'Personnalisé',
    K,
    slopePct,
    Q_lps,
    results,
  };

  const runExport = async (kind: 'pdf' | 'excel') => {
    try {
      if (kind === 'pdf') await exportPdf(exportData);
      else await exportExcel(exportData);
    } catch (e: any) {
      Alert.alert('Export impossible', e?.message ?? 'Erreur inconnue');
    }
  };

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="light-content" backgroundColor="#1f4e79" />
      <ErrorBoundary>
      <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <Text style={styles.title}>Manning–Strickler</Text>
          <Text style={styles.subtitle}>Écoulement à surface libre</Text>
        </View>

        {/* --- Profil --- */}
        <Card title="Profil de la section">
          <PickerBox
            selectedValue={profile}
            onValueChange={(v) => setProfile(v as ProfileId)}
            items={PROFILES.map((p) => ({ label: p.label, value: p.id }))}
          />
          <ProfileInputs
            profile={profile}
            diameter={diameter}
            setDiameter={setDiameter}
            ovoidWidth={ovoidWidth}
            setOvoidWidth={setOvoidWidth}
            rectWidth={rectWidth}
            setRectWidth={setRectWidth}
            rectHeight={rectHeight}
            setRectHeight={setRectHeight}
            trapBottom={trapBottom}
            setTrapBottom={setTrapBottom}
            trapTop={trapTop}
            setTrapTop={setTrapTop}
            trapHeight={trapHeight}
            setTrapHeight={setTrapHeight}
          />
        </Card>

        {/* --- Matériau / K --- */}
        <Card title="Matériau (coefficient de Strickler K)">
          <PickerBox
            selectedValue={materialId}
            onValueChange={(v) => onMaterialChange(String(v))}
            items={MATERIALS.map((m) => ({
              label: m.id === 'custom' ? m.name : `${m.name}  —  K = ${m.K}`,
              value: m.id,
            }))}
          />
          <Field
            label="Coefficient K"
            unit="m^⅓/s"
            value={kText}
            onChangeText={(t) => {
              setKText(t);
              setMaterialId('custom');
            }}
            placeholder="ex. 100"
            hint="K = 1/n (Manning). Modifiable manuellement."
          />
        </Card>

        {/* --- Paramètres hydrauliques --- */}
        <Card title="Paramètres hydrauliques">
          <Field
            label="Pente J"
            unit="%"
            value={slope}
            onChangeText={setSlope}
            placeholder="ex. 0.5"
            hint="En pourcentage. Facultatif pour certaines sorties (ex. pente minimale)."
          />
          <Field
            label="Débit Q"
            unit="L/s"
            value={flow}
            onChangeText={setFlow}
            placeholder="ex. 50"
            hint="Facultatif pour certaines sorties (ex. débit critique)."
          />
        </Card>

        {/* --- Résultats --- */}
        <Card title="Résultats">
          {!results.geometry && (
            <Text style={styles.warn}>Renseignez les dimensions du profil pour calculer.</Text>
          )}

          {results.full && (
            <>
              <Result
                label="Débit critique Qc (remplissage 100 %)"
                value={`${fmt(results.full.Q * 1000, 1)} L/s`}
                sub={`${fmt(results.full.Q, 4)} m³/s · Vc = ${fmt(results.full.V, 3)} m/s`}
              />
              {results.geometry?.closed && (
                <Result
                  label={`Débit maximal Qmax (à ${fmt(results.full.fillAtQmax * 100, 1)} % de remplissage)`}
                  value={`${fmt(results.full.Qmax * 1000, 1)} L/s`}
                  sub={`zone bicritique entre Qc et Qmax : deux hauteurs possibles pour un même débit`}
                />
              )}
            </>
          )}
          {!results.full && results.geometry && (
            <Hint text="Pente J et coefficient K requis pour le débit critique." />
          )}

          {results.operating && (
            <>
              {results.operating.bicritical && results.operating.fillAlt !== undefined ? (
                <>
                  <Result
                    label="Taux de remplissage (régime bicritique : 2 solutions)"
                    value={`${fmt(results.operating.fill * 100, 1)} % ou ${fmt(results.operating.fillAlt * 100, 1)} %`}
                    sub={`hauteurs d'eau ≈ ${fmt(results.operating.y * 1000, 0)} mm ou ${fmt((results.operating.yAlt ?? 0) * 1000, 0)} mm`}
                  />
                  <Result
                    label="Vitesse d'écoulement (2 solutions)"
                    value={`${fmt(results.operating.V, 3)} ou ${fmt(results.operating.VAlt ?? NaN, 3)} m/s`}
                  />
                </>
              ) : (
                <>
                  <Result
                    label="Taux de remplissage (au débit Q)"
                    value={
                      results.operating.surcharged
                        ? '≥ 100 %'
                        : `${fmt(results.operating.fill * 100, 1)} %`
                    }
                    sub={`hauteur d'eau ≈ ${fmt(results.operating.y * 1000, 0)} mm`}
                  />
                  <Result
                    label="Vitesse d'écoulement"
                    value={`${fmt(results.operating.V, 3)} m/s`}
                  />
                </>
              )}
              <Badge
                surcharged={results.operating.surcharged}
                bicritical={results.operating.bicritical}
                closed={results.geometry?.closed ?? true}
              />
            </>
          )}
          {!results.operating && results.geometry && (
            <Hint text="Pente J, coefficient K et débit Q requis pour le point d'écoulement." />
          )}

          {results.minSlope !== undefined && (
            <Result
              label="Pente minimale (pour le profil indiqué)"
              value={`${fmt(results.minSlope * 100, 3)} %`}
              sub="pour faire passer Q à pleine section"
            />
          )}
          {results.minSlope === undefined && results.geometry && (
            <Hint text="Coefficient K et débit Q requis pour la pente minimale." />
          )}

          {results.minSize && (
            <Result
              label={`${minSizeLabel(profile, results.minSize.label)} minimal(e)`}
              value={minSizeValue(profile, results.minSize.value)}
              sub={
                profile === 'circular' || profile === 'ovoid'
                  ? 'pour faire passer Q à la pente indiquée'
                  : `dimensions × ${fmt(results.minSize.scale, 3)} — pour faire passer Q à la pente indiquée`
              }
            />
          )}
          {!results.minSize && results.geometry && (
            <Hint text="Pente J, coefficient K et débit Q requis pour la taille minimale." />
          )}
        </Card>

        {/* --- Graphique --- */}
        <Card title="Courbes hydrauliques">
          {results.curve.length > 0 ? (
            <FlowChart width={width - 56} curve={results.curve} operating={operatingForChart} />
          ) : (
            <Text style={styles.warn}>Renseignez le profil pour afficher le graphique.</Text>
          )}
        </Card>

        {/* --- Export --- */}
        <Card title="Export">
          <View style={styles.exportRow}>
            <Pressable
              style={[styles.exportBtn, !results.geometry && styles.exportBtnDisabled]}
              disabled={!results.geometry}
              onPress={() => runExport('pdf')}
            >
              <Text style={styles.exportBtnText}>📄 Rapport PDF</Text>
            </Pressable>
            <Pressable
              style={[styles.exportBtn, !results.geometry && styles.exportBtnDisabled]}
              disabled={!results.geometry}
              onPress={() => runExport('excel')}
            >
              <Text style={styles.exportBtnText}>📊 Classeur Excel</Text>
            </Pressable>
          </View>
          <Text style={styles.hintRow}>
            L'export Excel contient les formules dans les cellules : modifiez les entrées dans le
            classeur pour recalculer.
          </Text>
        </Card>

        <Text style={styles.footer}>
          V = K · Rh^(2/3) · J^(1/2) · A.  Qc = débit à remplissage 100 %. Qmax = débit maximal réel.
        </Text>
      </ScrollView>
      </ErrorBoundary>
    </SafeAreaView>
  );
}

// --- Profile-specific inputs -----------------------------------------------
function ProfileInputs(props: any) {
  const {
    profile,
    diameter, setDiameter,
    ovoidWidth, setOvoidWidth,
    rectWidth, setRectWidth,
    rectHeight, setRectHeight,
    trapBottom, setTrapBottom,
    trapTop, setTrapTop,
    trapHeight, setTrapHeight,
  } = props;

  switch (profile) {
    case 'circular':
      return (
        <Field label="Diamètre intérieur" unit="mm" value={diameter} onChangeText={setDiameter} placeholder="ex. 300" />
      );
    case 'ovoid':
      return (
        <>
          <Field
            label="Largeur de l'ovoïde"
            unit="mm"
            value={ovoidWidth}
            onChangeText={setOvoidWidth}
            placeholder="ex. 400"
            hint="Ovoïde normalisé : hauteur = 1,5 × largeur (auto)."
          />
          {parseNum(ovoidWidth) ? (
            <Text style={styles.info}>Hauteur ≈ {fmt((parseNum(ovoidWidth) as number) * 1.5, 0)} mm</Text>
          ) : null}
        </>
      );
    case 'rectangular':
      return (
        <>
          <Field label="Base B" unit="m" value={rectWidth} onChangeText={setRectWidth} placeholder="ex. 0.5" />
          <Field label="Hauteur H" unit="m" value={rectHeight} onChangeText={setRectHeight} placeholder="ex. 0.4" />
        </>
      );
    case 'trapezoidal':
      return (
        <>
          <Field label="Petite base (fond) b" unit="m" value={trapBottom} onChangeText={setTrapBottom} placeholder="ex. 0.3" />
          <Field label="Grande base (haut) B" unit="m" value={trapTop} onChangeText={setTrapTop} placeholder="ex. 0.8" />
          <Field label="Hauteur H" unit="m" value={trapHeight} onChangeText={setTrapHeight} placeholder="ex. 0.4" />
        </>
      );
    default:
      return null;
  }
}

// --- Small UI helpers ------------------------------------------------------
function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={styles.card}>
      <Text style={styles.cardTitle}>{title}</Text>
      {children}
    </View>
  );
}

function PickerBox({
  selectedValue,
  onValueChange,
  items,
}: {
  selectedValue: any;
  onValueChange: (v: any) => void;
  items: { label: string; value: any }[];
}) {
  return (
    <View style={styles.pickerWrap}>
      <Picker selectedValue={selectedValue} onValueChange={onValueChange} dropdownIconColor="#1f4e79">
        {items.map((it) => (
          <Picker.Item key={String(it.value)} label={it.label} value={it.value} />
        ))}
      </Picker>
    </View>
  );
}

function Result({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <View style={styles.result}>
      <Text style={styles.resultLabel}>{label}</Text>
      <Text style={styles.resultValue}>{value}</Text>
      {sub ? <Text style={styles.resultSub}>{sub}</Text> : null}
    </View>
  );
}

function Hint({ text }: { text: string }) {
  return <Text style={styles.hintRow}>ⓘ {text}</Text>;
}

function Badge({
  surcharged,
  bicritical,
  closed,
}: {
  surcharged: boolean;
  bicritical: boolean;
  closed: boolean;
}) {
  let style = styles.badgeGood;
  let text = '✓ Écoulement à surface libre';
  if (surcharged) {
    style = styles.badgeBad;
    text = closed
      ? '⚠ Canalisation EN CHARGE (débit maximal Qmax dépassé)'
      : '⚠ DÉBORDEMENT (capacité du caniveau dépassée)';
  } else if (bicritical) {
    style = styles.badgeWarn;
    text = '⚠ Régime BICRITIQUE (Qc < Q ≤ Qmax) : deux taux de remplissage possibles';
  }
  return (
    <View style={[styles.badge, style]}>
      <Text style={styles.badgeText}>{text}</Text>
    </View>
  );
}

// --- formatting / units ----------------------------------------------------
function mm2m(v: number | undefined) {
  return v !== undefined ? v / 1000 : undefined;
}
/** Finite, strictly-positive number, else undefined. */
function pos(x: number | undefined): number | undefined {
  return x !== undefined && Number.isFinite(x) && x > 0 ? x : undefined;
}
/**
 * Safe French number formatting WITHOUT relying on Intl/toLocaleString, whose
 * behaviour is inconsistent across React Native (Hermes) builds. Uses a thin
 * space for thousands and a comma decimal separator.
 */
function fmt(n: number, d: number) {
  if (!Number.isFinite(n)) return '—';
  if (Math.abs(n) >= 1e9) return n.toExponential(2);
  const fixed = n.toFixed(d);
  let [intPart, decPart = ''] = fixed.split('.');
  const neg = intPart.startsWith('-');
  if (neg) intPart = intPart.slice(1);
  intPart = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  decPart = decPart.replace(/0+$/, '');
  const body = decPart ? `${intPart},${decPart}` : intPart;
  return neg ? `-${body}` : body;
}
function minSizeLabel(profile: ProfileId, fallback: string) {
  if (profile === 'circular') return 'Diamètre intérieur';
  if (profile === 'ovoid') return 'Largeur ovoïde';
  return fallback;
}
function minSizeValue(profile: ProfileId, valueM: number) {
  if (profile === 'circular' || profile === 'ovoid') return `${fmt(valueM * 1000, 0)} mm`;
  return `${fmt(valueM, 3)} m (hauteur)`;
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: '#eef1f4' },
  container: { padding: 12, paddingBottom: 40 },
  header: { backgroundColor: '#1f4e79', borderRadius: 12, padding: 16, marginBottom: 12 },
  title: { color: '#fff', fontSize: 22, fontWeight: '700' },
  subtitle: { color: '#cfe0f0', fontSize: 14, marginTop: 2 },
  card: {
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 14,
    marginBottom: 12,
    ...Platform.select({ android: { elevation: 2 }, default: {} }),
  },
  cardTitle: { fontSize: 16, fontWeight: '700', color: '#1f4e79', marginBottom: 10 },
  pickerWrap: {
    borderWidth: 1,
    borderColor: '#cfcfcf',
    borderRadius: 8,
    marginBottom: 12,
    backgroundColor: '#fff',
    overflow: 'hidden',
  },
  warn: { color: '#a05a00', fontSize: 14 },
  info: { color: '#1f4e79', fontSize: 13, marginTop: -6, marginBottom: 10 },
  result: { borderTopWidth: 1, borderTopColor: '#eee', paddingVertical: 8 },
  resultLabel: { fontSize: 13, color: '#666' },
  resultValue: { fontSize: 19, fontWeight: '700', color: '#1a1a1a', marginTop: 2 },
  resultSub: { fontSize: 12, color: '#888', marginTop: 2 },
  hintRow: { fontSize: 12, color: '#999', paddingVertical: 6, fontStyle: 'italic' },
  badge: { borderRadius: 8, padding: 10, marginTop: 8 },
  badgeGood: { backgroundColor: '#e3f4e8' },
  badgeBad: { backgroundColor: '#fbe4e6' },
  badgeWarn: { backgroundColor: '#fdf3e7' },
  badgeText: { fontSize: 13, fontWeight: '600', color: '#333', textAlign: 'center' },
  exportRow: { flexDirection: 'row', justifyContent: 'space-between' },
  exportBtn: {
    flex: 1,
    backgroundColor: '#1f4e79',
    borderRadius: 8,
    paddingVertical: 12,
    marginHorizontal: 4,
    alignItems: 'center',
  },
  exportBtnDisabled: { backgroundColor: '#a9b6c4' },
  exportBtnText: { color: '#fff', fontSize: 14, fontWeight: '700' },
  footer: { fontSize: 11, color: '#999', textAlign: 'center', marginTop: 4 },
});
