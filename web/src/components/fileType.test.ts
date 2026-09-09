import { describe, expect, it } from 'vitest';
import type { FileEntry } from '../api/device';
import { describeType, extensionOf, familyOf } from './fileType';

function file(name: string, mime: string | null = null): FileEntry {
  return { name, path: '', is_directory: false, size: 0, last_modified: null, mime_type: mime };
}

const folder: FileEntry = {
  name: 'Camera',
  path: '',
  is_directory: true,
  size: 0,
  last_modified: null,
  mime_type: null,
};

describe('extensionOf', () => {
  it('reads a plain extension', () => {
    expect(extensionOf('IMG_0001.JPG')).toBe('jpg');
  });

  it('reads the last extension of a compound name', () => {
    expect(extensionOf('backup.tar.gz')).toBe('gz');
  });

  it('treats a leading dot as a hidden file, not an extension', () => {
    // `.nomedia` is the whole name; calling its extension "nomedia" would type it as a format.
    expect(extensionOf('.nomedia')).toBe('');
  });

  it('returns nothing for a name with no dot or a trailing dot', () => {
    expect(extensionOf('README')).toBe('');
    expect(extensionOf('weird.')).toBe('');
  });
});

describe('familyOf', () => {
  it('groups by what the file is', () => {
    expect(familyOf('a.jpg')).toBe('image');
    expect(familyOf('a.mp4')).toBe('video');
    expect(familyOf('a.opus')).toBe('audio');
    expect(familyOf('a.pdf')).toBe('document');
    expect(familyOf('a.apk')).toBe('app');
    expect(familyOf('a.zip')).toBe('archive');
  });

  it('falls back to other for anything unmapped', () => {
    expect(familyOf('a.qqq')).toBe('other');
    expect(familyOf('README')).toBe('other');
  });
});

describe('describeType', () => {
  it('names a folder', () => {
    expect(describeType(folder)).toBe('Carpeta de archivos');
  });

  it('names known formats in Spanish', () => {
    expect(describeType(file('a.jpg'))).toBe('Imagen JPEG');
    expect(describeType(file('a.apk'))).toBe('Paquete de Android');
    expect(describeType(file('a.pdf'))).toBe('Documento PDF');
  });

  it('is case insensitive', () => {
    expect(describeType(file('VID_20260101.MP4'))).toBe('Vídeo MP4');
  });

  it('falls back to the uppercased extension for an unknown one', () => {
    expect(describeType(file('data.crdownload'))).toBe('Archivo CRDOWNLOAD');
  });

  it('uses the reported mime type only when there is no extension', () => {
    expect(describeType(file('README', 'text/plain'))).toBe('text/plain');
  });

  it('says just "Archivo" when there is neither', () => {
    expect(describeType(file('README'))).toBe('Archivo');
  });
});
