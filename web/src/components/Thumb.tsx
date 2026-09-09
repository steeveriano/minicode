import { useEffect, useRef, useState } from 'react';
import { fetchFileBlob, type FileEntry } from '../api/device';
import { ensureThumb } from '../api/thumbs';
import { familyOf } from './fileType';
import { FileIcon, FolderIcon } from './icons';

/**
 * Above this, a tile waits to be asked.
 *
 * Making a thumbnail costs the whole original across the tunnel, once. For a phone photo that is a
 * couple of megabytes and worth it; for a video it is the entire clip, which is not something a
 * folder should spend by being scrolled past.
 */
const AUTO_BYTES = 6_000_000;

/**
 * One tile's picture.
 *
 * Two things keep this from emptying the monthly allowance on a single folder. It only starts once
 * the tile is near the viewport, so scrolling past two hundred photos fetches what was looked at
 * rather than all of them; and the result goes into the shared bucket, so the second visit — from
 * any machine, by anyone allowed in — costs nothing.
 */
export function Thumb({
  slug,
  locationId,
  relativePath,
  entry,
}: {
  slug: string;
  locationId: string;
  relativePath: string;
  entry: FileEntry;
}) {
  const host = useRef<HTMLDivElement>(null);
  const [near, setNear] = useState(false);
  const [url, setUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  // Only still images: a thumbnail is made by decoding the file, and the browser decodes pictures.
  const eligible = !entry.is_directory && familyOf(entry.name) === 'image';
  const heavy = entry.size > AUTO_BYTES;

  useEffect(() => {
    const node = host.current;
    if (!node || !eligible || near) return;
    const observer = new IntersectionObserver(
      (records) => {
        if (records.some((r) => r.isIntersecting)) {
          setNear(true);
          observer.disconnect();
        }
      },
      // A margin, so a tile is ready by the time it is actually looked at rather than after.
      { rootMargin: '300px' },
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [eligible, near]);

  useEffect(() => {
    if (!near || !eligible || heavy) return;
    let cancelled = false;
    let created: string | null = null;

    void ensureThumb(
      {
        slug,
        locationId,
        path: relativePath,
        sizeBytes: entry.size,
        lastModified: entry.last_modified,
      },
      () => fetchFileBlob(slug, locationId, relativePath),
    )
      .then((blob) => {
        if (cancelled) return;
        if (!blob) {
          setFailed(true);
          return;
        }
        created = URL.createObjectURL(blob);
        setUrl(created);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => {
      cancelled = true;
      if (created) URL.revokeObjectURL(created);
    };
  }, [near, eligible, heavy, slug, locationId, relativePath, entry.size, entry.last_modified]);

  if (url) return <img className="tile-img" src={url} alt="" loading="lazy" />;

  return (
    <div className="tile-blank" ref={host}>
      {entry.is_directory ? <FolderIcon size={44} /> : <FileIcon name={entry.name} size={44} />}
      {eligible && !failed && !heavy && <span className="tile-spinner" aria-hidden="true" />}
    </div>
  );
}
