import { useCallback, useEffect, useState } from 'react';
import { deviceStatus, type DeviceStatus } from '../api/device';
import { recordSeen, type FleetDevice } from '../api/fleet';
import { formatBytes } from '../snapshot';

const LEVEL_LABEL: Record<FleetDevice['accessLevel'], string> = {
  read: 'Solo lectura',
  write: 'Edición',
  full: 'Completo',
};

const PLATFORM_LABEL: Record<FleetDevice['platform'], string> = {
  ANDROID: 'Android',
  IOS: 'iOS',
  PC: 'PC',
};

export function DevicesScreen({
  devices,
  selected,
  onSelect,
}: {
  devices: FleetDevice[];
  selected: string | null;
  onSelect: (slug: string) => void;
}) {
  const [probes, setProbes] = useState<Record<string, DeviceStatus | { error: string }>>({});
  const [probing, setProbing] = useState(false);

  const probeAll = useCallback(async () => {
    setProbing(true);
    const next: Record<string, DeviceStatus | { error: string }> = {};
    for (const device of devices) {
      // A reporting machine is not probed: there is nothing to probe, and a failed probe would
      // paint it red for being exactly what it is.
      if (device.transport === 'REPORT') continue;
      try {
        const status = await deviceStatus(device.slug);
        next[device.slug] = status;
        // Record what the probe actually found, so the list reflects measurement rather than a
        // value someone typed once.
        await recordSeen(device.slug, status.status, status.version ?? null).catch(() => undefined);
      } catch (e: unknown) {
        next[device.slug] = { error: e instanceof Error ? e.message : 'No se pudo consultar.' };
      }
    }
    setProbes(next);
    setProbing(false);
  }, [devices]);

  useEffect(() => {
    void probeAll();
  }, [probeAll]);

  if (devices.length === 0) {
    return (
      <div className="gate">
        <h1>Sin dispositivos</h1>
        <p className="muted">
          Todavía no hay ninguno registrado. Se agrega con una fila en <code>fleet.devices</code>: si
          el panel lo navega, además con dos variables de entorno para su URL y su token.
        </p>
      </div>
    );
  }

  return (
    <div className="devices">
      <div className="browser-bar">
        <button type="button" onClick={() => void probeAll()} disabled={probing}>
          {probing ? 'Consultando…' : 'Volver a consultar'}
        </button>
        <p className="note">
          El estado se pregunta al aparato en el momento, no se lee de una bandera guardada.
        </p>
      </div>

      <div className="cards">
        {devices.map((device) => {
          const probe = probes[device.slug];
          const reports = device.transport === 'REPORT';
          const online = probe && 'status' in probe && probe.status === 'online';
          const failed = probe && 'error' in probe;
          const used =
            device.capacityBytes !== null && device.freeBytes !== null
              ? device.capacityBytes - device.freeBytes
              : null;
          const share =
            used !== null && device.capacityBytes ? Math.min(used / device.capacityBytes, 1) : 0;

          return (
            <article
              key={device.slug}
              className={device.slug === selected ? 'card selected' : 'card'}
              aria-current={device.slug === selected ? 'true' : undefined}
            >
              <header className="card-head">
                <div>
                  <h2>{device.alias}</h2>
                  <p className="muted mono">
                    {PLATFORM_LABEL[device.platform]} · {device.slug}
                  </p>
                </div>
                <span
                  className={
                    reports
                      ? 'chip'
                      : failed
                        ? 'chip critical'
                        : online
                          ? 'chip ok'
                          : probe
                            ? 'chip warning'
                            : 'chip'
                  }
                >
                  {reports
                    ? device.lastSeenAt
                      ? `Reportó ${formatWhen(device.lastSeenAt)}`
                      : 'Sin reportar'
                    : failed
                      ? 'Sin acceso'
                      : online
                        ? 'En línea'
                        : probe
                          ? 'Fuera de línea'
                          : 'Consultando…'}
                </span>
              </header>

              {device.note && <p className="muted">{device.note}</p>}

              <dl className="facts">
                <div>
                  <dt>Permiso</dt>
                  <dd>{LEVEL_LABEL[device.accessLevel]}</dd>
                </div>
                <div>
                  <dt>Servidor</dt>
                  <dd className="mono">
                    {(probe && 'version' in probe ? probe.version : device.serverVersion) ?? '—'}
                  </dd>
                </div>
                <div>
                  <dt>{reports ? 'Enlace' : 'Respuesta'}</dt>
                  <dd className="mono">
                    {reports ? 'Reporta' : probe && 'elapsedMs' in probe ? `${probe.elapsedMs} ms` : '—'}
                  </dd>
                </div>
                <div>
                  <dt>Libre</dt>
                  <dd className="mono">
                    {device.freeBytes === null ? '—' : formatBytes(device.freeBytes)}
                  </dd>
                </div>
              </dl>

              {used !== null && device.capacityBytes !== null && (
                <div className="bar" role="img" aria-label={`${(share * 100).toFixed(0)} % ocupado`}>
                  <i style={{ width: `${share * 100}%` }} />
                </div>
              )}

              {failed && <p className="error">{probe.error}</p>}

              <button
                type="button"
                className={device.slug === selected ? 'ghost' : ''}
                onClick={() => onSelect(device.slug)}
                disabled={reports || device.slug === selected}
                title={reports ? 'Esta máquina reporta por su cuenta; el panel no la navega.' : undefined}
              >
                {reports ? 'No se navega' : device.slug === selected ? 'Seleccionado' : 'Navegar este'}
              </button>
            </article>
          );
        })}
      </div>
    </div>
  );
}

function formatWhen(iso: string): string {
  const when = new Date(iso);
  const days = Math.floor((Date.now() - when.getTime()) / 86_400_000);
  if (days === 0) return 'hoy';
  if (days === 1) return 'ayer';
  if (days < 30) return `hace ${days} días`;
  return when.toLocaleDateString('es', { day: '2-digit', month: 'short', year: 'numeric' });
}
