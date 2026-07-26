/**
 * Shared design tokens. Kept in one place so the screens, fields and charts
 * stay visually consistent.
 */
export const theme = {
  // Brand
  navy: '#123456',
  navyDeep: '#0C2438',
  accent: '#1C7ED6',
  accentSoft: '#E7F1FB',

  // Surfaces
  bg: '#EEF2F6',
  surface: '#FFFFFF',
  inputBg: '#FBFCFD',
  chipBg: '#EDF1F5',

  // Text
  textStrong: '#16212B',
  textMuted: '#5A6B7A',
  textFaint: '#93A1AE',
  onDark: '#FFFFFF',
  onDarkMuted: '#B9CEE0',

  // Lines
  border: '#D7DEE5',
  borderStrong: '#B7C3CE',
  divider: '#EBEFF3',

  // Semantic
  ok: '#1E8E5A',
  okBg: '#E6F5ED',
  warn: '#B4690E',
  warnBg: '#FDF3E3',
  danger: '#C0392B',
  dangerBg: '#FBEAE7',

  // Series colours (shared with the charts)
  seriesV: '#E07A3F',
  seriesQ: '#2F7DD1',
  point: '#D12F4F',
} as const;

export const APP_VERSION = '10.0';
