import { useCallback, useEffect, useState } from 'react';
import {
  KIND_LABEL,
  STATE_LABEL,
  listProposals,
  proposalItems,
  setProposalState,
  type Proposal,
  type ProposalItem,
} from '../api/proposals';
import { formatBytes, formatCount } from '../snapshot';
import { FileIcon, FolderIcon } from '../components/icons';

const STATE_TONE: Record<Proposal['state'], string> = {
  BORRADOR: 'chip',
  APROBADA: 'chip ok',
  EJECUTADA: 'chip',
  DESCARTADA: 'chip warning',
};

export function ProposalsScreen({ deviceSlug }: { deviceSlug: string | null }) {
  const [proposals, setProposals] = useState<Proposal[] | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [items, setItems] = useState<Record<string, ProposalItem[]>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setProposals(await listProposals(deviceSlug ?? undefined));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudieron leer las propuestas.');
    }
  }, [deviceSlug]);

  useEffect(() => {
    void load();
  }, [load]);

  async function expand(id: string) {
    if (open === id) {
      setOpen(null);
      return;
    }
    setOpen(id);
    if (items[id]) return;
    try {
      setItems((current) => ({ ...current, [id]: [] }));
      const loaded = await proposalItems(id);
      setItems((current) => ({ ...current, [id]: loaded }));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudo leer el contenido.');
    }
  }

  async function change(id: string, state: 'APROBADA' | 'DESCARTADA' | 'BORRADOR') {
    setBusy(id);
    setError(null);
    try {
      await setProposalState(id, state);
      await load();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudo cambiar el estado.');
    } finally {
      setBusy(null);
    }
  }

  if (proposals === null) return <p className="empty">Cargando propuestas…</p>;

  if (proposals.length === 0) {
    return (
      <div className="gate">
        <h1>Ninguna propuesta todavía</h1>
        <p className="muted">
          En el navegador, seleccioná archivos o carpetas y usá <strong>Proponer</strong>. Nada se
          ejecuta al proponer: queda anotado acá con su total, para decidirlo con la cifra a la vista.
        </p>
      </div>
    );
  }

  return (
    <div className="proposals">
      {error && <p className="error">{error}</p>}

      {proposals.map((p) => {
        const expanded = open === p.proposalId;
        const rows = items[p.proposalId];
        return (
          <article key={p.proposalId} className="proposal">
            <header className="proposal-head">
              <div className="proposal-title">
                <h2>{p.title}</h2>
                <p className="muted">
                  {KIND_LABEL[p.kind]} · {formatCount(p.itemCount)} elementos ·{' '}
                  <strong className="accent">{formatBytes(p.totalBytes)}</strong>
                  {p.destPath && (
                    <>
                      {' '}
                      · destino <code>{p.destPath}</code>
                    </>
                  )}
                </p>
              </div>
              <span className={STATE_TONE[p.state]}>{STATE_LABEL[p.state]}</span>
            </header>

            {p.note && <p className="muted">{p.note}</p>}

            <p className="proposal-meta mono">
              Creada por {p.createdBy} el{' '}
              {new Date(p.createdAt).toLocaleString('es', { dateStyle: 'medium', timeStyle: 'short' })}
              {p.approvedAt &&
                ` · aprobada por ${p.approvedBy} el ${new Date(p.approvedAt).toLocaleDateString('es')}`}
            </p>

            <div className="proposal-actions">
              <button type="button" className="ex-action" onClick={() => void expand(p.proposalId)}>
                {expanded ? 'Ocultar elementos' : 'Ver elementos'}
              </button>

              {p.state === 'BORRADOR' && (
                <>
                  <button
                    type="button"
                    className="ex-action approve"
                    disabled={busy === p.proposalId}
                    onClick={() => void change(p.proposalId, 'APROBADA')}
                  >
                    Aprobar
                  </button>
                  <button
                    type="button"
                    className="ex-action"
                    disabled={busy === p.proposalId}
                    onClick={() => void change(p.proposalId, 'DESCARTADA')}
                  >
                    Descartar
                  </button>
                </>
              )}

              {p.state === 'APROBADA' && (
                <button
                  type="button"
                  className="ex-action"
                  disabled={busy === p.proposalId}
                  onClick={() => void change(p.proposalId, 'BORRADOR')}
                >
                  Retirar aprobación
                </button>
              )}
            </div>

            {p.state === 'APROBADA' && (
              <p className="note">
                Aprobada y todavía sin ejecutar. Ejecutarla requiere subir el permiso del dispositivo
                en Ajustes, y es un acto aparte — aprobar no mueve nada.
              </p>
            )}

            {expanded && (
              <div className="scroll">
                {rows === undefined || rows.length === 0 ? (
                  <p className="empty">{rows === undefined ? 'Leyendo…' : 'Sin elementos.'}</p>
                ) : (
                  <table className="files">
                    <thead>
                      <tr>
                        <th>Elemento</th>
                        <th>Ubicación</th>
                        <th className="num">Tamaño</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((item) => (
                        <tr key={`${item.locationId}:${item.path}`}>
                          <td>
                            <span className="file">
                              {item.isDirectory ? <FolderIcon /> : <FileIcon name={item.name} />}
                              {item.name}
                            </span>
                          </td>
                          <td className="dim mono">{item.locationId}</td>
                          <td className="num mono">
                            {item.isDirectory ? '—' : formatBytes(item.sizeBytes)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}
