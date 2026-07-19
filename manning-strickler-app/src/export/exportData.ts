/**
 * Shared bundle passed from the UI to the PDF and Excel exporters, plus small
 * formatting helpers used by both.
 */

import { EngineResults } from '../hydraulics/engine';
import { ProfileId, ProfileParams } from '../hydraulics/profiles';

export interface ExportData {
  profile: ProfileId;
  profileLabel: string;
  params: ProfileParams;
  materialName: string;
  K?: number;
  slopePct?: number; // slope as entered, in %
  Q_lps?: number; // discharge as entered, in L/s
  results: EngineResults;
}

/** Plain French number formatting (comma decimal), export-safe. */
export function fnum(n: number | undefined, d = 3): string {
  if (n === undefined || !Number.isFinite(n)) return '—';
  const fixed = n.toFixed(d);
  let [intPart, decPart = ''] = fixed.split('.');
  decPart = decPart.replace(/0+$/, '');
  return decPart ? `${intPart},${decPart}` : intPart;
}

/** Dimension rows of the selected profile: [label, value in m]. */
export function dimensionRows(data: ExportData): [string, number][] {
  const p = data.params;
  switch (data.profile) {
    case 'circular':
      return [['Diamètre intérieur D', p.diameter ?? NaN]];
    case 'ovoid':
      return [
        ['Largeur ovoïde L', p.ovoidWidth ?? NaN],
        ['Hauteur (1,5 × L)', p.ovoidWidth !== undefined ? p.ovoidWidth * 1.5 : NaN],
      ];
    case 'rectangular':
      return [
        ['Base B', p.rectWidth ?? NaN],
        ['Hauteur H', p.rectHeight ?? NaN],
      ];
    case 'trapezoidal':
      return [
        ['Petite base b', p.trapBottom ?? NaN],
        ['Grande base B', p.trapTop ?? NaN],
        ['Hauteur H', p.trapHeight ?? NaN],
      ];
    default:
      return [];
  }
}
