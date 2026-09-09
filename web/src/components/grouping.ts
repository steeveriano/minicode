import type { FileEntry } from '../api/device';
import { describeType, familyOf } from './fileType';

/** How the list is broken into sections. `none` leaves it flat. */
export type GroupBy = 'none' | 'type' | 'size' | 'date';

export const GROUP_LABELS: Record<GroupBy, string> = {
  none: 'Sin agrupar',
  type: 'Tipo',
  size: 'Tamaño',
  date: 'Fecha',
};

/** A section: its heading, and the entries under it. Order is decided by the grouper. */
export type Group = { key: string; label: string; entries: FileEntry[] };

const FAMILY_LABEL: Record<string, string> = {
  folder: 'Carpetas',
  image: 'Imágenes',
  video: 'Vídeos',
  audio: 'Audio',
  document: 'Documentos',
  archive: 'Comprimidos',
  app: 'Aplicaciones',
  other: 'Otros',
};

/** Bands chosen so a phone's real distribution splits usefully, not by round decimal numbers. */
const SIZE_BANDS: { max: number; key: string; label: string }[] = [
  { max: 0, key: '0', label: 'Vacíos' },
  { max: 100_000, key: '1', label: 'Diminutos · menos de 100 kB' },
  { max: 1_000_000, key: '2', label: 'Pequeños · menos de 1 MB' },
  { max: 10_000_000, key: '3', label: 'Medianos · menos de 10 MB' },
  { max: 100_000_000, key: '4', label: 'Grandes · menos de 100 MB' },
  { max: Number.POSITIVE_INFINITY, key: '5', label: 'Enormes · 100 MB o más' },
];

const DAY = 86_400_000;

const DATE_BANDS: { within: number; key: string; label: string }[] = [
  { within: DAY, key: '0', label: 'Hoy' },
  { within: 7 * DAY, key: '1', label: 'Esta semana' },
  { within: 30 * DAY, key: '2', label: 'Este mes' },
  { within: 365 * DAY, key: '3', label: 'Este año' },
  { within: Number.POSITIVE_INFINITY, key: '4', label: 'Más de un año' },
];

function bandFor(entry: FileEntry, by: GroupBy, now: number): { key: string; label: string } {
  if (by === 'type') {
    const family = entry.is_directory ? 'folder' : familyOf(entry.name);
    return { key: family, label: FAMILY_LABEL[family] ?? describeType(entry) };
  }
  if (by === 'size') {
    // A folder has no size of its own until it is measured, so it gets its own section rather than
    // landing in "Vacíos" and reading as though it held nothing.
    if (entry.is_directory) return { key: 'dir', label: 'Carpetas' };
    const band = SIZE_BANDS.find((b) => entry.size <= b.max) ?? SIZE_BANDS[SIZE_BANDS.length - 1]!;
    return { key: band.key, label: band.label };
  }
  if (!entry.last_modified) return { key: '9', label: 'Sin fecha' };
  const age = now - entry.last_modified;
  const band = DATE_BANDS.find((b) => age < b.within) ?? DATE_BANDS[DATE_BANDS.length - 1]!;
  return { key: band.key, label: band.label };
}

/**
 * Splits an already-sorted list into sections, preserving the order within each.
 *
 * Sections come out in the order the bands define, not the order they happen to appear, so the
 * headings read the same whichever folder is open.
 */
export function groupEntries(entries: FileEntry[], by: GroupBy, now = Date.now()): Group[] {
  if (by === 'none') return [{ key: 'all', label: '', entries }];

  const buckets = new Map<string, Group>();
  for (const entry of entries) {
    const band = bandFor(entry, by, now);
    const bucket = buckets.get(band.key);
    if (bucket) bucket.entries.push(entry);
    else buckets.set(band.key, { key: band.key, label: band.label, entries: [entry] });
  }

  const order =
    by === 'type'
      ? Object.keys(FAMILY_LABEL)
      : by === 'size'
        ? ['dir', ...SIZE_BANDS.map((b) => b.key)]
        : [...DATE_BANDS.map((b) => b.key), '9'];

  const ranked = [...buckets.values()].sort((a, b) => order.indexOf(a.key) - order.indexOf(b.key));
  return ranked;
}

/** Total bytes of the files in a section. Folders contribute nothing until measured. */
export function groupBytes(group: Group): number {
  return group.entries.reduce((n, e) => (e.is_directory ? n : n + e.size), 0);
}
