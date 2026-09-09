import { parseUsage, type UsageTreeNode } from '../../scripts/parse-usage.mjs';
import { supabase } from '../supabase';

/**
 * Client for one device, through the panel's own proxy.
 *
 * Every call goes to `/api/device/:slug/…` on this origin. The device's bearer token lives in the
 * function's environment and never reaches here, so nothing in this file can leak one.
 */

export type DeviceStatus = {
  slug: string;
  status: 'online' | 'offline';
  elapsedMs: number;
  version?: string | null;
  upstreamStatus?: number;
};

export type StorageLocation = {
  id: string;
  name: string;
  path: string;
  description: string;
  available_bytes: number | null;
  allow_read: boolean;
  allow_write: boolean;
  allow_delete: boolean;
  access_level?: string;
};

export type FileEntry = {
  name: string;
  path: string;
  is_directory: boolean;
  size: number;
  last_modified: number | null;
  mime_type: string | null;
};

export type FileListing = { files: FileEntry[]; total?: number; offset?: number; limit?: number };

export type { UsageTreeNode };

/** Raised for anything the panel should show the user rather than swallow. */
export class DeviceError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'DeviceError';
  }
}

async function accessToken(): Promise<string> {
  const { data } = await supabase.auth.getSession();
  const token = data.session?.access_token;
  if (!token) throw new DeviceError('La sesión expiró. Volvé a entrar.', 401);
  return token;
}

function friendly(status: number, serverMessage: string | null): string {
  if (status === 403) return serverMessage ?? 'El panel no tiene permiso para esa operación.';
  if (status === 404) return 'Ese dispositivo no está configurado.';
  if (status === 504) return serverMessage ?? 'El dispositivo no respondió a tiempo.';
  if (status === 401) return 'La sesión expiró. Volvé a entrar.';
  return serverMessage ?? `El dispositivo devolvió un error (${status}).`;
}

export async function deviceStatus(slug: string): Promise<DeviceStatus> {
  const res = await fetch(`/api/device/${slug}/health`, {
    headers: { authorization: `Bearer ${await accessToken()}` },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { error?: string } | null;
    throw new DeviceError(friendly(res.status, body?.error ?? null), res.status);
  }
  return (await res.json()) as DeviceStatus;
}

/**
 * Content the device produced carries a warning banner as its first line, because a file name is
 * written by whoever made the file. It is stripped before parsing and the rest is treated as data:
 * every value from here is rendered as text, never as markup.
 */
function stripUntrustedBanner(text: string): string {
  if (!text.startsWith('CAUTION:')) return text;
  const newline = text.indexOf('\n');
  return newline === -1 ? '' : text.slice(newline + 1);
}

let nextId = 1;

async function callTool<T>(slug: string, name: string, args: Record<string, unknown>): Promise<T> {
  const res = await fetch(`/api/device/${slug}/mcp`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${await accessToken()}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: nextId++,
      method: 'tools/call',
      params: { name, arguments: args },
    }),
  });

  const payload = (await res.json().catch(() => null)) as
    | { error?: string | { message?: string }; result?: { isError?: boolean; content?: unknown[] } }
    | null;

  if (!res.ok) {
    const message = typeof payload?.error === 'string' ? payload.error : null;
    throw new DeviceError(friendly(res.status, message), res.status);
  }
  if (payload?.error) {
    const message =
      typeof payload.error === 'string' ? payload.error : (payload.error.message ?? 'Error del dispositivo.');
    throw new DeviceError(message, 200);
  }

  const first = payload?.result?.content?.[0] as { type?: string; text?: string } | undefined;
  if (!first || typeof first.text !== 'string') {
    throw new DeviceError('El dispositivo devolvió una respuesta que no se pudo leer.', 200);
  }
  const body = stripUntrustedBanner(first.text);
  if (payload?.result?.isError) throw new DeviceError(body.trim() || 'Error del dispositivo.', 200);

  try {
    return JSON.parse(body) as T;
  } catch {
    // Some tools answer in prose rather than JSON; hand the text back so the caller can show it.
    return body as unknown as T;
  }
}

/** The same call, handed back as text for the tools that answer in prose rather than JSON. */
async function callToolText(
  slug: string,
  name: string,
  args: Record<string, unknown>,
): Promise<string> {
  const value = await callTool<unknown>(slug, name, args);
  return typeof value === 'string' ? value : JSON.stringify(value);
}

export function listLocations(slug: string): Promise<StorageLocation[]> {
  return callTool<StorageLocation[]>(slug, 'android_list_storage_locations', {});
}

export function listFiles(
  slug: string,
  locationId: string,
  path: string,
  offset = 0,
  limit = 200,
): Promise<FileListing> {
  return callTool<FileListing>(slug, 'android_list_files', {
    location_id: locationId,
    path,
    offset,
    limit,
  });
}

/**
 * Measures a subtree. Depth is bounded on purpose: the proxy has about twenty seconds, and an
 * unbounded walk over a location with tens of thousands of files will spend all of it.
 *
 * This tool answers with an indented listing rather than JSON, parsed by the same module the
 * capture script uses — the format is a seam between two codebases here, and a second parser would
 * be the copy that drifts.
 */
export async function diskUsage(
  slug: string,
  locationId: string,
  path: string,
  maxDepth = 1,
): Promise<UsageTreeNode | null> {
  const text = await callToolText(slug, 'android_disk_usage', {
    location_id: locationId,
    path,
    max_depth: maxDepth,
  });
  return parseUsage(text);
}

/**
 * The bytes of one file, for the preview pane.
 *
 * Goes through this origin rather than the device: the page's `img-src` allows only `'self'`,
 * `data:` and `blob:`, and the function re-serving them keeps the device's temporary public URL out
 * of the browser entirely.
 *
 * @param path relative to the location root — `list_files` reports paths with the location id
 *   prefixed, and the device's own tools do not accept that form.
 */
export async function fetchFileBlob(slug: string, locationId: string, path: string): Promise<Blob> {
  const query = new URLSearchParams({ location: locationId, path });
  const res = await fetch(`/api/device/${slug}/file?${query}`, {
    headers: { authorization: `Bearer ${await accessToken()}` },
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { error?: string } | null;
    throw new DeviceError(body?.error ?? friendly(res.status, null), res.status);
  }
  return res.blob();
}

/** Head of a text file, through the line-based reader the device provides. */
export function readTextFile(
  slug: string,
  locationId: string,
  path: string,
  lines = 80,
): Promise<string> {
  return callToolText(slug, 'android_read_file', {
    location_id: locationId,
    path,
    offset: 1,
    limit: lines,
  });
}
