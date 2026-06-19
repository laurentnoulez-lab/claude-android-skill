import React from 'react';
import { View, Text, TextInput, StyleSheet } from 'react-native';

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
  return (
    <View style={styles.row}>
      <Text style={styles.label}>
        {label}
        {unit ? <Text style={styles.unit}>  ({unit})</Text> : null}
      </Text>
      <TextInput
        style={styles.input}
        value={value}
        onChangeText={onChangeText}
        placeholder={placeholder}
        keyboardType="numeric"
        placeholderTextColor="#aaa"
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
  row: { marginBottom: 12 },
  label: { fontSize: 14, color: '#333', marginBottom: 4, fontWeight: '500' },
  unit: { color: '#888', fontWeight: '400' },
  input: {
    borderWidth: 1,
    borderColor: '#cfcfcf',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 16,
    backgroundColor: '#fff',
  },
  hint: { fontSize: 11, color: '#888', marginTop: 3 },
});
