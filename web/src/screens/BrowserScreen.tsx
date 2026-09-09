import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  diskUsage,
  listFiles,
  listLocations,
  type FileEntry,
  type StorageLocation,
  type UsageTreeNode,
} from '../api/device';
import { formatBytes, formatCount } from '../snapshot';
import { FileIcon, FolderIcon } from '../components/icons';
import { Preview } from '../components/Preview';
import { describeType } from '../components/fileType';
import { GROUP_LABELS, groupBytes, groupEntries, type GroupBy } from '../components/grouping';

/** The device caps a page at 200 entries per folder, so asking for more just gets 200. */
const PAGE = 200;

type Column = 'name' | 'modified' | 'type' | 'size';
type Direction = 'asc' | 'desc';
type Place = { locationId: string; path: string };

const COLUMNS: { id: Column; label: string; numeric: boolean }[] = [
  { id: 'name', label: 'Nombre', numeric: false },
  { id: 'modified', label: 'Fecha de modificación', numeric: false },
  { id: 'type', label: 'Tipo', numeric: false },
  { id: 'size', label: 'Tamaño', numeric: true },
];

export function BrowserScreen({ slug }: { slug: string }) {
  const [locations, setLocations] = useState<StorageLocation[] | null>(null);
  const [place, setPlace] = useState<Place | null>(null);
  const [entries, setEntries] = useState<FileEntry[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [column, setColumn] = useState<Column>('name');
  const [direction, setDirection] = useState<Direction>('asc');
  const [query, setQuery] = useState('');
  const [groupBy, setGroupBy] = useState<GroupBy>('none');
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  const [usage, setUsage] = useState<UsageTreeNode | null>(null);
  const [usageBusy, setUsageBusy] = useState(false);
  const [selected, setSelected] = useState<FileEntry | null>(null);
  // Names are unique within a folder, so they key the selection. It is cleared on navigation:
  // carrying a selection across folders would let one click act somewhere the eye is not.
  const [checked, setChecked] = useState<ReadonlySet<string>>(new Set());
  const anchor = useRef<string | null>(null);

  // Back and forward, as an address bar has them. The index walks the stack; a new navigation
  // truncates whatever was ahead of it, exactly like a browser.
  const history = useRef<Place[]>([]);
  const [cursor, setCursor] = useState(-1);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    listLocations(slug)
      .then((found) => {
        if (cancelled) return;
        setLocations(found);
        const first = found[0];
        if (first && history.current.length === 0) {
          history.current = [{ locationId: first.id, path: '' }];
          setCursor(0);
          setPlace(history.current[0] ?? null);
        }
      })
      .catch((e: unknown) => {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'No se pudieron leer las ubicaciones.');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [slug]);

  useEffect(() => {
    if (!place) return;
    let cancelled = false;
    setBusy(true);
    setError(null);
    setUsage(null);
    setSelected(null);
    setChecked(new Set());
    anchor.current = null;
    listFiles(slug, place.locationId, place.path, 0, PAGE)
      .then((listing) => {
        if (cancelled) return;
        setEntries(listing.files);
        setTruncated(listing.files.length >= PAGE);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setEntries(null);
        setError(e instanceof Error ? e.message : 'No se pudo abrir la carpeta.');
      })
      .finally(() => {
        if (!cancelled) setBusy(false);
      });
    return () => {
      cancelled = true;
    };
  }, [slug, place]);

  const go = useCallback(
    (next: Place) => {
      history.current = [...history.current.slice(0, cursor + 1), next];
      setCursor(history.current.length - 1);
      setPlace(next);
    },
    [cursor],
  );

  const step = useCallback((delta: number) => {
    setCursor((current) => {
      const next = current + delta;
      const target = history.current[next];
      if (!target) return current;
      setPlace(target);
      return next;
    });
  }, []);

  const measure = useCallback(async () => {
    if (!place) return;
    setUsageBusy(true);
    setError(null);
    try {
      setUsage(await diskUsage(slug, place.locationId, place.path, 1));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudo medir la carpeta.');
    } finally {
      setUsageBusy(false);
    }
  }, [slug, place]);

  const location = locations?.find((l) => l.id === place?.locationId) ?? null;

  const crumbs = useMemo(() => {
    if (!place) return [];
    const out = [{ name: location?.name ?? 'Raíz', path: '' }];
    let walked = '';
    for (const part of place.path.split('/').filter(Boolean)) {
      walked = walked === '' ? part : `${walked}/${part}`;
      out.push({ name: part, path: walked });
    }
    return out;
  }, [place, location]);

  const usageByName = useMemo(() => {
    const map = new Map<string, UsageTreeNode>();
    for (const child of usage?.children ?? []) {
      const leaf = child.path.split('/').filter(Boolean).pop();
      if (leaf) map.set(leaf, child);
    }
    return map;
  }, [usage]);

  const rows = useMemo(() => {
    if (!entries) return [];
    const needle = query.trim().toLowerCase();
    const filtered =
      needle === '' ? entries : entries.filter((e) => e.name.toLowerCase().includes(needle));
    const sign = direction === 'asc' ? 1 : -1;
    return [...filtered].sort((a, b) => {
      // Folders always lead, whatever the column: Explorer does this, and a folder's size is not
      // comparable to a file's anyway.
      if (a.is_directory !== b.is_directory) return a.is_directory ? -1 : 1;
      if (column === 'size') {
        const av = a.is_directory ? (usageByName.get(a.name)?.totalBytes ?? -1) : a.size;
        const bv = b.is_directory ? (usageByName.get(b.name)?.totalBytes ?? -1) : b.size;
        return (av - bv) * sign;
      }
      if (column === 'modified') return ((a.last_modified ?? 0) - (b.last_modified ?? 0)) * sign;
      if (column === 'type') {
        return describeType(a).localeCompare(describeType(b), 'es') * sign;
      }
      return a.name.localeCompare(b.name, 'es', { numeric: true }) * sign;
    });
  }, [entries, query, column, direction, usageByName]);

  const counts = useMemo(() => {
    const files = rows.filter((e) => !e.is_directory);
    return {
      dirs: rows.length - files.length,
      files: files.length,
      bytes: files.reduce((n, e) => n + e.size, 0),
    };
  }, [rows]);

  /**
   * Click selects one, ctrl toggles, shift extends from the last anchor — the three gestures a
   * file manager has, because anything else makes selecting forty files a forty-click job.
   */
  function pick(entry: FileEntry, event: { ctrlKey: boolean; metaKey: boolean; shiftKey: boolean }) {
    setSelected(entry);
    const additive = event.ctrlKey || event.metaKey;

    if (event.shiftKey && anchor.current) {
      const from = rows.findIndex((r) => r.name === anchor.current);
      const to = rows.findIndex((r) => r.name === entry.name);
      if (from !== -1 && to !== -1) {
        const [lo, hi] = from < to ? [from, to] : [to, from];
        const span = rows.slice(lo, hi + 1).map((r) => r.name);
        setChecked((current) => new Set(additive ? [...current, ...span] : span));
        return;
      }
    }

    anchor.current = entry.name;
    if (additive) {
      setChecked((current) => {
        const next = new Set(current);
        if (next.has(entry.name)) next.delete(entry.name);
        else next.add(entry.name);
        return next;
      });
      return;
    }
    setChecked(new Set([entry.name]));
  }

  function toggleAll() {
    setChecked((current) => (current.size === rows.length ? new Set() : new Set(rows.map((r) => r.name))));
  }

  const picked = useMemo(() => {
    const items = rows.filter((r) => checked.has(r.name));
    const files = items.filter((r) => !r.is_directory);
    return {
      count: items.length,
      bytes: files.reduce((n, r) => n + r.size, 0),
      dirs: items.length - files.length,
    };
  }, [rows, checked]);

  const groups = useMemo(() => groupEntries(rows, groupBy), [rows, groupBy]);

  function toggleGroup(key: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  function sortBy(next: Column) {
    if (next === column) setDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
    else {
      setColumn(next);
      setDirection(next === 'size' || next === 'modified' ? 'desc' : 'asc');
    }
  }

  const parent = place && place.path !== '' ? place.path.split('/').slice(0, -1).join('/') : null;

  return (
    <div className="explorer">
      <div className="ex-toolbar">
        <div className="ex-nav">
          <button
            type="button"
            className="icon-btn"
            title="Atrás"
            aria-label="Atrás"
            disabled={cursor <= 0}
            onClick={() => step(-1)}
          >
            ‹
          </button>
          <button
            type="button"
            className="icon-btn"
            title="Adelante"
            aria-label="Adelante"
            disabled={cursor >= history.current.length - 1}
            onClick={() => step(1)}
          >
            ›
          </button>
          <button
            type="button"
            className="icon-btn"
            title="Subir un nivel"
            aria-label="Subir un nivel"
            disabled={parent === null}
            onClick={() => place && parent !== null && go({ ...place, path: parent })}
          >
            ↑
          </button>
          <button
            type="button"
            className="icon-btn"
            title="Actualizar"
            aria-label="Actualizar"
            disabled={busy || !place}
            onClick={() => place && setPlace({ ...place })}
          >
            ⟳
          </button>
        </div>

        <nav className="ex-address" aria-label="Ruta">
          {crumbs.map((crumb, index) => (
            <span className="ex-crumb" key={crumb.path}>
              {index > 0 && <span className="ex-sep" aria-hidden="true">›</span>}
              <button
                type="button"
                disabled={index === crumbs.length - 1}
                onClick={() => place && go({ ...place, path: crumb.path })}
              >
                {crumb.name}
              </button>
            </span>
          ))}
        </nav>

        <input
          type="search"
          className="ex-search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Buscar en esta carpeta"
          aria-label="Buscar en esta carpeta"
        />

        <label className="ex-group">
          <span>Agrupar</span>
          <select value={groupBy} onChange={(e) => setGroupBy(e.target.value as GroupBy)}>
            {(Object.keys(GROUP_LABELS) as GroupBy[]).map((key) => (
              <option key={key} value={key}>
                {GROUP_LABELS[key]}
              </option>
            ))}
          </select>
        </label>

        <button type="button" className="ex-action" onClick={() => void measure()} disabled={usageBusy || !place}>
          {usageBusy ? 'Midiendo…' : 'Calcular tamaño'}
        </button>
      </div>

      <div className="ex-body">
        <aside className="ex-side" aria-label="Ubicaciones">
          <p className="ex-side-head">Este dispositivo</p>
          <ul>
            {(locations ?? []).map((entry) => (
              <li key={entry.id}>
                <button
                  type="button"
                  className={entry.id === place?.locationId ? 'ex-place current' : 'ex-place'}
                  aria-current={entry.id === place?.locationId ? 'true' : undefined}
                  onClick={() => go({ locationId: entry.id, path: '' })}
                >
                  <FolderIcon />
                  <span className="ex-place-name">{entry.name}</span>
                </button>
              </li>
            ))}
          </ul>
          {locations === null && <p className="ex-side-note">Cargando…</p>}
        </aside>

        <div className="ex-main">
          {error && <p className="error ex-error">{error}</p>}

          <div className="ex-grid" role="table" aria-label="Contenido de la carpeta">
            <div className="ex-head" role="row">
              <span className="ex-th check">
                <input
                  type="checkbox"
                  checked={rows.length > 0 && checked.size === rows.length}
                  ref={(el) => {
                    if (el) el.indeterminate = checked.size > 0 && checked.size < rows.length;
                  }}
                  onChange={toggleAll}
                  aria-label="Seleccionar todo"
                  disabled={rows.length === 0}
                />
              </span>
              {COLUMNS.map((col) => (
                <button
                  key={col.id}
                  type="button"
                  role="columnheader"
                  aria-sort={
                    column === col.id ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'
                  }
                  className={col.numeric ? 'ex-th num' : 'ex-th'}
                  onClick={() => sortBy(col.id)}
                >
                  {col.label}
                  <span className="ex-sort" aria-hidden="true">
                    {column === col.id ? (direction === 'asc' ? '▴' : '▾') : ''}
                  </span>
                </button>
              ))}
            </div>

            <div className="ex-rows">
              {busy && <p className="ex-status">Leyendo del dispositivo…</p>}

              {!busy && entries && rows.length === 0 && (
                <p className="ex-status">
                  {query ? `Ningún elemento coincide con «${query}».` : 'Esta carpeta está vacía.'}
                </p>
              )}

              {!busy &&
                groups.map((group) => (
                  <div key={group.key} className="ex-group-block">
                    {group.label !== '' && (
                      <button
                        type="button"
                        className="ex-group-head"
                        aria-expanded={!collapsed.has(group.key)}
                        onClick={() => toggleGroup(group.key)}
                      >
                        <span className={collapsed.has(group.key) ? 'ex-caret' : 'ex-caret open'} aria-hidden="true">
                          ›
                        </span>
                        <span className="ex-group-name">{group.label}</span>
                        <span className="ex-group-meta mono">
                          {formatCount(group.entries.length)}
                          {groupBytes(group) > 0 && ` · ${formatBytes(groupBytes(group))}`}
                        </span>
                      </button>
                    )}
                    {!collapsed.has(group.key) &&
                      group.entries.map((entry) => {
                  const measured = entry.is_directory ? usageByName.get(entry.name) : undefined;
                  const isSelected = selected?.name === entry.name && selected.path === entry.path;
                  return (
                    <div
                      key={`${entry.path}:${entry.name}`}
                      role="row"
                      className={
                        checked.has(entry.name)
                          ? 'ex-row checked'
                          : isSelected
                            ? 'ex-row selected'
                            : 'ex-row'
                      }
                      tabIndex={0}
                      onClick={(e) => pick(entry, e)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' && entry.is_directory && place) {
                          go({ ...place, path: join(place.path, entry.name) });
                        }
                        if (e.key === ' ') {
                          e.preventDefault();
                          pick(entry, { ctrlKey: true, metaKey: false, shiftKey: false });
                        }
                      }}
                      onDoubleClick={() =>
                        entry.is_directory && place && go({ ...place, path: join(place.path, entry.name) })
                      }
                    >
                      <span className="ex-cell check" role="cell">
                        <input
                          type="checkbox"
                          checked={checked.has(entry.name)}
                          onClick={(e) => e.stopPropagation()}
                          onChange={(e) =>
                            pick(entry, { ctrlKey: true, metaKey: false, shiftKey: e.nativeEvent instanceof MouseEvent && e.nativeEvent.shiftKey })
                          }
                          aria-label={`Seleccionar ${entry.name}`}
                        />
                      </span>
                      <span className="ex-cell name" role="cell">
                        {entry.is_directory ? <FolderIcon /> : <FileIcon name={entry.name} />}
                        <span className="ex-name">{entry.name}</span>
                      </span>
                      <span className="ex-cell" role="cell">
                        {formatDateTime(entry.last_modified)}
                      </span>
                      <span className="ex-cell" role="cell">
                        {describeType(entry)}
                      </span>
                      <span className="ex-cell num mono" role="cell">
                        {entry.is_directory
                          ? measured
                            ? formatBytes(measured.totalBytes)
                            : ''
                          : formatBytes(entry.size)}
                      </span>
                    </div>
                      );
                    })}
                  </div>
                ))}
            </div>
          </div>
        </div>

        <aside className="ex-details" aria-label="Detalles">
          {selected && place ? (
            <>
              <div className="ex-preview">
                <Preview
                  slug={slug}
                  locationId={place.locationId}
                  relativePath={join(place.path, selected.name)}
                  entry={selected}
                />
              </div>
              <h3 className="ex-details-name">{selected.name}</h3>
              <p className="ex-details-type">{describeType(selected)}</p>
              <dl className="ex-props">
                <div>
                  <dt>Tamaño</dt>
                  <dd className="mono">
                    {selected.is_directory
                      ? (usageByName.get(selected.name)?.totalBytes ?? null) === null
                        ? 'Sin calcular'
                        : formatBytes(usageByName.get(selected.name)?.totalBytes ?? 0)
                      : formatBytes(selected.size)}
                  </dd>
                </div>
                {selected.is_directory && usageByName.get(selected.name) && (
                  <div>
                    <dt>Archivos</dt>
                    <dd className="mono">{formatCount(usageByName.get(selected.name)?.fileCount ?? 0)}</dd>
                  </div>
                )}
                <div>
                  <dt>Modificado</dt>
                  <dd>{formatDateTime(selected.last_modified)}</dd>
                </div>
                {selected.mime_type && (
                  <div>
                    <dt>Formato</dt>
                    <dd className="mono">{selected.mime_type}</dd>
                  </div>
                )}
                <div>
                  <dt>Ruta</dt>
                  <dd className="mono ex-path">{selected.path || '/'}</dd>
                </div>
              </dl>
            </>
          ) : (
            <p className="ex-details-empty">
              Seleccioná un elemento para ver sus detalles. Doble clic abre una carpeta.
            </p>
          )}
        </aside>
      </div>

      <div className="ex-status-bar">
        <span>
          {picked.count > 0 && (
            <strong className="ex-picked">
              {formatCount(picked.count)} seleccionados
              {picked.bytes > 0 && ` · ${formatBytes(picked.bytes)}`}
              {picked.dirs > 0 && ` · ${formatCount(picked.dirs)} carpetas`}
              {' — '}
            </strong>
          )}
          {formatCount(rows.length)} elementos
          {counts.dirs > 0 && ` · ${formatCount(counts.dirs)} carpetas`}
          {counts.files > 0 && ` · ${formatCount(counts.files)} archivos, ${formatBytes(counts.bytes)}`}
        </span>
        <span className="ex-status-note">
          {truncated
            ? 'El dispositivo entrega hasta 200 elementos por carpeta'
            : usage
              ? 'Tamaños de carpeta calculados'
              : counts.dirs > 0
                ? '«Calcular tamaño» mide cada carpeta'
                : ''}
        </span>
      </div>
    </div>
  );
}

function join(base: string, name: string): string {
  return base === '' ? name : `${base}/${name}`;
}

function formatDateTime(millis: number | null): string {
  if (!millis) return '';
  return new Date(millis).toLocaleString('es', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}
