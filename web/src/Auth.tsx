import { useState, type FormEvent } from 'react';
import { supabase } from './supabase';

type Mode = 'magic' | 'password';

/**
 * The gate.
 *
 * This project's auth users belong to another application, so signing in is not by itself
 * permission to be here — the database decides that from an allowlist. The screen therefore never
 * claims access; it only establishes who is asking.
 *
 * The failure messages deliberately do not distinguish "no such address" from "wrong password", and
 * a magic link is reported as sent whatever the address: telling a stranger which addresses exist is
 * the one thing a login screen must not do.
 */
export function Auth() {
  const [mode, setMode] = useState<Mode>('magic');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'magic') {
        const { error } = await supabase.auth.signInWithOtp({
          email,
          options: { emailRedirectTo: window.location.origin },
        });
        if (error) throw error;
        setSent(true);
      } else {
        const { error } = await supabase.auth.signInWithPassword({ email, password });
        if (error) throw new Error('Correo o contraseña incorrectos.');
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo iniciar sesión.');
    } finally {
      setBusy(false);
    }
  }

  if (sent) {
    return (
      <div className="gate">
        <h1>Revisá tu correo</h1>
        <p className="muted">
          Si <strong>{email}</strong> tiene acceso, le llegó un enlace para entrar. Abrilo en este
          mismo navegador.
        </p>
        <button type="button" className="ghost" onClick={() => setSent(false)}>
          Usar otro correo
        </button>
      </div>
    );
  }

  return (
    <div className="gate">
      <h1>Almacenamiento del dispositivo</h1>
      <p className="muted">Acceso restringido. Entrá con tu correo.</p>

      <form onSubmit={submit}>
        <label className="label" htmlFor="email">
          Correo
        </label>
        <input
          id="email"
          type="email"
          required
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder="vos@ejemplo.com"
        />

        {mode === 'password' && (
          <>
            <label className="label" htmlFor="password">
              Contraseña
            </label>
            <input
              id="password"
              type="password"
              required
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </>
        )}

        {error && <p className="error">{error}</p>}

        <button type="submit" className="primary" disabled={busy}>
          {busy ? 'Un momento…' : mode === 'magic' ? 'Enviarme un enlace' : 'Entrar'}
        </button>
      </form>

      <button
        type="button"
        className="ghost"
        onClick={() => {
          setMode(mode === 'magic' ? 'password' : 'magic');
          setError(null);
        }}
      >
        {mode === 'magic' ? 'Prefiero usar contraseña' : 'Prefiero un enlace por correo'}
      </button>
    </div>
  );
}
