import { useEffect, useState } from 'react';
import { fetchFileBlob, readTextFile, type FileEntry } from '../api/device';
import { describeType, familyOf } from './fileType';
import { formatBytes } from '../snapshot';
import { FileIcon, FolderIcon } from './icons';

/** Text is read through the line-based tool, so a long file shows its head rather than all of it. */
const TEXT_LINES = 80;

type State =
  | { kind: 'idle' }
  | { kind: 'loading' }
  | { kind: 'blob'; url: string }
  | { kind: 'text'; body: string }
  | { kind: 'none'; reason: string };

/**
 * The preview pane.
 *
 * Bytes arrive through `/api/device/:slug/file`, which needs an Authorization header — so they are
 * fetched and turned into an object URL rather than pointed at with `<img src>`, which cannot carry
 * one. The URL is revoked when the selection changes, or the pane would leak one blob per click.
 */
export function Preview({
  slug,
  locationId,
  relativePath,
  entry,
}: {
  slug: string;
  locationId: string;
  /** Path relative to the location root, which is the form the device's tools accept. */
  relativePath: string;
  entry: FileEntry;
}) {
  const [state, setState] = useState<State>({ kind: 'idle' });
  const family = entry.is_directory ? 'folder' : familyOf(entry.name);
  const renderable = family === 'image' || isPdf(entry.name);
  const textual = family === 'document' && !isPdf(entry.name);

  useEffect(() => {
    if (entry.is_directory) {
      setState({ kind: 'none', reason: 'Es una carpeta.' });
      return;
    }

    let cancelled = false;
    let created: string | null = null;
    setState({ kind: 'loading' });

    const work = renderable
      ? fetchFileBlob(slug, locationId, relativePath).then((blob) => {
          if (cancelled) return;
          created = URL.createObjectURL(blob);
          setState({ kind: 'blob', url: created });
        })
      : textual
        ? readTextFile(slug, locationId, relativePath, TEXT_LINES).then((body) => {
            if (!cancelled) setState({ kind: 'text', body });
          })
        : Promise.resolve().then(() => {
            if (!cancelled) {
              setState({
                kind: 'none',
                reason:
                  family === 'video'
                    ? 'Los vídeos no se previsualizan: pesan más de lo que esta ruta puede servir.'
                    : 'Este formato no se previsualiza.',
              });
            }
          });

    void work.catch((e: unknown) => {
      if (!cancelled) {
        setState({ kind: 'none', reason: e instanceof Error ? e.message : 'No se pudo abrir.' });
      }
    });

    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [slug, locationId, relativePath, entry.is_directory, entry.name, renderable, textual, family]);

  return (
    <div className="pv">
      {state.kind === 'loading' && <p className="pv-note">Trayendo del dispositivo…</p>}

      {state.kind === 'blob' && family === 'image' && (
        <img className="pv-image" src={state.url} alt={entry.name} />
      )}

      {state.kind === 'blob' && isPdf(entry.name) && (
        <iframe className="pv-frame" src={state.url} title={entry.name} />
      )}

      {state.kind === 'text' && <pre className="pv-text">{state.body}</pre>}

      {(state.kind === 'none' || state.kind === 'idle') && (
        <div className="pv-fallback">
          {entry.is_directory ? <FolderIcon size={48} /> : <FileIcon name={entry.name} size={48} />}
          <p className="pv-note">
            {state.kind === 'none' ? state.reason : describeType(entry)}
          </p>
          {!entry.is_directory && <p className="pv-note mono">{formatBytes(entry.size)}</p>}
        </div>
      )}
    </div>
  );
}

function isPdf(name: string): boolean {
  return name.toLowerCase().endsWith('.pdf');
}
