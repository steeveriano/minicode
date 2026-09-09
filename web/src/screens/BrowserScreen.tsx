import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  diskUsage,
  listFiles,
  listLocations,
  type FileEntry,
  type StorageLocation,
  type UsageTreeNode,
} from '../api/device';
import { formatBytes, formatCount } from '../snapshot';

/** The device caps a page at 200 entries, so asking for more just gets 200. */
const PAGE = 200;

type Crumb = { name: string; path: string };

type Sort = 'name' | 'size' | 'date';

export function BrowserScreen({ slug }: { slug: string }) {
  const [locations, setLocations] = useState<StorageLocation[] | null>(null);
  const [locationId, setLocationId] = useState<string | null>(null);
  const [path, setPath] = useState('');
  const [entries, setEntries] = useState<FileEntry[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sort, setSort] = useState<Sort>('size');
  const [query, setQuery] = useState('');
  const [usage, setUsage] = useState<UsageTreeNode | null>(null);
  const [usageBusy, setUsageBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    listLocations(slug)
      .then((found) => {
        if (cancelled) return;
        setLocations(found);
        setLocationId((current) => current ?? found[0]?.id ?? null);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : 'No se pudieron leer las ubicaciones.');
      });
    return () => {
      cancelled = true;
    };
  }, [slug]);

  const open = useCallback(
    async (nextLocation: string, nextPath: string) => {
      setBusy(true);
      setError(null);
      setUsage(null);
      try {
        const listing = await listFiles(slug, nextLocation, nextPath, 0, PAGE);
        setEntries(listing.files);
        setTruncated(listing.files.length >= PAGE);
        setLocationId(nextLocation);
        setPath(nextPath);
      } catch (e: unknown) {
        setEntries(null);
        setError(e instanceof Error ? e.message : 'No se pudo abrir la carpeta.');
      } finally {
        setBusy(false);
      }
    },
    [slug],
  );

  useEffect(() => {
    if (locationId) void open(locationId, '');
    // Only when the chosen location changes: `open` also runs on every navigation.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locationId, slug]);

  const measure = useCallback(async () => {
    if (!locationId) return;
    setUsageBusy(true);
    setError(null);
    try {
      setUsage(await diskUsage(slug, locationId, path, 1));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudo medir la carpeta.');
    } finally {
      setUsageBusy(false);
    }
  }, [slug, locationId, path]);

  const crumbs = useMemo<Crumb[]>(() => {
    const location = locations?.find((l) => l.id === locationId);
    const out: Crumb[] = [{ name: location?.name ?? 'Raíz', path: '' }];
    if (path === '') return out;
    const parts = path.split('/').filter(Boolean);
    let walked = '';
    for (const part of parts) {
      walked = walked === '' ? part : `${walked}/${part}`;
      out.push({ name: part, path: walked });
    }
    return out;
  }, [locations, locationId, path]);

  const shown = useMemo(() => {
    if (!entries) return [];
    const needle = query.trim().toLowerCase();
    const filtered = needle === '' ? entries : entries.filter((e) => e.name.toLowerCase().includes(needle));
    const sorted = [...filtered].sort((a, b) => {
      // Directories first at every sort: a folder and a file are not comparable by size.
      if (a.is_directory !== b.is_directory) return a.is_directory ? -1 : 1;
      if (sort === 'size') return b.size - a.size;
      if (sort === 'date') return (b.last_modified ?? 0) - (a.last_modified ?? 0);
      return a.name.localeCompare(b.name, 'es');
    });
    return sorted;
  }, [entries, query, sort]);

  const usageByName = useMemo(() => {
    const map = new Map<string, UsageTreeNode>();
    for (const child of usage?.children ?? []) {
      const leaf = child.path.split('/').filter(Boolean).pop();
      if (leaf) map.set(leaf, child);
    }
    return map;
  }, [usage]);

  const totals = useMemo(() => {
    const files = shown.filter((e) => !e.is_directory);
    return { dirs: shown.length - files.length, files: files.length, bytes: files.reduce((n, e) => n + e.size, 0) };
  }, [shown]);

  return (
    <div className="browser">
      <div className="browser-bar">
        <label className="field">
          <span>Ubicación</span>
          <select
            value={locationId ?? ''}
            onChange={(e) => setLocationId(e.target.value)}
            disabled={!locations}
          >
            {(locations ?? []).map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </select>
        </label>

        <label className="field">
          <span>Ordenar</span>
          <select value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
            <option value="size">Tamaño</option>
            <option value="name">Nombre</option>
            <option value="date">Fecha</option>
          </select>
        </label>

        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Filtrar en esta carpeta…"
          aria-label="Filtrar en esta carpeta"
        />

        <button type="button" onClick={() => void measure()} disabled={usageBusy || !locationId}>
          {usageBusy ? 'Midiendo…' : 'Medir carpetas'}
        </button>
      </div>

      <nav className="crumbs" aria-label="Ruta">
        {crumbs.map((crumb, index) => (
          <span key={crumb.path}>
            {index > 0 && <span className="sep">/</span>}
            <button
              type="button"
              className="linklike"
              onClick={() => locationId && void open(locationId, crumb.path)}
              disabled={index === crumbs.length - 1}
            >
              {crumb.name}
            </button>
          </span>
        ))}
      </nav>

      {error && <p className="error">{error}</p>}

      {busy && <p className="empty">Leyendo del dispositivo…</p>}

      {!busy && entries && shown.length === 0 && (
        <p className="empty">{query ? `Nada coincide con «${query}».` : 'Carpeta vacía.'}</p>
      )}

      {!busy && shown.length > 0 && (
        <>
          <table className="files">
            <thead>
              <tr>
                <th>Nombre</th>
                <th className="num">Tamaño</th>
                <th className="num">Modificado</th>
              </tr>
            </thead>
            <tbody>
              {shown.map((entry) => {
                const measured = entry.is_directory ? usageByName.get(entry.name) : undefined;
                return (
                  <tr key={`${entry.path}:${entry.name}`}>
                    <td>
                      {entry.is_directory ? (
                        <button
                          type="button"
                          className="dir"
                          onClick={() => locationId && void open(locationId, joinPath(path, entry.name))}
                        >
                          <span className="ic" aria-hidden="true">
                            ›
                          </span>
                          {entry.name}
                        </button>
                      ) : (
                        <span className="file">
                          <span className="ic" aria-hidden="true" />
                          {entry.name}
                        </span>
                      )}
                    </td>
                    <td className="num mono">
                      {entry.is_directory
                        ? measured
                          ? `${formatBytes(measured.totalBytes)} · ${formatCount(measured.fileCount)}`
                          : '—'
                        : formatBytes(entry.size)}
                    </td>
                    <td className="num mono">{formatDate(entry.last_modified)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>

          <p className="note">
            {formatCount(totals.dirs)} carpetas · {formatCount(totals.files)} archivos ·{' '}
            {formatBytes(totals.bytes)} en esta página
            {truncated && ' · el dispositivo corta en 200 entradas por carpeta'}
            {!usage && totals.dirs > 0 && ' · «Medir carpetas» calcula el peso de cada subcarpeta'}
          </p>
        </>
      )}
    </div>
  );
}

function joinPath(base: string, name: string): string {
  return base === '' ? name : `${base}/${name}`;
}

function formatDate(millis: number | null): string {
  if (!millis) return '—';
  return new Date(millis).toLocaleDateString('es', { year: 'numeric', month: 'short', day: '2-digit' });
}
