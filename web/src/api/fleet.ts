import { supabase } from '../supabase';

/** What a device's access level lets the proxy forward. Stored per device, never per session. */
export type AccessLevel = 'read' | 'write' | 'full';

/**
 * How the panel reaches a machine — a different question from what kind of machine it is.
 *
 * `PROXY` is forwarded to by this site's own function, so it can be probed and browsed. `REPORT`
 * signs in and writes what it found; nothing forwards to it, and probing one would always fail and
 * read as a broken device rather than as a machine that simply is not served that way.
 */
export type Transport = 'PROXY' | 'REPORT';

export type FleetDevice = {
  slug: string;
  alias: string;
  platform: 'ANDROID' | 'IOS' | 'PC';
  note: string | null;
  accessLevel: AccessLevel;
  transport: Transport;
  lastSeenAt: string | null;
  lastStatus: 'online' | 'offline' | 'unknown' | null;
  capacityBytes: number | null;
  freeBytes: number | null;
  serverVersion: string | null;
  createdAt: string;
};

/**
 * The registered devices, or null when the caller is not an allowlisted viewer.
 *
 * Null and `[]` are different answers and the panel must not conflate them: one means "you may not
 * see this", the other "there is nothing yet".
 */
export async function fleetDevices(): Promise<FleetDevice[] | null> {
  const { data, error } = await supabase.rpc('fleet_devices');
  if (error) throw new Error(error.message);
  return data === null ? null : (data as FleetDevice[]);
}

export async function setAccessLevel(slug: string, level: AccessLevel): Promise<void> {
  const { error } = await supabase.rpc('fleet_set_access_level', { p_slug: slug, p_level: level });
  if (error) throw new Error(error.message);
}

/** Records what a live probe found, so the device list shows measured reachability, not a guess. */
export async function recordSeen(
  slug: string,
  status: 'online' | 'offline',
  serverVersion?: string | null,
): Promise<void> {
  const { error } = await supabase.rpc('fleet_device_seen', {
    p_slug: slug,
    p_status: status,
    p_server_version: serverVersion ?? null,
  });
  if (error) throw new Error(error.message);
}
