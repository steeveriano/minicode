/**
 * The contract between the phone, the database and this page.
 *
 * `UsageNode` mirrors the Android side's `DiskUsageResult` / `DiskUsageNode`; keeping the two in one
 * repository is the point — a field renamed on the device and not here would otherwise only surface
 * as an empty screen after a deploy.
 *
 * The database stores the tree flattened, because growth over time and diffs between captures are
 * why it earns its place, and neither is expressible over nested JSON. The nesting is rebuilt here,
 * at the one place that needs it.
 */

/** How much of a location the device was allowed to see when the snapshot was taken. */
export type AccessLevel = 'full' | 'partial' | 'owned_only' | 'saf';

/** One directory, with the totals for everything beneath it. */
export interface UsageNode {
  /** Location-relative path. Empty string for the location's own root. */
  path: string;
  /** Bytes in this directory and every directory below it. */
  totalBytes: number;
  /** Files in this directory and every directory below it. */
  fileCount: number;
  children: UsageNode[];
}

export interface LocationSnapshot {
  id: string;
  /** The device's own display name, which encodes the access level in words. */
  name: string;
  path: string;
  accessLevel: AccessLevel;
  /**
   * Null when the traversal did not complete — a location that could not be read is not the same
   * as one that is empty, and the page must never show the second when it means the first.
   */
  root: UsageNode | null;
  /** Why `root` is null, in the device's words. */
  error?: string;
  /** True when the device reported it could only see files this app itself wrote. */
  partial: boolean;
}

/** One row of the flattened tree, as `public.device_storage_latest_snapshot()` returns it. */
export interface UsageRow {
  path: string;
  parentPath: string | null;
  depth: number;
  totalBytes: number;
  fileCount: number;
}

export interface LocationRows {
  id: string;
  name: string;
  path: string;
  accessLevel: AccessLevel;
  partial: boolean;
  error: string | null;
  nodes: UsageRow[];
}

/** The function's payload, before the tree is rebuilt. */
export interface SnapshotPayload {
  schemaVersion: 1;
  snapshotId?: string;
  capturedAt?: string;
  empty?: true;
  device?: { availableBytes: number | null; serverVersion: string | null };
  locations?: LocationRows[];
}

export interface Snapshot {
  schemaVersion: 1;
  capturedAt: string;
  device: {
    /** Free space on shared storage, as the device reported it. Null when unavailable. */
    availableBytes: number | null;
    serverVersion: string | null;
  };
  locations: LocationSnapshot[];
}

export const SCHEMA_VERSION = 1 as const;

/** Total bytes across every location that could be read. */
export function totalBytes(snapshot: Snapshot): number {
  return snapshot.locations.reduce((sum, l) => sum + (l.root?.totalBytes ?? 0), 0);
}

/** Total files across every location that could be read. */
export function totalFiles(snapshot: Snapshot): number {
  return snapshot.locations.reduce((sum, l) => sum + (l.root?.fileCount ?? 0), 0);
}

/**
 * Bytes as a person reads them.
 *
 * Decimal units, not binary: the device reports what Android reports, and Android's own storage
 * screen is decimal. Showing GiB here would disagree with the phone by 7% and make every figure
 * look wrong.
 */
export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';
  const units = ['B', 'kB', 'MB', 'GB', 'TB'];
  const exponent = Math.min(Math.floor(Math.log10(bytes) / 3), units.length - 1);
  const value = bytes / 1000 ** exponent;
  const decimals = exponent === 0 ? 0 : value < 10 ? 2 : value < 100 ? 1 : 0;
  return `${value.toFixed(decimals)} ${units[exponent]}`;
}

export function formatCount(n: number): string {
  return n.toLocaleString('es');
}

/** The last path segment, which is what a tree row shows. */
export function leafName(path: string): string {
  if (path === '') return '/';
  const cut = path.lastIndexOf('/');
  return cut === -1 ? path : path.slice(cut + 1);
}

/** Children ordered by what occupies most, which is the only order this page is read in. */
export function sortedChildren(node: UsageNode): UsageNode[] {
  return [...node.children].sort((a, b) => b.totalBytes - a.totalBytes);
}

/**
 * Spanish name and path for a location.
 *
 * The device names its built-in locations in English and encodes the access level into the name
 * ("Camera (DCIM) - All files"). The level is already shown as a chip, so repeating it in the name
 * is noise; and a Spanish page reading "Movies - All files" is just untranslated.
 *
 * A location the user added through the picker keeps the folder's own name — that is the name they
 * chose, and renaming it here would stop it matching what they see on the phone.
 */
const BUILTIN_NAMES: Record<string, string> = {
  'builtin:downloads': 'Descargas',
  'builtin:pictures': 'Imágenes',
  'builtin:movies': 'Videos',
  'builtin:music': 'Música',
  'builtin:dcim': 'Cámara (DCIM)',
  'builtin:recordings': 'Grabaciones',
};

export function locationLabel(location: LocationSnapshot): { name: string; path: string } {
  return { name: BUILTIN_NAMES[location.id] ?? location.name, path: location.path };
}

/**
 * Rebuilds one location's tree from its flat rows.
 *
 * The rows arrive ordered by size, not by structure, so a child can appear before its parent; every
 * node is therefore created first and linked second. A row whose `parentPath` names something that
 * is not in the set is dropped rather than silently reparented to the root — that would invent a
 * total the device never reported.
 */
export function buildTree(rows: UsageRow[]): UsageNode | null {
  const byPath = new Map<string, UsageNode>();
  for (const row of rows) {
    byPath.set(row.path, {
      path: row.path,
      totalBytes: row.totalBytes,
      fileCount: row.fileCount,
      children: [],
    });
  }

  let root: UsageNode | null = null;
  for (const row of rows) {
    const node = byPath.get(row.path);
    if (!node) continue;
    if (row.parentPath === null) {
      root = node;
      continue;
    }
    byPath.get(row.parentPath)?.children.push(node);
  }
  return root;
}

/** Turns the database payload into what the page renders. */
export function toSnapshot(payload: SnapshotPayload): Snapshot | null {
  if (payload.empty || !payload.capturedAt) return null;
  return {
    schemaVersion: SCHEMA_VERSION,
    capturedAt: payload.capturedAt,
    device: {
      availableBytes: payload.device?.availableBytes ?? null,
      serverVersion: payload.device?.serverVersion ?? null,
    },
    locations: (payload.locations ?? []).map((location) => ({
      id: location.id,
      name: location.name,
      path: location.path,
      accessLevel: location.accessLevel,
      partial: location.partial,
      root: buildTree(location.nodes),
      ...(location.error ? { error: location.error } : {}),
    })),
  };
}
