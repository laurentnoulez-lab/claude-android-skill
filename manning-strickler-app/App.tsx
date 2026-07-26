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
import { computeResults, FlowRegime } from './src/hydraulics/engine';
import { ExportData } from './src/export/exportData';
import { exportPdf } from './src/export/pdf';
import { exportExcel } from './src/export/excel';
import { theme, APP_VERSION } from './src/theme';

const PROFILES: { id: ProfileId; label: string; short: string }[] = [
  { id: 'circular', label: 'Circulaire fermé', short: 'Circulaire' },
  { id: 'ovoid', label: 'Ovoïde fermé (classique)', short: 'Ovoïde' },
  { id: 'rectangular', label: 'Caniveau rectangulaire (ciel ouvert)', short: 'Rectangulaire' },
  { id: 'trapezoidal', label: 'Caniveau trapézoïdal (ciel ouvert)', short: 'Trapézoïdal' },
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
  const [trapBottom, setTrapBottom] = useState(''); // m
  const [trapTop, setTrapTop] = useState(''); // m
  const [trapHeight, setTrapHeight] = useState(''); // m

  const [slope, setSlope] = useState(''); // %
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
  const slopePct = pos(parseNum(slope));
  const J = slopePct !== undefined ? slopePct / 100 : undefined;
  const Q_lps = pos(parseNum(flow));
  const Q = Q_lps !== undefined ? Q_lps / 1000 : undefined;

  const results = useMemo(
    () => computeResults({ profile, params, K, slope: J, flow: Q }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [profile, diameter, ovoidWidth, rectWidth, rectHeight, trapBottom, trapTop, trapHeight, kText, slope, flow],
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
    return pts.filter((p) => [p.fill, p.vRatio, p.qRatio].every(Number.isFinite));
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

  const [busy, setBusy] = useState<'pdf' | 'excel' | null>(null);
  const runExport = async (kind: 'pdf' | 'excel') => {
    try {
      setBusy(kind);
      if (kind === 'pdf') await exportPdf(exportData);
      else await exportExcel(exportData);
    } catch (e: any) {
      Alert.alert('Export impossible', e?.message ?? 'Erreur inconnue');
    } finally {
      setBusy(null);
    }
  };

  const op = results.operating;
  const full = results.full;

  return (
    <SafeAreaView style={styles.safe}>
      <StatusBar barStyle="light-content" backgroundColor={theme.navyDeep} />
      <ErrorBoundary>
        <ScrollView contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
          {/* ---------- Header ---------- */}
          <View style={styles.header}>
            <View style={styles.headerTop}>
              <View style={{ flex: 1 }}>
                <Text style={styles.title}>Manning–Strickler</Text>
                <Text style={styles.subtitle}>Écoulement à surface libre</Text>
              </View>
              <View style={styles.versionBadge}>
                <Text style={styles.versionText}>v{APP_VERSION}</Text>
              </View>
            </View>
            <View style={styles.formulaStrip}>
              <Text style={styles.formulaText}>V = K · Rh^⅔ · J^½    ·    Q = V · A</Text>
            </View>
          </View>

          {/* ---------- Key results summary ---------- */}
          {full && (
            <View style={styles.summaryRow}>
              <Stat label="Qc" value={fmt(full.Q * 1000, 1)} unit="L/s" tone="accent" />
              {results.geometry?.closed && (
                <Stat label="Qmax" value={fmt(full.Qmax * 1000, 1)} unit="L/s" tone="accent" />
              )}
              <Stat
                label="Remplissage"
                value={op ? (op.surcharged ? '≥100' : fmt(op.fill * 100, 1)) : '—'}
                unit="%"
                tone={op?.surcharged ? 'danger' : op?.bicritical ? 'warn' : 'ok'}
              />
              <Stat label="Vitesse" value={op ? fmt(op.V, 2) : '—'} unit="m/s" tone="ok" />
              <Stat
                label="Froude"
                value={op?.froude !== undefined ? fmt(op.froude, 2) : '—'}
                unit={op?.regime ? REGIME_SHORT[op.regime] : '—'}
                tone={op?.regime === 'torrentiel' ? 'warn' : op?.regime === 'critique' ? 'danger' : 'ok'}
              />
            </View>
          )}

          {/* ---------- 1. Profile ---------- */}
          <Card step="1" title="Profil de la section">
            <SelectRow
              label="Type de profil"
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

          {/* ---------- 2. Material ---------- */}
          <Card step="2" title="Matériau et rugosité">
            <SelectRow
              label="Matériau"
              selectedValue={materialId}
              onValueChange={(v) => onMaterialChange(String(v))}
              items={MATERIALS.map((m) => ({
                label: m.id === 'custom' ? m.name : `${m.name} — K = ${m.K}`,
                value: m.id,
              }))}
            />
            <Field
              label="Coefficient de Strickler K"
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

          {/* ---------- 3. Hydraulic parameters ---------- */}
          <Card step="3" title="Paramètres hydrauliques">
            <Field
              label="Pente J"
              unit="%"
              value={slope}
              onChangeText={setSlope}
              placeholder="ex. 0.5"
              hint="Facultative : sans elle, la pente minimale reste calculée."
            />
            <Field
              label="Débit Q"
              unit="L/s"
              value={flow}
              onChangeText={setFlow}
              placeholder="ex. 50"
              hint="Facultatif : sans lui, le débit critique reste calculé."
            />
          </Card>

          {/* ---------- 4. Results ---------- */}
          <Card step="4" title="Résultats">
            {!results.geometry && <Notice tone="warn" text="Renseignez les dimensions du profil pour lancer le calcul." />}

            {full && (
              <>
                <Result
                  label="Débit critique Qc"
                  hint="remplissage 100 %"
                  value={`${fmt(full.Q * 1000, 1)} L/s`}
                  sub={`${fmt(full.Q, 4)} m³/s · Vc = ${fmt(full.V, 3)} m/s`}
                />
                {results.geometry?.closed && (
                  <Result
                    label="Débit maximal Qmax"
                    hint={`à ${fmt(full.fillAtQmax * 100, 1)} % de remplissage`}
                    value={`${fmt(full.Qmax * 1000, 1)} L/s`}
                    sub="entre Qc et Qmax : zone bicritique (deux hauteurs possibles)"
                  />
                )}
              </>
            )}
            {!full && results.geometry && <Hint text="Pente J et coefficient K requis pour le débit critique." />}

            {op && (
              <>
                {op.bicritical && op.fillAlt !== undefined ? (
                  <>
                    <Result
                      label="Taux de remplissage"
                      hint="régime bicritique — 2 solutions"
                      value={`${fmt(op.fill * 100, 1)} %  ou  ${fmt(op.fillAlt * 100, 1)} %`}
                      sub={`hauteurs d'eau ≈ ${fmt(op.y * 1000, 0)} mm ou ${fmt((op.yAlt ?? 0) * 1000, 0)} mm`}
                    />
                    <Result
                      label="Vitesse d'écoulement"
                      hint="2 solutions"
                      value={`${fmt(op.V, 3)}  ou  ${fmt(op.VAlt ?? NaN, 3)} m/s`}
                    />
                  </>
                ) : (
                  <>
                    <Result
                      label="Taux de remplissage"
                      hint="au débit Q indiqué"
                      value={op.surcharged ? '≥ 100 %' : `${fmt(op.fill * 100, 1)} %`}
                      sub={`hauteur d'eau ≈ ${fmt(op.y * 1000, 0)} mm`}
                    />
                    <Result label="Vitesse d'écoulement" value={`${fmt(op.V, 3)} m/s`} />
                  </>
                )}
                <FroudeRows op={op} />
                <Banner
                  surcharged={op.surcharged}
                  bicritical={op.bicritical}
                  closed={results.geometry?.closed ?? true}
                />
              </>
            )}
            {!op && results.geometry && (
              <Hint text="Pente J, coefficient K et débit Q requis pour le point de fonctionnement." />
            )}

            {results.minSlope !== undefined && (
              <Result
                label="Pente minimale"
                hint="pour le profil indiqué"
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
                hint="pour la pente indiquée"
                value={minSizeValue(profile, results.minSize.value)}
                sub={
                  profile === 'circular' || profile === 'ovoid'
                    ? 'pour faire passer Q'
                    : `dimensions × ${fmt(results.minSize.scale, 3)}`
                }
              />
            )}
            {!results.minSize && results.geometry && (
              <Hint text="Pente J, coefficient K et débit Q requis pour la taille minimale." />
            )}
          </Card>

          {/* ---------- 5. Chart ---------- */}
          <Card step="5" title="Courbes hydrauliques">
            {results.curve.length > 0 ? (
              <FlowChart width={width - 60} curve={results.curve} operating={operatingForChart} />
            ) : (
              <Notice tone="warn" text="Renseignez le profil pour afficher le graphique." />
            )}
          </Card>

          {/* ---------- 6. Export ---------- */}
          <Card step="6" title="Export">
            <View style={styles.exportRow}>
              <ExportButton
                icon="PDF"
                label="Rapport PDF"
                disabled={!results.geometry || busy !== null}
                loading={busy === 'pdf'}
                onPress={() => runExport('pdf')}
              />
              <ExportButton
                icon="XLS"
                label="Classeur Excel"
                disabled={!results.geometry || busy !== null}
                loading={busy === 'excel'}
                onPress={() => runExport('excel')}
              />
            </View>
            <Text style={styles.exportNote}>
              Les deux exports sont mis en page pour l'impression A4. Le classeur Excel est
              interactif : listes déroulantes, formules dans les cellules et graphique natif.
            </Text>
          </Card>

          <Text style={styles.footer}>
            Qc = débit à remplissage 100 %  ·  Qmax = débit maximal réel  ·  v{APP_VERSION}
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
        <Field label="Diamètre intérieur D" unit="mm" value={diameter} onChangeText={setDiameter} placeholder="ex. 300" />
      );
    case 'ovoid':
      return (
        <>
          <Field
            label="Largeur de l'ovoïde L"
            unit="mm"
            value={ovoidWidth}
            onChangeText={setOvoidWidth}
            placeholder="ex. 400"
            hint="Ovoïde normalisé : hauteur = 1,5 × largeur."
          />
          {parseNum(ovoidWidth) ? (
            <View style={styles.derived}>
              <Text style={styles.derivedText}>
                Hauteur calculée : {fmt((parseNum(ovoidWidth) as number) * 1.5, 0)} mm
              </Text>
            </View>
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

const REGIME_SHORT: Record<FlowRegime, string> = {
  fluvial: 'fluvial',
  critique: 'critique',
  torrentiel: 'torrent.',
};
const REGIME_LABEL: Record<FlowRegime, string> = {
  fluvial: 'FLUVIAL (subcritique)',
  critique: 'CRITIQUE',
  torrentiel: 'TORRENTIEL (supercritique)',
};

/**
 * Froude number and the resulting regime. Fr = V / sqrt(g·Dh) with Dh = A/T.
 * Not shown for pressurised flow, nor when the free surface closes on itself
 * at the crown of a closed conduit (T -> 0), where Fr is meaningless.
 */
function FroudeRows({ op }: { op: any }) {
  if (op.froude === undefined) {
    return (
      <Hint
        text={
          op.surcharged
            ? 'Nombre de Froude non défini : écoulement en charge (pas de surface libre).'
            : 'Nombre de Froude non défini : la surface libre se referme (section pleine).'
        }
      />
    );
  }
  const two = op.bicritical && op.froudeAlt !== undefined;
  return (
    <>
      <Result
        label="Nombre de Froude Fr"
        hint={two ? '2 solutions' : 'Fr = V / √(g·A/T)'}
        value={two ? `${fmt(op.froude, 3)}  ou  ${fmt(op.froudeAlt, 3)}` : fmt(op.froude, 3)}
        sub={
          two
            ? `${REGIME_LABEL[op.regime as FlowRegime]} / ${REGIME_LABEL[op.regimeAlt as FlowRegime]}`
            : undefined
        }
      />
      {!two && (
        <View
          style={[
            styles.banner,
            {
              backgroundColor:
                op.regime === 'torrentiel' ? theme.warnBg : op.regime === 'critique' ? theme.dangerBg : theme.okBg,
              borderLeftColor:
                op.regime === 'torrentiel' ? theme.warn : op.regime === 'critique' ? theme.danger : theme.ok,
            },
          ]}
        >
          <Text
            style={[
              styles.bannerTitle,
              {
                color:
                  op.regime === 'torrentiel' ? theme.warn : op.regime === 'critique' ? theme.danger : theme.ok,
              },
            ]}
          >
            Régime {REGIME_LABEL[op.regime as FlowRegime]}
          </Text>
          <Text style={styles.bannerDetail}>
            {op.regime === 'fluvial'
              ? 'Fr < 1 : écoulement lent, contrôlé par l’aval.'
              : op.regime === 'torrentiel'
                ? 'Fr > 1 : écoulement rapide, contrôlé par l’amont ; risque de ressaut hydraulique.'
                : 'Fr ≈ 1 : régime critique, écoulement instable — à éviter en conception.'}
          </Text>
        </View>
      )}
    </>
  );
}

// --- UI building blocks ----------------------------------------------------
function Card({ step, title, children }: { step: string; title: string; children: React.ReactNode }) {
  return (
    <View style={styles.card}>
      <View style={styles.cardHeader}>
        <View style={styles.stepBadge}>
          <Text style={styles.stepText}>{step}</Text>
        </View>
        <Text style={styles.cardTitle}>{title}</Text>
      </View>
      <View style={styles.cardBody}>{children}</View>
    </View>
  );
}

function SelectRow({
  label,
  selectedValue,
  onValueChange,
  items,
}: {
  label: string;
  selectedValue: any;
  onValueChange: (v: any) => void;
  items: { label: string; value: any }[];
}) {
  return (
    <View style={{ marginBottom: 14 }}>
      <Text style={styles.selectLabel}>{label}</Text>
      <View style={styles.pickerWrap}>
        <Picker
          selectedValue={selectedValue}
          onValueChange={onValueChange}
          dropdownIconColor={theme.navy}
          style={styles.picker}
        >
          {items.map((it) => (
            <Picker.Item key={String(it.value)} label={it.label} value={it.value} />
          ))}
        </Picker>
      </View>
    </View>
  );
}

function Stat({
  label,
  value,
  unit,
  tone,
}: {
  label: string;
  value: string;
  unit: string;
  tone: 'accent' | 'ok' | 'warn' | 'danger';
}) {
  const color =
    tone === 'danger' ? theme.danger : tone === 'warn' ? theme.warn : tone === 'ok' ? theme.ok : theme.accent;
  return (
    <View style={styles.stat}>
      <Text style={styles.statLabel}>{label}</Text>
      <Text style={[styles.statValue, { color }]} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
      <Text style={styles.statUnit}>{unit}</Text>
    </View>
  );
}

function Result({ label, value, sub, hint }: { label: string; value: string; sub?: string; hint?: string }) {
  return (
    <View style={styles.result}>
      <View style={styles.resultLabelRow}>
        <Text style={styles.resultLabel}>{label}</Text>
        {hint ? <Text style={styles.resultHint}>{hint}</Text> : null}
      </View>
      <Text style={styles.resultValue}>{value}</Text>
      {sub ? <Text style={styles.resultSub}>{sub}</Text> : null}
    </View>
  );
}

function Hint({ text }: { text: string }) {
  return (
    <View style={styles.hintRow}>
      <Text style={styles.hintIcon}>ⓘ</Text>
      <Text style={styles.hintText}>{text}</Text>
    </View>
  );
}

function Notice({ tone, text }: { tone: 'warn' | 'ok'; text: string }) {
  return (
    <View style={[styles.notice, { backgroundColor: tone === 'warn' ? theme.warnBg : theme.okBg }]}>
      <Text style={[styles.noticeText, { color: tone === 'warn' ? theme.warn : theme.ok }]}>{text}</Text>
    </View>
  );
}

function Banner({ surcharged, bicritical, closed }: { surcharged: boolean; bicritical: boolean; closed: boolean }) {
  let bg: string = theme.okBg;
  let fg: string = theme.ok;
  let title = 'Écoulement à surface libre';
  let detail = 'Le débit reste inférieur à la capacité de la section.';
  if (surcharged) {
    bg = theme.dangerBg;
    fg = theme.danger;
    title = closed ? 'Canalisation en charge' : 'Débordement du caniveau';
    detail = 'Le débit maximal Qmax est dépassé.';
  } else if (bicritical) {
    bg = theme.warnBg;
    fg = theme.warn;
    title = 'Régime bicritique';
    detail = 'Qc < Q ≤ Qmax : deux taux de remplissage sont possibles pour ce débit.';
  }
  return (
    <View style={[styles.banner, { backgroundColor: bg, borderLeftColor: fg }]}>
      <Text style={[styles.bannerTitle, { color: fg }]}>{title}</Text>
      <Text style={styles.bannerDetail}>{detail}</Text>
    </View>
  );
}

function ExportButton({
  icon,
  label,
  disabled,
  loading,
  onPress,
}: {
  icon: string;
  label: string;
  disabled: boolean;
  loading: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable
      style={({ pressed }: { pressed: boolean }) => [
        styles.exportBtn,
        disabled && styles.exportBtnDisabled,
        pressed && !disabled && styles.exportBtnPressed,
      ]}
      disabled={disabled}
      onPress={onPress}
    >
      <View style={styles.exportIcon}>
        <Text style={styles.exportIconText}>{icon}</Text>
      </View>
      <Text style={styles.exportBtnText}>{loading ? 'Export…' : label}</Text>
    </Pressable>
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
 * behaviour is inconsistent across React Native (Hermes) builds.
 */
function fmt(n: number, d: number) {
  if (!Number.isFinite(n)) return '—';
  if (Math.abs(n) >= 1e9) return n.toExponential(2);
  const fixed = n.toFixed(d);
  let [intPart, decPart = ''] = fixed.split('.');
  const neg = intPart.startsWith('-');
  if (neg) intPart = intPart.slice(1);
  intPart = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
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

const shadow = Platform.select({
  android: { elevation: 2 },
  default: {
    shadowColor: '#0C2438',
    shadowOpacity: 0.08,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
  },
}) as object;

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: theme.bg },
  container: { padding: 14, paddingBottom: 44 },

  // Header
  header: { backgroundColor: theme.navy, borderRadius: 16, padding: 18, marginBottom: 14, ...shadow },
  headerTop: { flexDirection: 'row', alignItems: 'flex-start' },
  title: { color: theme.onDark, fontSize: 25, fontWeight: '800', letterSpacing: -0.4 },
  subtitle: { color: theme.onDarkMuted, fontSize: 13.5, marginTop: 3, letterSpacing: 0.2 },
  versionBadge: {
    backgroundColor: 'rgba(255,255,255,0.16)',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 20,
  },
  versionText: { color: theme.onDark, fontSize: 12, fontWeight: '700' },
  formulaStrip: {
    marginTop: 14,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: 'rgba(255,255,255,0.14)',
  },
  formulaText: { color: theme.onDarkMuted, fontSize: 12.5, letterSpacing: 0.3 },

  // Summary stats
  summaryRow: { flexDirection: 'row', marginBottom: 14, gap: 8 },
  stat: {
    flex: 1,
    backgroundColor: theme.surface,
    borderRadius: 12,
    paddingVertical: 11,
    paddingHorizontal: 6,
    alignItems: 'center',
    ...shadow,
  },
  statLabel: { fontSize: 10.5, color: theme.textFaint, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.4 },
  statValue: { fontSize: 18, fontWeight: '800', marginTop: 3, fontVariant: ['tabular-nums'] },
  statUnit: { fontSize: 10.5, color: theme.textFaint, marginTop: 1 },

  // Cards
  card: { backgroundColor: theme.surface, borderRadius: 14, marginBottom: 14, overflow: 'hidden', ...shadow },
  cardHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 12,
    borderBottomWidth: 1,
    borderBottomColor: theme.divider,
  },
  stepBadge: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: theme.accentSoft,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 10,
  },
  stepText: { color: theme.accent, fontSize: 12.5, fontWeight: '800' },
  cardTitle: { fontSize: 15.5, fontWeight: '700', color: theme.textStrong, letterSpacing: -0.2 },
  cardBody: { padding: 14 },

  // Select
  selectLabel: { fontSize: 13.5, color: theme.textStrong, fontWeight: '600', marginBottom: 6 },
  pickerWrap: {
    borderWidth: 1.5,
    borderColor: theme.border,
    borderRadius: 10,
    backgroundColor: theme.inputBg,
    overflow: 'hidden',
  },
  picker: { color: theme.textStrong },
  derived: {
    backgroundColor: theme.accentSoft,
    borderRadius: 8,
    paddingVertical: 8,
    paddingHorizontal: 11,
    marginTop: -4,
    marginBottom: 12,
  },
  derivedText: { color: theme.accent, fontSize: 12.5, fontWeight: '600' },

  // Results
  result: { borderTopWidth: 1, borderTopColor: theme.divider, paddingVertical: 11 },
  resultLabelRow: { flexDirection: 'row', alignItems: 'baseline', flexWrap: 'wrap' },
  resultLabel: { fontSize: 13, color: theme.textMuted, fontWeight: '600' },
  resultHint: { fontSize: 11.5, color: theme.textFaint, marginLeft: 6, fontStyle: 'italic' },
  resultValue: {
    fontSize: 21,
    fontWeight: '800',
    color: theme.textStrong,
    marginTop: 3,
    letterSpacing: -0.4,
    fontVariant: ['tabular-nums'],
  },
  resultSub: { fontSize: 12, color: theme.textFaint, marginTop: 3, lineHeight: 16 },

  hintRow: { flexDirection: 'row', paddingVertical: 7, alignItems: 'flex-start' },
  hintIcon: { fontSize: 12, color: theme.textFaint, marginRight: 6, marginTop: 1 },
  hintText: { flex: 1, fontSize: 12, color: theme.textFaint, fontStyle: 'italic', lineHeight: 16 },

  notice: { borderRadius: 10, padding: 12 },
  noticeText: { fontSize: 13, fontWeight: '600', lineHeight: 18 },

  banner: { borderRadius: 10, borderLeftWidth: 4, padding: 12, marginTop: 12 },
  bannerTitle: { fontSize: 14, fontWeight: '800', letterSpacing: -0.1 },
  bannerDetail: { fontSize: 12.5, color: theme.textMuted, marginTop: 3, lineHeight: 17 },

  // Export
  exportRow: { flexDirection: 'row', gap: 10 },
  exportBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: theme.navy,
    borderRadius: 11,
    paddingVertical: 13,
  },
  exportBtnPressed: { backgroundColor: theme.navyDeep },
  exportBtnDisabled: { backgroundColor: theme.borderStrong },
  exportIcon: {
    backgroundColor: 'rgba(255,255,255,0.18)',
    borderRadius: 5,
    paddingHorizontal: 5,
    paddingVertical: 2,
    marginRight: 8,
  },
  exportIconText: { color: theme.onDark, fontSize: 9.5, fontWeight: '800', letterSpacing: 0.3 },
  exportBtnText: { color: theme.onDark, fontSize: 14, fontWeight: '700' },
  exportNote: { fontSize: 11.5, color: theme.textFaint, marginTop: 11, lineHeight: 16 },

  footer: { fontSize: 11, color: theme.textFaint, textAlign: 'center', marginTop: 6, lineHeight: 15 },
});
