import type { FileEntry } from '../api/device';

/**
 * A human name for what a file is, in the shape a file manager shows it.
 *
 * Extension first, MIME second. The device reports a MIME type from the same extension in most
 * cases, and where it does not — a `.crdownload`, a file with no extension — the generic fallback
 * is more honest than a guess.
 */
const BY_EXTENSION: Record<string, string> = {
  jpg: 'Imagen JPEG',
  jpeg: 'Imagen JPEG',
  png: 'Imagen PNG',
  gif: 'Imagen GIF',
  webp: 'Imagen WebP',
  heic: 'Imagen HEIC',
  bmp: 'Imagen BMP',
  tif: 'Imagen TIFF',
  tiff: 'Imagen TIFF',
  dng: 'Imagen RAW',
  svg: 'Imagen SVG',

  mp4: 'Vídeo MP4',
  mov: 'Vídeo QuickTime',
  mkv: 'Vídeo Matroska',
  webm: 'Vídeo WebM',
  avi: 'Vídeo AVI',
  '3gp': 'Vídeo 3GP',
  m4v: 'Vídeo M4V',

  mp3: 'Audio MP3',
  m4a: 'Audio M4A',
  aac: 'Audio AAC',
  wav: 'Audio WAV',
  ogg: 'Audio OGG',
  opus: 'Audio Opus',
  flac: 'Audio FLAC',

  pdf: 'Documento PDF',
  doc: 'Documento de Word',
  docx: 'Documento de Word',
  xls: 'Hoja de cálculo',
  xlsx: 'Hoja de cálculo',
  ppt: 'Presentación',
  pptx: 'Presentación',
  txt: 'Documento de texto',
  csv: 'Valores separados por comas',
  epub: 'Libro EPUB',
  rtf: 'Documento RTF',

  apk: 'Paquete de Android',
  zip: 'Carpeta comprimida',
  rar: 'Archivo RAR',
  '7z': 'Archivo 7z',
  gz: 'Archivo GZIP',
  tar: 'Archivo TAR',

  json: 'Archivo JSON',
  xml: 'Documento XML',
  html: 'Documento HTML',
  db: 'Base de datos',
  log: 'Registro',
  nomedia: 'Marcador de Android',
};

/** The broad family a name belongs to, used to pick an icon. */
export type FileFamily = 'folder' | 'image' | 'video' | 'audio' | 'document' | 'archive' | 'app' | 'other';

const FAMILY_BY_EXTENSION: Record<string, FileFamily> = {
  jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image', heic: 'image',
  bmp: 'image', tif: 'image', tiff: 'image', dng: 'image', svg: 'image',
  mp4: 'video', mov: 'video', mkv: 'video', webm: 'video', avi: 'video', '3gp': 'video', m4v: 'video',
  mp3: 'audio', m4a: 'audio', aac: 'audio', wav: 'audio', ogg: 'audio', opus: 'audio', flac: 'audio',
  pdf: 'document', doc: 'document', docx: 'document', xls: 'document', xlsx: 'document',
  ppt: 'document', pptx: 'document', txt: 'document', csv: 'document', epub: 'document', rtf: 'document',
  apk: 'app',
  zip: 'archive', rar: 'archive', '7z': 'archive', gz: 'archive', tar: 'archive',
};

export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  // A leading dot is a hidden file, not an extension: `.nomedia` has no type of its own.
  if (dot <= 0 || dot === name.length - 1) return '';
  return name.slice(dot + 1).toLowerCase();
}

export function familyOf(name: string): FileFamily {
  return FAMILY_BY_EXTENSION[extensionOf(name)] ?? 'other';
}

export function describeType(entry: FileEntry): string {
  if (entry.is_directory) return 'Carpeta de archivos';
  const extension = extensionOf(entry.name);
  const known = BY_EXTENSION[extension];
  if (known) return known;
  if (extension) return `Archivo ${extension.toUpperCase()}`;
  return entry.mime_type ?? 'Archivo';
}
