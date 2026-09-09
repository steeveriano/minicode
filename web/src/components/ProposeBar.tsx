import { useState } from 'react';
import type { FileEntry } from '../api/device';
import { KIND_LABEL, createProposal, type ProposalKind } from '../api/proposals';
import { formatBytes, formatCount } from '../snapshot';

/** Where an ARCHIVAR proposal sends things unless someone types otherwise. */
const ARCHIVE_DEFAULT = '_ARCHIVO';

const KIND_HELP: Record<ProposalKind, string> = {
  BORRAR: 'Se elimina del dispositivo. No se deshace.',
  MOVER: 'Cambia de carpeta dentro del dispositivo. Reversible moviéndolo de vuelta.',
  ARCHIVAR: 'Se aparta a una carpeta de archivo. Sigue en el dispositivo, fuera del camino.',
};

/**
 * The bar that appears once something is selected.
 *
 * It writes a proposal and stops. Nothing here reaches the device — that is the point of the split:
 * choosing what should happen and making it happen are different acts, and a proposal can sit with
 * its total visible until someone decides with the figure in front of them.
 */
export function ProposeBar({
  deviceSlug,
  locationId,
  folderPath,
  selected,
  onClear,
  onProposed,
}: {
  deviceSlug: string;
  locationId: string;
  folderPath: string;
  selected: FileEntry[];
  onClear: () => void;
  onProposed: () => void;
}) {
  const [kind, setKind] = useState<ProposalKind | null>(null);
  const [title, setTitle] = useState('');
  const [dest, setDest] = useState(ARCHIVE_DEFAULT);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const bytes = selected.reduce((n, e) => (e.is_directory ? n : n + e.size), 0);
  const dirs = selected.filter((e) => e.is_directory).length;

  function begin(next: ProposalKind) {
    setKind(next);
    setError(null);
    setTitle(
      next === 'BORRAR'
        ? `Borrar ${formatCount(selected.length)} elementos de ${folderPath || 'la raíz'}`
        : `${KIND_LABEL[next]} ${formatCount(selected.length)} elementos`,
    );
  }

  async function submit() {
    if (!kind) return;
    setBusy(true);
    setError(null);
    try {
      await createProposal({
        deviceSlug,
        kind,
        title: title.trim() || KIND_LABEL[kind],
        note: note.trim() || undefined,
        destPath: kind === 'BORRAR' ? undefined : dest.trim(),
        items: selected.map((entry) => ({
          locationId,
          // Relative to the location root: the device's own tools reject the prefixed form that
          // `list_files` reports.
          path: folderPath === '' ? entry.name : `${folderPath}/${entry.name}`,
          name: entry.name,
          sizeBytes: entry.is_directory ? 0 : entry.size,
          isDirectory: entry.is_directory,
        })),
      });
      setKind(null);
      setNote('');
      onClear();
      onProposed();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'No se pudo crear la propuesta.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="propose">
      <div className="propose-summary">
        <strong className="accent">{formatCount(selected.length)} seleccionados</strong>
        <span className="muted">
          {bytes > 0 && formatBytes(bytes)}
          {dirs > 0 && ` · ${formatCount(dirs)} carpetas sin medir`}
        </span>
      </div>

      {kind === null ? (
        <div className="propose-kinds">
          {(Object.keys(KIND_LABEL) as ProposalKind[]).map((k) => (
            <button key={k} type="button" className="ex-action" onClick={() => begin(k)}>
              Proponer {KIND_LABEL[k].toLowerCase()}
            </button>
          ))}
          <button type="button" className="ex-action" onClick={onClear}>
            Quitar selección
          </button>
        </div>
      ) : (
        <div className="propose-form">
          <p className="note">{KIND_HELP[kind]}</p>

          <label className="field wide">
            <span>Título</span>
            <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />
          </label>

          {kind !== 'BORRAR' && (
            <label className="field wide">
              <span>Carpeta destino</span>
              <input value={dest} onChange={(e) => setDest(e.target.value)} />
            </label>
          )}

          <label className="field wide">
            <span>Nota (opcional)</span>
            <input
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Por qué, para acordarse en un mes"
              maxLength={1000}
            />
          </label>

          {error && <p className="error">{error}</p>}

          <div className="propose-kinds">
            <button
              type="button"
              className="ex-action approve"
              disabled={busy || (kind !== 'BORRAR' && dest.trim() === '')}
              onClick={() => void submit()}
            >
              {busy ? 'Guardando…' : 'Guardar propuesta'}
            </button>
            <button type="button" className="ex-action" disabled={busy} onClick={() => setKind(null)}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
