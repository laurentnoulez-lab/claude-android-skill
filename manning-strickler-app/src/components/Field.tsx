import React, { useState } from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';
import { theme } from '../theme';

interface Props {
  label: string;
  unit?: string;
  value: string;
  onChangeText: (t: string) => void;
  placeholder?: string;
  hint?: string;
}

/** Labeled numeric text field (accepts comma or dot as decimal separator). */
export default function Field({ label, unit, value, onChangeText, placeholder, hint }: Props) {
  const [focused, setFocused] = useState(false);
  const filled = value.trim() !== '';
  return (
    <View style={styles.row}>
      <View style={styles.labelRow}>
        <Text style={styles.label}>{label}</Text>
        {unit ? (
          <View style={styles.unitChip}>
            <Text style={styles.unitText}>{unit}</Text>
          </View>
        ) : null}
      </View>
      <TextInput
        style={[styles.input, focused && styles.inputFocused, filled && styles.inputFilled]}
        value={value}
        onChangeText={onChangeText}
        onFocus={() => setFocused(true)}
        onBlur={() => setFocused(false)}
        placeholder={placeholder}
        keyboardType="numeric"
        placeholderTextColor={theme.textFaint}
        selectionColor={theme.accent}
      />
      {hint ? <Text style={styles.hint}>{hint}</Text> : null}
    </View>
  );
}

/** Parse a number accepting French decimal comma; returns undefined if empty/invalid. */
export function parseNum(s: string): number | undefined {
  const t = s.trim().replace(',', '.');
  if (t === '') return undefined;
  const n = Number(t);
  return Number.isFinite(n) ? n : undefined;
}

const styles = StyleSheet.create({
  row: { marginBottom: 14 },
  labelRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 6 },
  label: { fontSize: 13.5, color: theme.textStrong, fontWeight: '600', letterSpacing: 0.1 },
  unitChip: {
    marginLeft: 8,
    paddingHorizontal: 7,
    paddingVertical: 1,
    borderRadius: 5,
    backgroundColor: theme.chipBg,
  },
  unitText: { fontSize: 11, color: theme.textMuted, fontWeight: '600' },
  input: {
    borderWidth: 1.5,
    borderColor: theme.border,
    borderRadius: 10,
    paddingHorizontal: 13,
    paddingVertical: 11,
    fontSize: 16.5,
    color: theme.textStrong,
    backgroundColor: theme.inputBg,
    fontVariant: ['tabular-nums'],
  },
  inputFilled: { borderColor: theme.borderStrong, backgroundColor: '#fff' },
  inputFocused: { borderColor: theme.accent, backgroundColor: '#fff' },
  hint: { fontSize: 11.5, color: theme.textFaint, marginTop: 5, lineHeight: 15 },
});
