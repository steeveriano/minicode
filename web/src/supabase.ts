import { createClient } from '@supabase/supabase-js';

/**
 * The project's publishable credentials.
 *
 * These ship in the browser bundle on purpose: they name the project, not a person. Everything the
 * page can reach is decided server-side by `device_storage.viewers`, so a leaked publishable key
 * buys nothing — signing in as an allowlisted address is the only way through.
 */
const url = import.meta.env.VITE_SUPABASE_URL;
const key = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY;

if (!url || !key) {
  throw new Error(
    'Faltan VITE_SUPABASE_URL y VITE_SUPABASE_PUBLISHABLE_KEY. Copiá .env.example a .env.',
  );
}

export const supabase = createClient(url, key, {
  auth: {
    persistSession: true,
    autoRefreshToken: true,
    // The magic link comes back as a hash fragment; without this the session is never established.
    detectSessionInUrl: true,
  },
});
