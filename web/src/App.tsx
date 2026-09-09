import { useCallback, useEffect, useMemo, useState } from 'react';
import type { Session } from '@supabase/supabase-js';
import { Auth } from './Auth';
import { supabase } from './supabase';
import {
  formatBytes,
  formatCount,
  leafName,
  locationLabel,
  sortedChildren,
  totalBytes,
  totalFiles,
  toSnapshot,
  type LocationSnapshot,
  type Snapshot,
  type SnapshotPayload,
  type UsageNode,
} from './snapshot';

/** Depth at which the tree stops opening by itself; deeper levels are a deliberate tap. */
const AUTO_OPEN_DEPTH = 0;

type Access = 'loading' | 'anonymous' | 'denied' | 'granted';

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [access, setAccess] = useState<Access>('loading');
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, next) => setSession(next));
    return () => data.subscription.unsubscribe();
  }, []);

  const load = useCallback(async () => {
    if (!session) {
      setAccess('anonymous');
      setSnapshot(null);
      return;
    }
    setAccess('loading');
    setFailure(null);
    const { data, error } = await supabase.rpc('device_storage_latest_snapshot');
    if (error) {
      setFailure(error.message);
      setAccess('denied');
      return;
    }
    // The function returns null for a caller who is signed in but not on the allowlist. That is a
    // different answer from "there is no data", and the page must not conflate them.
    if (data === null) {
      setAccess('denied');
      return;
    }
    setSnapshot(toSnapshot(data as SnapshotPayload));
    setAccess('granted');
  }, [session]);

  useEffect(() => {
    void load();
  }, [load]);

  if (access === 'loading') {
    return <p className="empty">Cargando…</p>;
  }
  if (access === 'anonymous') {
    return <Auth />;
  }
  if (access === 'denied') {
    return (
      <div className="gate">
        <h1>Sin acceso</h1>
        <p className="muted">
          Iniciaste sesión como <strong>{session?.user.email}</strong>, pero esa dirección no está
          autorizada a ver este inventario.
        </p>
        {failure && <p className="error">{failure}</p>}
        <button type="button" className="ghost" onClick={() => supabase.auth.signOut()}>
          Cerrar sesión
        </button>
      </div>
    );
  }
  if (!snapshot) {
    return (
      <div className="gate">
        <h1>Todavía no hay mediciones</h1>
        <p className="muted">Corré la captura contra el teléfono y volvé a entrar.</p>
        <button type="button" className="ghost" onClick={() => supabase.auth.signOut()}>
          Cerrar sesión
        </button>
      </div>
    );
  }
  return <Viewer snapshot={snapshot} email={session?.user.email ?? ''} />;
}

function Viewer({ snapshot, email }: { snapshot: Snapshot; email: string }) {
  const [query, setQuery] = useState('');
  const [expandAll, setExpandAll] = useState(false);

  const grandTotal = useMemo(() => totalBytes(snapshot), [snapshot]);
  const files = useMemo(() => totalFiles(snapshot), [snapshot]);
  const locations = useMemo(
    () =>
      [...snapshot.locations].sort((a, b) => (b.root?.totalBytes ?? 0) - (a.root?.totalBytes ?? 0)),
    [snapshot],
  );

  const needle = query.trim().toLowerCase();
  const visible = needle === '' ? locations : locations.filter((l) => locationMatches(l, needle));

  return (
    <div className="wrap">
      <header>
        <h1>Almacenamiento del dispositivo</h1>
        <p className="captured">
          Medido el{' '}
          {new Date(snapshot.capturedAt).toLocaleString('es', {
            dateStyle: 'long',
            timeStyle: 'short',
          })}
          {snapshot.device.serverVersion ? ` · ${snapshot.device.serverVersion}` : ''}
        </p>
        <p className="captured">
          {email}{' '}
          <button type="button" className="linklike" onClick={() => supabase.auth.signOut()}>
            cerrar sesión
          </button>
        </p>
      </header>

      <section className="tiles" aria-label="Resumen">
        <Tile label="Ocupado" value={formatBytes(grandTotal)} accent />
        <Tile label="Archivos" value={formatCount(files)} />
        <Tile
          label="Libre"
          value={
            snapshot.device.availableBytes === null
              ? '—'
              : formatBytes(snapshot.device.availableBytes)
          }
        />
        <Tile label="Ubicaciones" value={formatCount(locations.length)} />
      </section>

      <div className="controls">
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Buscar una carpeta…"
          aria-label="Buscar una carpeta"
        />
        <button type="button" onClick={() => setExpandAll((v) => !v)}>
          {expandAll ? 'Contraer todo' : 'Expandir todo'}
        </button>
      </div>

      {visible.length === 0 ? (
        <p className="empty">Ninguna carpeta coincide con «{query}».</p>
      ) : (
        visible.map((location) => (
          <LocationPanel
            key={location.id}
            location={location}
            grandTotal={grandTotal}
            filter={needle}
            expandAll={expandAll}
          />
        ))
      )}

      <footer>
        <p>
          Instantánea generada con <code>npm run capture</code> contra el servidor MCP del teléfono y
          guardada en Supabase. Esta página nunca se conecta al dispositivo: sólo consulta la base,
          así que funciona con el celular apagado. Quién puede leerla lo decide el servidor, no el
          navegador.
        </p>
      </footer>
    </div>
  );
}

