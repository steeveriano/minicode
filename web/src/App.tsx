import { useCallback, useEffect, useState } from 'react';
import type { Session } from '@supabase/supabase-js';
import { Auth } from './Auth';
import { supabase } from './supabase';
import { fleetDevices, type FleetDevice } from './api/fleet';
import { BrowserScreen } from './screens/BrowserScreen';
import { DevicesScreen } from './screens/DevicesScreen';
import { ProposalsScreen } from './screens/ProposalsScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { SnapshotScreen } from './screens/SnapshotScreen';
import { toSnapshot, type Snapshot, type SnapshotPayload } from './snapshot';

type Access = 'loading' | 'anonymous' | 'denied' | 'granted';
type Tab = 'devices' | 'browser' | 'proposals' | 'snapshot' | 'settings';

const TABS: { id: Tab; label: string }[] = [
  { id: 'devices', label: 'Dispositivos' },
  { id: 'browser', label: 'Navegador' },
  { id: 'proposals', label: 'Propuestas' },
  { id: 'snapshot', label: 'Censo' },
  { id: 'settings', label: 'Ajustes' },
];

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [access, setAccess] = useState<Access>('loading');
  const [devices, setDevices] = useState<FleetDevice[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('devices');

  useEffect(() => {
    void supabase.auth.getSession().then(({ data }) => setSession(data.session));
    const { data } = supabase.auth.onAuthStateChange((_event, next) => setSession(next));
    return () => data.subscription.unsubscribe();
  }, []);

  const loadDevices = useCallback(async () => {
    const found = await fleetDevices();
    // Null is "you may not see this", not "there is nothing". The two must not be conflated.
    if (found === null) return null;
    setDevices(found);
    setSelected((current) => current ?? found[0]?.slug ?? null);
    return found;
  }, []);

  const load = useCallback(async () => {
    if (!session) {
      setAccess('anonymous');
      return;
    }
    setAccess('loading');
    setFailure(null);
    try {
      const found = await loadDevices();
      if (found === null) {
        setAccess('denied');
        return;
      }
      // The stored census is a separate feed and its absence is not a failure: a fleet with no
      // capture yet is a normal state, and the live browser works without one.
      const { data } = await supabase.rpc('device_storage_latest_snapshot');
      setSnapshot(data ? toSnapshot(data as SnapshotPayload) : null);
      setAccess('granted');
    } catch (e: unknown) {
      setFailure(e instanceof Error ? e.message : 'No se pudo cargar.');
      setAccess('denied');
    }
  }, [session, loadDevices]);

  useEffect(() => {
    void load();
  }, [load]);

  if (access === 'loading') return <p className="empty">Cargando…</p>;
  if (access === 'anonymous') return <Auth />;

  if (access === 'denied') {
    return (
      <div className="gate">
        <h1>Sin acceso</h1>
        <p className="muted">
          Iniciaste sesión como <strong>{session?.user.email}</strong>, pero esa dirección no está
          autorizada a ver este panel.
        </p>
        {failure && <p className="error">{failure}</p>}
        <button type="button" className="ghost" onClick={() => void supabase.auth.signOut()}>
          Cerrar sesión
        </button>
      </div>
    );
  }

  const device = devices.find((d) => d.slug === selected) ?? null;

  return (
    <div className="wrap">
      <header className="shell-head">
        <div>
          <h1>Panel de dispositivos</h1>
          <p className="captured">
            {session?.user.email}{' '}
            <button type="button" className="linklike" onClick={() => void supabase.auth.signOut()}>
              cerrar sesión
            </button>
          </p>
        </div>
        {device && (
          <div className="device-pill">
            <span className="muted">Navegando</span>
            <strong>{device.alias}</strong>
          </div>
        )}
      </header>

      <nav className="tabs" aria-label="Secciones">
        {TABS.map((entry) => (
          <button
            key={entry.id}
            type="button"
            className={tab === entry.id ? 'tab active' : 'tab'}
            aria-current={tab === entry.id ? 'page' : undefined}
            onClick={() => setTab(entry.id)}
          >
            {entry.label}
          </button>
        ))}
      </nav>

      {tab === 'devices' && (
        <DevicesScreen
          devices={devices}
          selected={selected}
          onSelect={(slug) => {
            setSelected(slug);
            setTab('browser');
          }}
        />
      )}

      {tab === 'browser' &&
        (selected ? (
          <BrowserScreen slug={selected} onProposed={() => setTab('proposals')} />
        ) : (
          <p className="empty">Elegí un dispositivo en la pestaña Dispositivos.</p>
        ))}

      {tab === 'proposals' && <ProposalsScreen deviceSlug={selected} />}

      {tab === 'snapshot' &&
        (snapshot ? (
          <SnapshotScreen snapshot={snapshot} email={session?.user.email ?? ''} />
        ) : (
          <div className="gate">
            <h1>Todavía no hay censo guardado</h1>
            <p className="muted">
              El navegador funciona igual: lee del dispositivo en vivo. El censo es la vista
              histórica, y se genera con <code>npm run capture</code>.
            </p>
          </div>
        ))}

      {tab === 'settings' && <SettingsScreen devices={devices} onChanged={() => void loadDevices()} />}

      <footer>
        <p>
          El navegador lee del dispositivo en el momento, a través de una función de este mismo
          sitio que guarda el token y reenvía sólo las herramientas del nivel elegido. El censo
          consulta Supabase y funciona con el teléfono apagado.
        </p>
      </footer>
    </div>
  );
}
