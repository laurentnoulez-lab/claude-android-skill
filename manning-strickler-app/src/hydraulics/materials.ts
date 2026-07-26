/**
 * Suggested materials with typical Strickler coefficients K (m^(1/3)/s).
 * K = 1 / n where n is the Manning roughness coefficient.
 * Values are typical design figures; the user can always override manually.
 */

export interface Material {
  id: string;
  name: string;
  K: number;
}

export const MATERIALS: Material[] = [
  { id: 'pvc', name: 'PVC / PE (lisse)', K: 100 },
  { id: 'fonte', name: 'Fonte', K: 80 },
  { id: 'beton-lisse', name: 'Béton lisse', K: 75 },
  { id: 'beton-courant', name: 'Béton courant', K: 70 },
  { id: 'beton-rugueux', name: 'Béton rugueux', K: 60 },
  { id: 'acier', name: 'Acier', K: 90 },
  { id: 'gres', name: 'Grès vernissé', K: 80 },
  { id: 'amiante-ciment', name: 'Amiante-ciment', K: 90 },
  { id: 'maconnerie', name: 'Maçonnerie', K: 60 },
  { id: 'terre', name: 'Terre / canal naturel', K: 40 },
  { id: 'enrobe', name: 'Enrobé bitumineux', K: 70 },
  { id: 'custom', name: 'Personnalisé…', K: 0 },
];
