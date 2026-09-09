import { useState } from 'react';
import { setAccessLevel, type AccessLevel, type FleetDevice } from '../api/fleet';

type Option = { value: AccessLevel; title: string; detail: string; tone: 'ok' | 'warning' | 'critical' };

/**
 * Three levels rather than a switch. The device already distinguishes quarantine — a reversible
 * move it can undo — from permanent deletion, and collapsing the two would throw away the only
 * safeguard it ships with.
 */
const OPTIONS: Option[] = [
  {
    value: 'read',
    title: 'Solo lectura',
    detail:
      'Listar ubicaciones y carpetas, medir uso de disco, leer un archivo, ver apps y cuarentenas. El panel no puede cambiar nada.',
    tone: 'ok',
  },
  {
    value: 'write',
    title: 'Edición',
    detail:
      'Además: escribir, mover, descargar y poner en cuarentena. Todo reversible — la cuarentena se restaura con un lote. No incluye borrado.',
    tone: 'warning',
  },
  {
    value: 'full',
    title: 'Completo',
    detail:
      'Además: borrado permanente y purga de cuarentena. Nada de esto se deshace.',
    tone: 'critical',
  },
];

export function SettingsScreen({
  devices,
  onChanged,
}: {
  devices: FleetDevice[];
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const governed = devices.filter((device) => device.transport === 'PROXY');

  async function change(slug: string, level: AccessLevel) {
    setBusy(slug);
    setError(null);
    try {
      await setAccessLevel(slug, level);
      onChanged();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudo guardar.');
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="settings">
      <section className="panel-block">
        <h2>Qué puede hacer el panel en cada dispositivo</h2>
        <p className="muted">
          El proxy reenvía al dispositivo únicamente las herramientas del nivel elegido, por nombre.
          Lo que quede fuera no llega al aparato aunque el aparato lo permita.
        </p>

        {error && <p className="error">{error}</p>}

        {/*
          Only the devices the proxy forwards to. Offering a permission level for a machine that
          nothing forwards to would be a control that changes nothing while implying the panel
          governs that machine.
        */}
        {governed.length === 0 && (
          <p className="note">Ningún dispositivo se navega desde el panel todavía.</p>
        )}

        {governed.map((device) => (
          <div className="device-perm" key={device.slug}>
            <h3>
              {device.alias} <span className="muted mono">{device.slug}</span>
            </h3>
            <div className="levels">
              {OPTIONS.map((option) => {
                const active = device.accessLevel === option.value;
                return (
                  <label
                    key={option.value}
                    className={active ? `level ${option.tone} active` : `level ${option.tone}`}
                  >
                    <input
                      type="radio"
                      name={`level-${device.slug}`}
                      value={option.value}
                      checked={active}
                      disabled={busy === device.slug}
                      onChange={() => void change(device.slug, option.value)}
                    />
                    <span className="level-title">{option.title}</span>
                    <span className="level-detail">{option.detail}</span>
                  </label>
                );
              })}
            </div>
          </div>
        ))}
      </section>

      <section className="panel-block warn">
        <h2>Antes de subir de nivel</h2>
        <p>
          El archivo multimedia de WhatsApp de este teléfono <strong>no tiene respaldo</strong>: la
          carpeta <code>Backups</code> está vacía y <code>Databases</code> no existe. Mientras eso
          siga así, «Completo» puede destruir la única copia de algo.
        </p>
        <p className="muted">
          «Edición» ya alcanza para ordenar: mover y poner en cuarentena son reversibles, y la
          cuarentena se restaura entera si algo sale mal.
        </p>
      </section>

      <section className="panel-block">
        <h2>Lo que este nivel no controla</h2>
        <p className="muted">
          Cambia lo que el <em>panel</em> puede pedir. No cambia lo que el dispositivo expone: quien
          tenga su token puede llamarlo directo. Para cerrar esa puerta hay que desactivar las
          herramientas en la app del teléfono, en Ajustes → Herramientas MCP.
        </p>
      </section>
    </div>
  );
}
