import { familyOf, type FileFamily } from './fileType';

/**
 * Icons drawn inline rather than loaded.
 *
 * The page's content security policy allows images only from this origin and `data:`, and an icon
 * font would be a third-party request. These are a few paths each; a sprite sheet would cost more
 * than it saves at this count.
 */

const FAMILY_COLOR: Record<FileFamily, string> = {
  folder: '#e3b341',
  image: '#5eb0ef',
  video: '#c297ff',
  audio: '#4ec9a5',
  document: '#e06c75',
  archive: '#d9a441',
  app: '#7ee787',
  other: '#8b9c92',
};

export function FolderIcon({ size = 16 }: { size?: number }) {
  return (
    <svg
      className="ico"
      width={size}
      height={size}
      viewBox="0 0 16 16"
      aria-hidden="true"
      focusable="false"
    >
      <path
        d="M1.5 3.5A1.5 1.5 0 0 1 3 2h3.2c.4 0 .78.16 1.06.44L8.5 3.5H13a1.5 1.5 0 0 1 1.5 1.5v7A1.5 1.5 0 0 1 13 13.5H3A1.5 1.5 0 0 1 1.5 12z"
        fill={FAMILY_COLOR.folder}
        opacity="0.9"
      />
      <path d="M1.5 5.5h13V6H1.5z" fill="#000" opacity="0.15" />
    </svg>
  );
}

export function FileIcon({ name, size = 16 }: { name: string; size?: number }) {
  const family = familyOf(name);
  const color = FAMILY_COLOR[family];
  return (
    <svg
      className="ico"
      width={size}
      height={size}
      viewBox="0 0 16 16"
      aria-hidden="true"
      focusable="false"
    >
      {/* A page with its corner turned, so the shape reads as a file at 16 px. */}
      <path d="M3.5 1.5h6L13 5v9.5H3.5z" fill={color} opacity="0.22" />
      <path d="M3.5 1.5h6L13 5v9.5H3.5z" fill="none" stroke={color} strokeWidth="1" opacity="0.8" />
      <path d="M9.5 1.5V5H13" fill="none" stroke={color} strokeWidth="1" opacity="0.8" />
      {family === 'image' && <circle cx="6.2" cy="8.2" r="1.1" fill={color} />}
      {family === 'image' && <path d="M4.5 12l2.4-2.6L8.6 11l1.4-1.4 2 2.4z" fill={color} />}
      {family === 'video' && <path d="M6.4 8l3.6 2.2-3.6 2.2z" fill={color} />}
      {family === 'audio' && (
        <path
          d="M6.2 12.2V8.4l3.6-.9v3.5"
          fill="none"
          stroke={color}
          strokeWidth="1.1"
          strokeLinecap="round"
        />
      )}
      {family === 'audio' && <circle cx="5.6" cy="12.3" r="1.1" fill={color} />}
      {family === 'document' && (
        <>
          <path d="M5.2 8.6h5.6M5.2 10.4h5.6M5.2 12.2h3.4" stroke={color} strokeWidth="1" strokeLinecap="round" />
        </>
      )}
      {family === 'archive' && (
        <path d="M8 6.4v1.2M8 8.8v1.2M8 11.2v1.2" stroke={color} strokeWidth="1.4" strokeLinecap="round" />
      )}
      {family === 'app' && <circle cx="8" cy="10.4" r="2.1" fill="none" stroke={color} strokeWidth="1.2" />}
    </svg>
  );
}