function Tile({ label, value, accent = false }: { label: string; value: string; accent?: boolean }) {
  return (
    <div className="tile">
      <div className={accent ? 'value accent mono' : 'value mono'}>{value}</div>
      <div className="label">{label}</div>
    </div>
  );
}

function LocationPanel({
  location,
  grandTotal,
  filter,
  expandAll,
}: {
  location: LocationSnapshot;
  grandTotal: number;
  filter: string;
  expandAll: boolean;
}) {
  const label = locationLabel(location);
  return (
    <section className="panel" aria-label={label.name}>
      {location.root === null ? (
        <>
          <StaticRow
            name={label.name}
            detail={label.path}
            chip={{ kind: 'critical', text: 'Sin datos' }}
          />
          <p className="note">{location.error ?? 'La medición no completó.'}</p>
        </>
      ) : (
        <>
          <TreeRow
            node={location.root}
            displayName={label.name}
            displayPath={label.path}
            grandTotal={grandTotal}
            depth={0}
            filter={filter}
            expandAll={expandAll}
            chip={
              location.partial ? { kind: 'warning' as const, text: 'Parcial' } : undefined
            }
          />
          {location.partial && (
            <p className="note">
              Android sólo deja ver aquí los archivos que esta app creó. El total es un piso, no la
              cifra real.
            </p>
          )}
        </>
      )}
    </section>
  );
}

function StaticRow({
  name,
  detail,
  chip,
}: {
  name: string;
  detail: string;
  chip?: { kind: 'warning' | 'critical'; text: string };
}) {
  return (
    <div className="row leaf">
      <div className="name">
        <span className="caret" aria-hidden="true" />
        <span className="text">{name}</span>
        {chip && <span className={`chip ${chip.kind}`}>{chip.text}</span>}
      </div>
      <div className="figures mono">
        <span className="files">{detail}</span>
      </div>
    </div>
  );
}

function TreeRow({
  node,
  displayName,
  displayPath,
  grandTotal,
  depth,
  filter,
  expandAll,
  chip,
}: {
  node: UsageNode;
  displayName?: string;
  displayPath?: string;
  grandTotal: number;
  depth: number;
  filter: string;
  expandAll: boolean;
  chip?: { kind: 'warning' | 'critical'; text: string };
}) {
  const [openedByUser, setOpenedByUser] = useState<boolean | null>(null);
  const children = useMemo(() => sortedChildren(node), [node]);
  const hasChildren = children.length > 0;

  // A search opens whatever it matched; otherwise the user's own choice wins, and failing that the
  // depth default. Without the search override a match three levels down would stay invisible.
  const forcedOpen = expandAll || (filter !== '' && subtreeMatches(node, filter));
  const open = openedByUser ?? (forcedOpen || depth < AUTO_OPEN_DEPTH);

  const share = grandTotal === 0 ? 0 : node.totalBytes / grandTotal;
  const name = displayName ?? leafName(node.path);

  return (
    <>
      <button
        type="button"
        className={hasChildren ? 'row' : 'row leaf'}
        aria-expanded={hasChildren ? open : undefined}
        onClick={hasChildren ? () => setOpenedByUser(!open) : undefined}
        style={{ paddingLeft: `${14 + depth * 16}px` }}
      >
        <div className="name">
          <span className={open && hasChildren ? 'caret open' : 'caret'} aria-hidden="true">
            {hasChildren ? '›' : ''}
          </span>
          <span className="text">{name}</span>
          {displayPath && <span className="path mono">{displayPath}</span>}
          {chip && <span className={`chip ${chip.kind}`}>{chip.text}</span>}
        </div>
        <div className="figures">
          <span className="mono">{formatBytes(node.totalBytes)}</span>
          <span className="files mono">{formatCount(node.fileCount)}</span>
        </div>
        <div
          className="bar"
          role="img"
          aria-label={`${(share * 100).toFixed(1)} % del total`}
          title={`${formatBytes(node.totalBytes)} · ${formatCount(node.fileCount)} archivos · ${(
            share * 100
          ).toFixed(1)} % del total`}
        >
          <i style={{ width: `${Math.max(share * 100, share > 0 ? 0.6 : 0)}%` }} />
        </div>
      </button>

      {open &&
        children.map((child) => (
          <TreeRow
            key={child.path}
            node={child}
            grandTotal={grandTotal}
            depth={depth + 1}
            filter={filter}
            expandAll={expandAll}
          />
        ))}
    </>
  );
}

/** True when this node or anything under it carries the search text. */
function subtreeMatches(node: UsageNode, needle: string): boolean {
  if (node.path.toLowerCase().includes(needle)) return true;
  return node.children.some((child) => subtreeMatches(child, needle));
}

function locationMatches(location: LocationSnapshot, needle: string): boolean {
  if (location.name.toLowerCase().includes(needle)) return true;
  if (location.path.toLowerCase().includes(needle)) return true;
  return location.root !== null && subtreeMatches(location.root, needle);
}
