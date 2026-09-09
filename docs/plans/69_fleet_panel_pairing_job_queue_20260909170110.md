<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Fleet panel: device pairing, outbound job queue, engine ingest, approval inbox

Implements the panel side of the engine↔panel contract (`policy_version: 1.1`, `engine_version: 0.1.0`).
The engine (separate workstream, PC) owns SCAN/ANALYZE/CLASSIFY/PLAN/EXECUTE/VERIFY and emits JSONL
locally. This plan builds everything the contract assigns to the panel: device identity, pairing,
the outbound transport, ingest of the three aggregate feeds, the APPROVE surface, and a scheduler
that only proposes.

## Contract invariants — binding on every task below

| Invariant | Consequence for this plan |
|---|---|
| `media_id = sha256(bytes)` is the only cross-device identity | `media` PK is `media_id`. Paths are per-device attributes, never identity. Cross-device state is a JOIN, never a name heuristic. |
| `safe_to_purge_staging` / `safe_to_release_mobile` are decided by the engine | Panel and app store and display them. No code in this plan may compute or infer either. |
| `PC → móvil` never automatic | Job kinds in this plan never write to a device. Scheduler emits proposals only. |
| `REMUX ≠ RECODE` | Rendered as distinct actions with distinct labels. Never collapsed into "convertir". |
| `confidence: 0.0` means "no sé" | Rendered with its `status` + `review_reason`, never as a low score or a progress bar. |
| The manifest never crosses the cable | Ingest accepts aggregates, REVIEW rows and opportunities only. No endpoint in this plan accepts per-file manifest rows in bulk. |
| APPROVE is manual and binds to `plan_hash` | An approval row stores the hash it approved. Execution carrying a different hash is rejected. |

## Threat model for the device transport

The device holds a long-lived credential and talks to Supabase without a user session. Therefore:

- Device tokens are random 32-byte values, transmitted once at pairing, stored **hashed** (sha256) server-side.
- Every device-facing RPC is `security definer`, resolves the device by token hash, and is granted to `anon` only.
- Tables in `device_registry` and `fleet` keep RLS enabled with **no** policies (fails closed); all access is via RPC.
- Pairing codes are single-use, 10-minute TTL, and bound to the minting user at creation.
- A revoked device (`revoked_at` set) fails every RPC on the next call.

---

## User story 1 — Device identity and pairing

**Why**: the engine's `device` field is `PC|ANDROID|IOS` and cannot tell two phones apart. Everything
downstream (cross-device JOIN, per-device jobs, per-device opportunities) needs a stable id the engine
can emit and the panel can resolve to a human name. Pairing is assigned entirely to the panel by the
contract (§6).

**Acceptance criteria**

- [ ] A signed-in viewer can mint a pairing code in the panel and see it with its expiry.
- [ ] The app, given that code, registers itself and receives a device token it persists.
- [ ] The same app re-paired with a new code keeps its `device_id` and rotates only the token.
- [ ] An expired, already-used, or unknown code is rejected with a distinct, non-enumerable error.
- [ ] A device the viewer revokes in the panel fails its next call.
- [ ] `device.schema.json` exists and documents the shape the engine must emit.

### Task 1.1 — Supabase schema `device_registry`

**Actions**

**Action 1.1.1** — create `supabase/migrations/0002_device_registry.sql`

```sql
create schema if not exists device_registry;
revoke all on schema device_registry from anon, authenticated;

create table device_registry.devices (
  device_id      uuid primary key default gen_random_uuid(),
  owner_email    text not null references device_storage.viewers(email) on delete cascade,
  alias          text not null,
  platform       text not null check (platform in ('ANDROID','IOS','PC')),
  app_version    text,
  token_hash     bytea not null,
  capacity_bytes bigint,
  free_bytes     bigint,
  live_url       text,
  last_seen_at   timestamptz,
  created_at     timestamptz not null default now(),
  revoked_at     timestamptz
);
create unique index devices_token_hash_idx on device_registry.devices (token_hash) where revoked_at is null;
create index devices_owner_idx on device_registry.devices (owner_email);

create table device_registry.pairing_codes (
  code        text primary key,
  owner_email text not null references device_storage.viewers(email) on delete cascade,
  device_id   uuid references device_registry.devices(device_id) on delete cascade,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  consumed_at timestamptz
);
create index pairing_codes_owner_idx on device_registry.pairing_codes (owner_email);

alter table device_registry.devices enable row level security;
alter table device_registry.pairing_codes enable row level security;
```

**Action 1.1.2** — append to the same migration: caller-identity helper reused by every RPC below

```sql
create or replace function device_registry.current_viewer_email()
returns text language sql stable security definer set search_path = '' as $$
  select v.email
  from auth.users u
  join device_storage.viewers v on v.email = lower(u.email)
  where u.id = auth.uid() and u.email_confirmed_at is not null;
$$;
```

Returns null for anonymous, unconfirmed, and non-viewer callers — every user-facing RPC below treats
null as "not authorised" and raises the same generic error, so membership is not enumerable.

**Action 1.1.3** — append: code minting (user-facing)

```sql
create or replace function public.fleet_mint_pairing_code(p_alias text, p_device_id uuid default null)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_email text; v_code text; v_expires timestamptz;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then raise exception 'not authorised' using errcode = '42501'; end if;
  if p_device_id is not null and not exists (
       select 1 from device_registry.devices d
       where d.device_id = p_device_id and d.owner_email = v_email and d.revoked_at is null)
  then raise exception 'not authorised' using errcode = '42501'; end if;

  -- Crockford base32 without I, L, O, U: unambiguous when read off a screen and typed on a phone.
  select string_agg(substr('0123456789ABCDEFGHJKMNPQRSTVWXYZ',
                           1 + floor(random() * 32)::int, 1), '')
    into v_code from generate_series(1, 8);
  v_expires := now() + interval '10 minutes';

  insert into device_registry.pairing_codes (code, owner_email, device_id, expires_at)
  values (v_code, v_email, p_device_id, v_expires);

  return jsonb_build_object('code', v_code, 'expires_at', v_expires, 'alias', p_alias);
end;
$$;
revoke all on function public.fleet_mint_pairing_code(text, uuid) from public, anon;
grant execute on function public.fleet_mint_pairing_code(text, uuid) to authenticated;
```

**Action 1.1.4** — append: code redemption (device-facing, unauthenticated by design)

```sql
create or replace function public.fleet_claim_pairing_code(
  p_code text, p_alias text, p_platform text, p_app_version text)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_row device_registry.pairing_codes%rowtype; v_token text; v_device_id uuid;
begin
  select * into v_row from device_registry.pairing_codes
   where code = upper(trim(p_code)) for update;
  if v_row.code is null or v_row.consumed_at is not null or v_row.expires_at < now() then
    raise exception 'invalid or expired code' using errcode = '22023';
  end if;
  if p_platform not in ('ANDROID','IOS','PC') then
    raise exception 'invalid platform' using errcode = '22023';
  end if;

  v_token := encode(extensions.gen_random_bytes(32), 'hex');

  if v_row.device_id is null then
    insert into device_registry.devices (owner_email, alias, platform, app_version, token_hash)
    values (v_row.owner_email, p_alias, p_platform, p_app_version,
            extensions.digest(v_token, 'sha256'))
    returning device_id into v_device_id;
  else
    -- Re-pairing an existing device rotates the token and keeps device_id, so history survives.
    update device_registry.devices
       set token_hash = extensions.digest(v_token, 'sha256'),
           alias = p_alias, app_version = p_app_version, revoked_at = null
     where device_id = v_row.device_id
    returning device_id into v_device_id;
  end if;

  update device_registry.pairing_codes set consumed_at = now() where code = v_row.code;
  return jsonb_build_object('device_id', v_device_id, 'device_token', v_token);
end;
$$;
revoke all on function public.fleet_claim_pairing_code(text, text, text, text) from public;
grant execute on function public.fleet_claim_pairing_code(text, text, text, text) to anon, authenticated;
```

**Action 1.1.5** — append: device resolution helper used by every device-facing RPC

```sql
create or replace function device_registry.device_for_token(p_token text)
returns uuid language sql stable security definer set search_path = '' as $$
  select d.device_id from device_registry.devices d
   where d.token_hash = extensions.digest(p_token, 'sha256') and d.revoked_at is null;
$$;
```

**Action 1.1.6** — append: heartbeat (device-facing) and listing/revocation (user-facing)

```sql
create or replace function public.fleet_device_heartbeat(
  p_token text, p_capacity_bytes bigint, p_free_bytes bigint, p_live_url text default null)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;
  update device_registry.devices
     set capacity_bytes = p_capacity_bytes, free_bytes = p_free_bytes,
         live_url = p_live_url, last_seen_at = now()
   where device_id = v_device;
  return jsonb_build_object('device_id', v_device, 'ok', true);
end;
$$;
revoke all on function public.fleet_device_heartbeat(text, bigint, bigint, text) from public;
grant execute on function public.fleet_device_heartbeat(text, bigint, bigint, text) to anon, authenticated;

create or replace function public.fleet_devices()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_email text;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then return null; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
      'deviceId', d.device_id, 'alias', d.alias, 'platform', d.platform,
      'appVersion', d.app_version, 'capacityBytes', d.capacity_bytes,
      'freeBytes', d.free_bytes, 'liveUrl', d.live_url, 'lastSeenAt', d.last_seen_at,
      'createdAt', d.created_at) order by d.created_at)
    from device_registry.devices d
    where d.owner_email = v_email and d.revoked_at is null), '[]'::jsonb);
end;
$$;
revoke all on function public.fleet_devices() from public, anon;
grant execute on function public.fleet_devices() to authenticated;

create or replace function public.fleet_revoke_device(p_device_id uuid)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare v_email text;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then raise exception 'not authorised' using errcode = '42501'; end if;
  update device_registry.devices set revoked_at = now()
   where device_id = p_device_id and owner_email = v_email and revoked_at is null;
  return found;
end;
$$;
revoke all on function public.fleet_revoke_device(uuid) from public, anon;
grant execute on function public.fleet_revoke_device(uuid) to authenticated;
```

**Definition of Done**

- [ ] Migration applies cleanly against the live project.
- [ ] `fleet_devices()` returns `null` for anonymous and for an authenticated non-viewer (verified by
      `set local request.jwt.claims`, not by assumption).
- [ ] `fleet_claim_pairing_code` rejects expired, consumed and unknown codes with the same message.
- [ ] Re-pairing an existing device preserves `device_id`.
- [ ] A revoked device's token fails `fleet_device_heartbeat`.

### Task 1.2 — `device.schema.json` (panel-owned, per contract §6)

**Action 1.2.1** — create `docs/schemas/device.schema.json`

JSON Schema draft 2020-12, `$id: https://tierraparaiso.net/schemas/device.schema.json`. Required:
`device_id` (uuid), `alias` (string, 1..64), `platform` (enum ANDROID|IOS|PC). Optional:
`app_version`, `capacity_bytes` (integer ≥ 0), `free_bytes` (integer ≥ 0), `last_seen_at`
(date-time), `live_url` (uri). `additionalProperties: false`.

Document in the schema description that the engine emits `device_id` and the panel resolves the
alias — the engine never stores the human name.

**Definition of Done**

- [ ] Schema validates a representative device object and rejects an unknown platform.
- [ ] Referenced from `docs/PROJECT.md` in the storage/fleet section.

### Task 1.3 — App: device identity and pairing client

**Action 1.3.1** — create `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/FleetIdentity.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val fleetIdentityJson = Json { ignoreUnknownKeys = true }

/**
 * Panel pairing state. [deviceToken] authenticates every outbound call and is never logged.
 * An unpaired device carries blank values for all three fields.
 */
@Serializable
data class FleetIdentity(
    val deviceId: String = "",
    val deviceToken: String = "",
    val panelUrl: String = "",
    val alias: String = "",
    val pairedAtMillis: Long = 0L,
) {
    val paired: Boolean get() = deviceId.isNotBlank() && deviceToken.isNotBlank() && panelUrl.isNotBlank()

    companion object {
        fun fromJsonOrDefault(json: String): FleetIdentity =
            try {
                fleetIdentityJson.decodeFromString(serializer(), json)
            } catch (_: Exception) {
                FleetIdentity()
            }
    }

    fun toJson(): String = fleetIdentityJson.encodeToString(serializer(), this)
}
```

**Action 1.3.2** — create `.../data/repository/FleetIdentityRepository.kt` (interface) and
`FleetIdentityRepositoryImpl.kt`

Interface: `val identity: Flow<FleetIdentity>`, `suspend fun get(): FleetIdentity`,
`suspend fun save(identity: FleetIdentity)`, `suspend fun clear()`.
Impl reads/writes a single `stringPreferencesKey("fleet_identity")` in the existing DataStore
injected in `AppModule.provideDataStore`. Follows `EventChannelSettingsImpl` exactly.

Constraint: `SettingsChangeLogger` must NOT receive `deviceToken`. Log only
`deviceId` and `paired`.

**Action 1.3.3** — create `.../services/fleet/FleetClient.kt` (interface) and `FleetClientImpl.kt`

```kotlin
interface FleetClient {
    suspend fun claimPairingCode(panelUrl: String, anonKey: String, code: String, alias: String): Result<FleetIdentity>
    suspend fun heartbeat(capacityBytes: Long, freeBytes: Long, liveUrl: String?): Result<Unit>
}
```

`FleetClientImpl` uses the Ktor client already on the app classpath. It POSTs to
`$panelUrl/rest/v1/rpc/<fn>` with headers `apikey` and `Content-Type: application/json`.
Platform is always `"ANDROID"`; `app_version` is `BuildConfig.VERSION_NAME`.

Errors map to `Result.failure` with a message safe to show in the UI. The device token is never
included in a log line or an error message.

**Action 1.3.4** — modify `.../di/AppModule.kt`: add `@Binds` for `FleetIdentityRepository` and
`FleetClient`, in the existing alphabetical block.

**Definition of Done**

- [ ] Pairing persists across app restart.
- [ ] `clear()` leaves the device unpaired and stops outbound calls.
- [ ] No log line, `ServerLogEntry` or error message contains the device token.

### Task 1.4 — App: pairing UI

**Action 1.4.1** — create `.../ui/screens/settings/FleetSettingsScreen.kt`

Unpaired state: panel URL field (prefilled from `BuildConfig` default), 8-character code field
(uppercased on input, grouped `XXXX-XXXX` for readability, hyphen stripped before sending), alias
field prefilled with `Build.MODEL`, and a "Vincular" button disabled until the code has 8 valid
characters.

Paired state: alias, device id (truncated), paired-at date, last sync, and an "Desvincular" button
behind a confirmation dialog.

Uses `SettingsSection` / `SettingsRow` from `ui/components/SettingsPrimitives.kt` and a `HelpHint`
on each field, matching the rest of the settings screens.

**Action 1.4.2** — create `.../ui/viewmodels/FleetSettingsViewModel.kt`

`StateFlow<FleetSettingsUiState>` with `identity`, `busy`, `error`. `pair(panelUrl, code, alias)`
calls `FleetClient.claimPairingCode` then `FleetIdentityRepository.save`. `unpair()` calls `clear()`.

**Action 1.4.3** — modify `.../ui/screens/settings/SettingsIndexScreen.kt` and
`.../ui/navigation/` to add the "Panel" entry and route.

**Definition of Done**

- [ ] Entering a valid code moves the screen to the paired state without restarting the app.
- [ ] An invalid code shows the server's message and leaves the device unpaired.
- [ ] Screen renders correctly in dark mode with 48dp touch targets and content descriptions.

### Task 1.5 — Web: pairing screen

**Action 1.5.1** — create `web/src/fleet/api.ts`

Typed wrappers over `supabase.rpc(...)` for `fleet_mint_pairing_code`, `fleet_devices`,
`fleet_revoke_device`. Types mirror `device.schema.json`.

**Action 1.5.2** — create `web/src/fleet/PairDeviceDialog.tsx`

Mints a code, renders it as `XXXX-XXXX` in the monospace face at display size, shows a live
countdown to `expires_at`, and a "Generar otro" button once expired. Copy-to-clipboard.

**Action 1.5.3** — create `web/src/fleet/DeviceList.tsx`

One row per device: alias, platform, free/capacity as a thin bar, last-seen as relative time, and a
reachability chip — `EN VIVO` when `live_url` is set and `last_seen_at` is under 2 minutes old,
`EN COLA` otherwise. Revoke behind a confirm.

**Definition of Done**

- [ ] A minted code pairs a real device end to end.
- [ ] The list reflects heartbeat data without a manual refresh (poll every 30 s).
- [ ] Revoking removes the row and the device's next call fails.

### Task 1.6 — Tests for user story 1

**File**: `app/src/test/kotlin/.../data/repository/FleetIdentityRepositoryTest.kt`

**Setup**: in-memory `DataStore<Preferences>` via `PreferenceDataStoreFactory.create` over a
`TemporaryFolder`; `repo = FleetIdentityRepositoryImpl(dataStore)`.

| Test | Verifies |
|---|---|
| `get returns unpaired identity when nothing stored` | Default `FleetIdentity()` with `paired == false` |
| `save then get round-trips every field` | Persistence of all five fields |
| `clear leaves identity unpaired` | `paired == false` after `clear()` |
| `malformed stored json falls back to default` | `fromJsonOrDefault` swallows the parse error. **Setup**: write `"{"` directly to the key |
| `identity flow emits on save` | Turbine: one emission per `save` |

**File**: `app/src/test/kotlin/.../services/fleet/FleetClientImplTest.kt`

**Setup**: Ktor `MockEngine` returning canned JSON; `client = FleetClientImpl(httpClient, identityRepo)`.

| Test | Verifies |
|---|---|
| `claimPairingCode posts to rpc endpoint with apikey header` | URL is `<panel>/rest/v1/rpc/fleet_claim_pairing_code`, `apikey` present |
| `claimPairingCode strips hyphens and uppercases the code` | Request body carries `ABCD1234` for input `abcd-1234` |
| `claimPairingCode maps 400 to a failure carrying the server message` | `Result.failure`, message from `msg` field |
| `claimPairingCode returns identity on success` | `deviceId`/`deviceToken` populated from the response |
| `heartbeat fails fast when unpaired` | No HTTP call is made. **Setup**: repo returns `FleetIdentity()` |
| `heartbeat sends platform ANDROID and app version` | Body fields match `BuildConfig.VERSION_NAME` |
| `failure message never contains the device token` | Assert the token substring is absent from `exceptionOrNull()?.message` |

**File**: `app/src/test/kotlin/.../ui/viewmodels/FleetSettingsViewModelTest.kt`

**Setup**: MockK `FleetClient` + `FleetIdentityRepository`; `MainDispatcherRule` as used by
`MainViewModelTest`.

| Test | Verifies |
|---|---|
| `pair saves identity on success` | `repository.save` called once with the returned identity |
| `pair surfaces error and does not save on failure` | `save` never called; `error` set |
| `unpair clears the repository` | `clear()` called |
| `busy is true while pairing and false after` | Turbine on the state flow |

**File**: `web/src/fleet/api.test.ts` (Vitest)

| Test | Verifies |
|---|---|
| `mintPairingCode returns code and expiry` | Shape mapping from the RPC payload |
| `mintPairingCode throws on rpc error` | Error propagation |
| `devices maps snake_case rpc payload to typed camelCase` | Field mapping |
| `devices returns empty array when rpc returns null` | Null from the RPC (not a viewer) becomes `[]`, never a crash |

**Definition of Done**

- [ ] All tests pass under `./gradlew :app:test` and `npm test --prefix web`.
- [ ] No test asserts on a hard-coded device token value that also appears in a log assertion.

---

## User story 2 — Hybrid transport: outbound job queue plus live tunnel

**Why**: a phone behind NAT, asleep, or on a dying quick tunnel cannot be dialled into on a schedule.
The queue makes autonomous work possible with no inbound reachability; the existing tunnel stays for
live, interactive work. The two are complementary, not alternatives, and the panel must show which
one is currently available.

**Acceptance criteria**

- [ ] The app claims and completes jobs with no inbound connectivity.
- [ ] Only non-destructive job kinds can be enqueued by this plan's code paths; a destructive kind
      without an approval reference is rejected by a database constraint, not by application code.
- [ ] A job claimed by a device that then dies is re-claimable after its lease expires.
- [ ] The panel shows `EN VIVO` only when the tunnel is actually reachable.
- [ ] Sync runs only on unmetered networks while charging, unless the user overrides it.

### Task 2.1 — Supabase schema `fleet` (jobs)

**Action 2.1.1** — create `supabase/migrations/0003_fleet_jobs.sql`

```sql
create schema if not exists fleet;
revoke all on schema fleet from anon, authenticated;

create type fleet.job_state as enum ('PENDING','CLAIMED','DONE','FAILED');

create table fleet.jobs (
  job_id       uuid primary key default gen_random_uuid(),
  device_id    uuid not null references device_registry.devices(device_id) on delete cascade,
  kind         text not null,
  params       jsonb not null default '{}'::jsonb,
  state        fleet.job_state not null default 'PENDING',
  destructive  boolean not null default false,
  approval_id  uuid,
  run_after    timestamptz not null default now(),
  lease_until  timestamptz,
  attempts     int not null default 0,
  result       jsonb,
  error        text,
  created_at   timestamptz not null default now(),
  updated_at   timestamptz not null default now(),
  -- The contract's approval rule, enforced by the database rather than by application code:
  -- a destructive job can never be runnable without an approval it points at.
  constraint destructive_requires_approval
    check (not destructive or approval_id is not null)
);
create index jobs_claimable_idx on fleet.jobs (device_id, state, run_after);
alter table fleet.jobs enable row level security;
```

**Action 2.1.2** — append: claim and complete (device-facing)

```sql
create or replace function public.fleet_claim_job(p_token text, p_lease_seconds int default 300)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid; v_job fleet.jobs%rowtype;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;

  -- A claim whose lease expired returns to the pool: a device that died mid-job never wedges it.
  update fleet.jobs set state = 'PENDING', lease_until = null
   where device_id = v_device and state = 'CLAIMED' and lease_until < now();

  select * into v_job from fleet.jobs
   where device_id = v_device and state = 'PENDING' and run_after <= now()
   order by run_after
   for update skip locked
   limit 1;
  if v_job.job_id is null then return null; end if;

  update fleet.jobs
     set state = 'CLAIMED', lease_until = now() + make_interval(secs => p_lease_seconds),
         attempts = attempts + 1, updated_at = now()
   where job_id = v_job.job_id;

  return jsonb_build_object('jobId', v_job.job_id, 'kind', v_job.kind, 'params', v_job.params);
end;
$$;
revoke all on function public.fleet_claim_job(text, int) from public;
grant execute on function public.fleet_claim_job(text, int) to anon, authenticated;

create or replace function public.fleet_complete_job(
  p_token text, p_job_id uuid, p_ok boolean, p_result jsonb, p_error text)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;
  update fleet.jobs
     set state = case when p_ok then 'DONE'::fleet.job_state else 'FAILED'::fleet.job_state end,
         result = p_result, error = p_error, lease_until = null, updated_at = now()
   where job_id = p_job_id and device_id = v_device and state = 'CLAIMED';
  return found;
end;
$$;
revoke all on function public.fleet_complete_job(text, uuid, boolean, jsonb, text) from public;
grant execute on function public.fleet_complete_job(text, uuid, boolean, jsonb, text) to anon, authenticated;
```

**Action 2.1.3** — append: enqueue (user-facing, non-destructive kinds only)

```sql
create or replace function public.fleet_enqueue_job(
  p_device_id uuid, p_kind text, p_params jsonb default '{}'::jsonb,
  p_run_after timestamptz default now())
returns uuid language plpgsql volatile security definer set search_path = '' as $$
declare v_email text; v_job uuid;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then raise exception 'not authorised' using errcode = '42501'; end if;
  if not exists (select 1 from device_registry.devices d
                  where d.device_id = p_device_id and d.owner_email = v_email
                    and d.revoked_at is null)
  then raise exception 'not authorised' using errcode = '42501'; end if;
  -- Destructive kinds are not reachable from this entry point at all. They arrive only through the
  -- approval path in user story 5, which sets destructive = true together with approval_id.
  if p_kind not in ('INVENTORY','EXT_HISTOGRAM','HASH_BATCH','STAT_PATH') then
    raise exception 'unsupported job kind' using errcode = '22023';
  end if;
  insert into fleet.jobs (device_id, kind, params, run_after)
  values (p_device_id, p_kind, p_params, p_run_after)
  returning job_id into v_job;
  return v_job;
end;
$$;
revoke all on function public.fleet_enqueue_job(uuid, text, jsonb, timestamptz) from public, anon;
grant execute on function public.fleet_enqueue_job(uuid, text, jsonb, timestamptz) to authenticated;
```

**Definition of Done**

- [ ] `fleet_claim_job` returns null when the queue is empty and never returns another device's job.
- [ ] An expired lease makes the job claimable again.
- [ ] `insert into fleet.jobs (destructive) values (true)` without `approval_id` is rejected by the
      constraint (verified directly against the database).
- [ ] `fleet_enqueue_job` rejects `MOVE_FILE` and every other unsupported kind.

### Task 2.2 — App: job kinds and the runner

**Action 2.2.1** — create `.../services/fleet/FleetJob.kt`

```kotlin
/**
 * Work the panel can ask this device to do without a live connection. Every kind here is read-only:
 * the queue in this release cannot mutate the device. Destructive work travels the approval path and
 * is not represented in this enum.
 */
enum class FleetJobKind { INVENTORY, EXT_HISTOGRAM, HASH_BATCH, STAT_PATH }

data class FleetJob(val jobId: String, val kind: FleetJobKind, val params: JsonObject)
```

An unrecognised kind from the server completes the job as `FAILED` with a clear error rather than
throwing — an older app must not wedge the queue of a newer panel.

**Action 2.2.2** — create `.../services/fleet/FleetJobRunner.kt` (interface) and `FleetJobRunnerImpl.kt`

`suspend fun run(job: FleetJob): Result<JsonObject>` dispatching on kind:

- `INVENTORY` → `StorageLocationProvider.listLocations()` + `FileOperationProvider.diskUsage(...)`
  per location, serialised as the aggregate payload of task 3.3.
- `EXT_HISTOGRAM` → existing extension-histogram walk for `params.locationId` / `params.path`.
- `HASH_BATCH` → task 2.4.
- `STAT_PATH` → `FileOperationProvider.statPath(...)`.

Every result is capped: a payload above 256 KB is rejected in-app with a `payload too large` error
rather than uploaded, so the manifest can never cross the cable by accident (contract §4).

**Action 2.2.3** — create `.../services/fleet/FleetSyncWorker.kt`

`CoroutineWorker` (WorkManager) — chosen over a foreground service because sync is periodic,
deferrable, and must survive process death, which is exactly WorkManager's contract and avoids a
second permanent notification next to `McpServerService`.

Loop: heartbeat → claim → run → complete, repeating until `fleet_claim_job` returns null or the
worker's 10-minute budget is spent. Constraints: `NetworkType.UNMETERED` and
`setRequiresCharging(true)` unless the user overrode them. Backoff: exponential from 30 s.

**Action 2.2.4** — modify `.../McpApplication.kt`: enqueue the unique periodic work
(`ExistingPeriodicWorkPolicy.KEEP`, 6-hour interval) when `FleetIdentity.paired` is true, and cancel
it on unpair.

**Action 2.2.5** — modify `app/build.gradle.kts`: add `androidx.work:work-runtime-ktx` and
`androidx.hilt:hilt-work` with `@HiltWorker`, pinned to the current stable versions, and register
the versions in `gradle/libs.versions.toml` following the existing catalogue style.

**Definition of Done**

- [ ] With the server stopped and no tunnel, a queued `INVENTORY` job completes end to end.
- [ ] An unknown job kind is reported as `FAILED` and the next job still runs.
- [ ] A payload over the cap fails the job instead of uploading.
- [ ] Unpairing cancels the periodic work.

### Task 2.3 — App: live reachability reporting

**Action 2.3.1** — modify `.../services/tunnel/TunnelManager.kt`: expose the current public URL as a
`StateFlow<String?>` if it is not already exposed in that shape.

**Action 2.3.2** — modify `.../services/fleet/FleetSyncWorker.kt`: pass the tunnel URL to
`fleet_device_heartbeat` as `p_live_url`, and pass `null` when no tunnel is up, so a stale URL is
cleared rather than left to mislead the panel.

**Definition of Done**

- [ ] Stopping the tunnel clears `live_url` on the next heartbeat.
- [ ] The panel's `EN VIVO` chip disappears within one heartbeat interval.

### Task 2.4 — App: incremental content hashing

**Why this is its own task**: `media_id = sha256(bytes)` is the contract's join key, and this device
holds 90 957 files across ~90 GB. Hashing all of it in one pass means reading 90 GB from flash — hours
of wall time and a flat battery. It must be incremental, resumable, budgeted, and ordered so the most
valuable rows land first.

**Action 2.4.1** — create `.../services/fleet/MediaHasher.kt` (interface) and `MediaHasherImpl.kt`

```kotlin
interface MediaHasher {
    /** Hashes up to [maxFiles] not-yet-hashed files under [locationId], or until [budgetMillis] is spent. */
    suspend fun hashBatch(locationId: String, path: String, maxFiles: Int, budgetMillis: Long): HashBatchResult
}

data class HashedFile(val path: String, val mediaId: String, val sizeBytes: Long, val lastModified: Long)
data class HashBatchResult(val hashed: List<HashedFile>, val exhausted: Boolean)
```

Streams each file through `MessageDigest.getInstance("SHA-256")` in 64 KB chunks — never loads a file
into memory. Skips files already carrying an unchanged `(size, lastModified)` fingerprint.

**Action 2.4.2** — create `.../data/repository/HashCacheRepository.kt` + `Impl`

Local record of `path → (mediaId, size, lastModified)` so a re-run hashes only what changed. Backed by
a DataStore-serialised map keyed by location id; a location's cache is dropped when its entry count
exceeds the configured ceiling, to bound the preference size.

**Action 2.4.3** — modify `.../services/fleet/FleetJobRunnerImpl.kt`: implement `HASH_BATCH` over
`MediaHasher`, defaulting to 500 files and a 4-minute budget per job, and returning `exhausted` so the
panel knows whether to enqueue another batch.

**Priority order for hashing, encoded as the default enqueue order in task 6.2**: `builtin:dcim`,
then `Android/media/com.whatsapp`, then `Android/media/com.whatsapp.w4b`, then
`primary:storage-0`, then the rest. Rationale from the contract: `SOLO_MOVIL` rows are the only
media with no backup anywhere, and these locations are where they live.

**Definition of Done**

- [ ] Hashing a known file produces the same digest as `sha256sum`.
- [ ] A second run over unchanged files performs no reads and returns `exhausted = true`.
- [ ] A 2 GB file hashes without an `OutOfMemoryError`.
- [ ] The batch honours both `maxFiles` and `budgetMillis`, whichever comes first.

### Task 2.5 — Tests for user story 2

**File**: `app/src/test/kotlin/.../services/fleet/FleetJobRunnerImplTest.kt`

**Setup**: MockK `StorageLocationProvider`, `FileOperationProvider`, `MediaHasher`;
`runner = FleetJobRunnerImpl(...)`.

| Test | Verifies |
|---|---|
| `INVENTORY returns one entry per location` | Aggregate shape from mocked locations |
| `EXT_HISTOGRAM passes locationId and path through` | Argument forwarding |
| `HASH_BATCH returns hashed entries and exhausted flag` | Result mapping |
| `STAT_PATH maps a missing path to a failure` | Error path |
| `unknown kind returns failure without throwing` | Queue is not wedged. **Setup**: construct `FleetJob` with a kind absent from the enum via the deserialiser |
| `payload above cap fails the job` | Size guard. **Setup**: mock a location list serialising over 256 KB |

**File**: `app/src/test/kotlin/.../services/fleet/MediaHasherImplTest.kt`

**Setup**: `TemporaryFolder` with real files; fake `FileOperationProvider` reading from disk.

| Test | Verifies |
|---|---|
| `hash of known content matches expected sha256` | Correctness against a precomputed digest of `"hello\n"` |
| `unchanged file is skipped on second pass` | Cache hit; zero reads |
| `changed lastModified forces a re-hash` | Cache invalidation |
| `maxFiles bounds the batch` | Returns exactly `maxFiles` entries, `exhausted = false` |
| `budget exhaustion stops the batch early` | **Setup**: `budgetMillis = 0` |
| `large file hashes in constant memory` | Streaming. **Setup**: 64 MB sparse file; assert digest, not memory |

**File**: `app/src/test/kotlin/.../services/fleet/FleetSyncWorkerTest.kt`

**Setup**: `TestListenableWorkerBuilder`; MockK `FleetClient`, `FleetJobRunner`.

| Test | Verifies |
|---|---|
| `worker returns success when queue is empty` | Null claim ends the loop |
| `worker completes a claimed job` | `completeJob(ok = true)` called with the runner's payload |
| `runner failure completes the job as failed` | `completeJob(ok = false)` with the error text |
| `worker retries on network failure` | `Result.retry()` |
| `unpaired device does no work` | No client calls |
| `heartbeat sends null live url when tunnel is down` | Stale URL cleared |

**Definition of Done**

- [ ] All tests pass.
- [ ] `MediaHasherImplTest` asserts a real digest value, not a mock.

---

## User story 3 — Ingest of engine aggregates, REVIEW queue and opportunities

**Why**: contract §4 fixes exactly what crosses the cable — aggregates (~5 KB), REVIEW rows (207
today), opportunities (~20). The manifest stays on the PC. This story builds the storage and the one
write path, and seeds the panel with the already-measured PC figures from contract §8 so the first
screen is populated without any new analysis.

**Acceptance criteria**

- [ ] Aggregates, REVIEW rows and opportunities are ingested through a single authenticated endpoint.
- [ ] `media` is keyed by `media_id`; a path is never part of a primary key.
- [ ] `safe_to_purge_staging` and `safe_to_release_mobile` are stored as received and never derived.
- [ ] The panel can answer the four cross-device states with one query.
- [ ] `opportunity.schema.json` exists and is versioned with the plan.

### Task 3.1 — Supabase schema `fleet` (media and engine feeds)

**Action 3.1.1** — create `supabase/migrations/0004_fleet_engine_feeds.sql`

```sql
create table fleet.media (
  media_id      text primary key,            -- sha256 hex of the bytes; the only identity
  media_type    text check (media_type in ('image','video','document','other')),
  size_bytes    bigint,
  derived_from  text references fleet.media(media_id),
  first_seen_at timestamptz not null default now()
);

create table fleet.device_media (
  media_id   text not null references fleet.media(media_id) on delete cascade,
  device_id  uuid not null references device_registry.devices(device_id) on delete cascade,
  path       text not null,                  -- an attribute of the pairing, never an identity
  observed_at timestamptz not null default now(),
  primary key (media_id, device_id, path)
);
create index device_media_device_idx on fleet.device_media (device_id);

create table fleet.aggregates (
  run_id     uuid not null,
  device_id  uuid references device_registry.devices(device_id) on delete cascade,
  unit       text, site text, year int, media_type text, state text,
  file_count bigint not null,
  total_bytes bigint not null,
  captured_at timestamptz not null default now()
);
create index aggregates_run_idx on fleet.aggregates (run_id);

create table fleet.review_queue (
  run_id        uuid not null,
  media_id      text not null,
  device_id     uuid references device_registry.devices(device_id) on delete set null,
  state         text not null,
  review_reason text not null check (review_reason in
    ('NO_MATCH','LOW_CONFIDENCE','CONFLICT','CORRUPT','AMBIGUOUS_SITE',
     'NO_SPACE','CONVERSION_FAILED','MISSING_SOURCE')),
  confidence    numeric,
  explanation   text,
  evidence      text[],
  thumb_data_uri text,                       -- <= 240px per contract §4; nullable
  primary key (run_id, media_id)
);

create table fleet.verifications (
  media_id               text primary key references fleet.media(media_id) on delete cascade,
  verdict                text not null check (verdict in ('PASS','FAIL')),
  checks                 jsonb not null default '{}'::jsonb,
  -- Written by the engine. Never computed here. See contract §5.
  safe_to_purge_staging  boolean not null default false,
  safe_to_release_mobile boolean not null default false,
  verified_at            timestamptz not null default now()
);

alter table fleet.media enable row level security;
alter table fleet.device_media enable row level security;
alter table fleet.aggregates enable row level security;
alter table fleet.review_queue enable row level security;
alter table fleet.verifications enable row level security;
```

**Action 3.1.2** — append: the cross-device state view, derived purely by JOIN on `media_id`

```sql
create or replace view fleet.cross_device_state as
select m.media_id,
       bool_or(d.platform = 'PC')     as on_pc,
       bool_or(d.platform <> 'PC')    as on_mobile,
       case
         when bool_or(d.platform <> 'PC') and not bool_or(d.platform = 'PC') then 'SOLO_MOVIL'
         when bool_or(d.platform = 'PC') and not bool_or(d.platform <> 'PC') then 'SOLO_PC'
         else 'AMBOS_IGUAL'
       end as state
from fleet.media m
join fleet.device_media dm on dm.media_id = m.media_id
join device_registry.devices d on d.device_id = dm.device_id
group by m.media_id;
```

`AMBOS_DISTINTO` is deliberately **not** derivable here: by definition it is the same *name* with
different `media_id`s, so it cannot come from a `media_id` group. It is emitted by the engine as a
REVIEW row with `review_reason = 'CONFLICT'` and read from `fleet.review_queue`.

**Definition of Done**

- [ ] Migration applies cleanly.
- [ ] Inserting the same `media_id` from two devices yields exactly one `fleet.media` row.
- [ ] `cross_device_state` returns `SOLO_MOVIL` for a mobile-only hash and `SOLO_PC` for a PC-only one.
- [ ] No column in this migration is computed from another table's `safe_to_*` values.

### Task 3.2 — `opportunity.schema.json` and its table

**Action 3.2.1** — create `docs/schemas/opportunity.schema.json`

Required: `type` (enum `HEVC_DRONE|VIDEO_CONVERT|REDOWNLOADABLE|LEGACY_ARCHIVE|DUPLICATE_GROUP|MOBILE_ONLY_BACKUP|APK_BACKUP`),
`device_id`, `gb_recoverable` (number ≥ 0), `risk` (enum `none|low|decision`), `status`
(enum `DETECTED|PROPOSED|APPROVED|EXECUTED|DISMISSED`), `detected_at`. Optional: `media_ids` (array)
**or** `filter` (object) — exactly one, enforced with `oneOf`; `executed_run_id`; `title`; `detail`.

**Action 3.2.2** — append to `0004_fleet_engine_feeds.sql`

```sql
create table fleet.opportunities (
  opportunity_id  uuid primary key default gen_random_uuid(),
  device_id       uuid references device_registry.devices(device_id) on delete cascade,
  type            text not null,
  title           text not null,
  detail          text,
  gb_recoverable  numeric not null check (gb_recoverable >= 0),
  risk            text not null check (risk in ('none','low','decision')),
  status          text not null default 'DETECTED'
                  check (status in ('DETECTED','PROPOSED','APPROVED','EXECUTED','DISMISSED')),
  media_ids       text[],
  filter          jsonb,
  detected_at     timestamptz not null default now(),
  executed_run_id uuid,
  constraint media_ids_xor_filter check ((media_ids is null) <> (filter is null))
);
alter table fleet.opportunities enable row level security;
```

**Definition of Done**

- [ ] Schema and table agree field for field.
- [ ] A row carrying both `media_ids` and `filter` is rejected, as is one carrying neither.

### Task 3.3 — Ingest endpoint

**Action 3.3.1** — append: `public.fleet_ingest(p_payload jsonb)`

`security definer`, granted to `authenticated` only, resolving the caller through
`device_registry.current_viewer_email()`. Accepts `{run_id, device_id, aggregates[], review[],
opportunities[], verifications[], media[]}`. Behaviour:

- Rejects a payload above 2 MB with a clear error — the manifest cannot be smuggled through it.
- Rejects `review[]` longer than 5 000 rows and `aggregates[]` longer than 2 000 rows.
- Upserts `media` on `media_id`, `verifications` on `media_id`, replaces `aggregates` and
  `review_queue` for the given `run_id`.
- Copies `safe_to_purge_staging` / `safe_to_release_mobile` verbatim; there is no expression in this
  function that derives either value.

**Action 3.3.2** — create `web/scripts/ingest.mjs`

Reads an engine export file, validates it against `docs/schemas/*.json` with `ajv`, and calls
`fleet_ingest` with a user session (not the service-role key). Refuses to run if the payload contains
a `manifest` key, naming contract §4 in the error.

**Action 3.3.3** — create `docs/seeds/pc_baseline_20260909.json`

The already-measured PC figures from contract §8, as one `fleet_ingest` payload: 112 554 files,
504 videos / 55.58 GB, 3 231 EXIF images (553 with GPS, 3 151 with a date), 1 925 documents (117
OCR'd), 54 duplicate groups, 207 REVIEW rows, 62.95 GB already released; and the four opportunities
(drone HEVC 9.74 GB / low, video conversion 85 files 14.30 GB / low, redownloadable software ~34 GB /
low, legacy archives ~63 GB / decision).

**Definition of Done**

- [ ] `fleet_ingest` rejects an oversized payload and a payload carrying a `manifest` key.
- [ ] Seeding produces a populated panel with the contract §8 figures and no analysis step.
- [ ] Re-running the same seed is idempotent (same `run_id` replaces, never duplicates).

### Task 3.4 — Tests for user story 3

**File**: `web/src/fleet/ingest.test.ts` (Vitest)

| Test | Verifies |
|---|---|
| `rejects a payload carrying a manifest key` | Contract §4 guard |
| `rejects a payload over the size cap` | Size guard |
| `validates the seed file against the schemas` | `docs/seeds/pc_baseline_20260909.json` passes ajv |
| `opportunity with both media_ids and filter fails validation` | `oneOf` enforcement |
| `opportunity with neither fails validation` | `oneOf` enforcement |
| `device object with unknown platform fails validation` | `device.schema.json` enum |

**File**: `web/src/fleet/crossDevice.test.ts`

| Test | Verifies |
|---|---|
| `SOLO_MOVIL when hash exists only on a mobile device` | State derivation |
| `SOLO_PC when hash exists only on PC` | State derivation |
| `AMBOS_IGUAL when the same hash is on both` | State derivation |
| `AMBOS_DISTINTO is read from review rows, never derived` | Asserts the deriver has no branch producing it |

**Definition of Done**

- [ ] All tests pass.
- [ ] A grep for `safe_to_release_mobile` in `web/src/` finds only reads and renders, never an assignment.

---

## User story 4 — The panel as a system with context

**Why**: the panel's job is to make the fleet legible at a glance and to carry the business meaning the
engine deliberately does not have. It is scanned and operated, not read.

**Acceptance criteria**

- [ ] A device switcher plus a fleet-wide overview; the panel never assumes a single device.
- [ ] Cross-device state is shown per media with its four states and their default actions.
- [ ] The REVIEW queue is filterable by `review_reason`.
- [ ] Opportunities are ranked by recoverable GB with their risk shown, never hidden.
- [ ] `REMUX` and `RECODE` are visually distinct everywhere they appear.
- [ ] `confidence: 0.0` renders as "no sé" with its reason, never as a 0 % bar.

### Task 4.1 — Panel shell and device switcher

**Action 4.1.1** — create `web/src/shell/AppShell.tsx`: persistent left rail (Resumen, Dispositivos,
Revisión, Oportunidades, Aprobaciones, Programación), device switcher in the header with an
"Toda la flota" option, and the signed-in identity with sign-out.

**Action 4.1.2** — rewrite `web/src/App.tsx` to mount `AppShell` inside the existing Access state
machine, preserving the `loading | anonymous | denied | granted` states unchanged.

**Action 4.1.3** — create `web/src/styles/tokens.css`: the palette already committed in the Android
app (`#0A0F0C` ground, `#161E1A` panel, `#00E676` accent) as CSS custom properties, plus the status
ramp (`none` / `low` / `decision`) kept separate from the accent.

**Definition of Done**

- [ ] Switching device re-scopes every screen without a full reload.
- [ ] The shell renders correctly at 360 px width.
- [ ] No horizontal page scroll at any breakpoint; wide tables scroll inside their own container.

### Task 4.2 — Overview, review queue and opportunities screens

**Action 4.2.1** — create `web/src/screens/OverviewScreen.tsx`: per-device capacity and free space,
last sync, reachability chip, and the fleet totals. Cross-device summary counts for the four states,
with `SOLO_MOVIL` given first position — it is the only class with no copy anywhere.

**Action 4.2.2** — create `web/src/screens/ReviewScreen.tsx`: one row per REVIEW item with a reason
chip, `confidence` rendered as "no sé" when 0.0 and as a value otherwise, `explanation`, `evidence`
badges, and the thumbnail when present. Filter row above the table.

**Action 4.2.3** — create `web/src/screens/OpportunitiesScreen.tsx`: ranked by `gb_recoverable`, risk
chip (`none` / `low` / `decision`), and a "Proponer" action that creates a proposal (user story 5).
`decision` rows carry an explicit note that they need a human call, never a one-click action.

**Action 4.2.4** — create `web/src/components/ActionLabel.tsx`: maps
`COPY|MOVE|REMUX|REMUX_AUDIO|RECODE|SKIP|DEDUP_DELETE|REVIEW` to distinct labels and colours.
`REMUX` reads "Recontenedor (sin recodificar)" and `RECODE` reads "Recodificar (re-encoda el vídeo)";
they never share a colour or an icon.

**Definition of Done**

- [ ] The seeded data renders on all three screens with no empty states.
- [ ] A `confidence` of 0.0 never renders as a progress bar.
- [ ] `REMUX` and `RECODE` are distinguishable without reading the text (colour plus shape).

### Task 4.3 — Tests for user story 4

**File**: `web/src/components/ActionLabel.test.tsx`

| Test | Verifies |
|---|---|
| `REMUX and RECODE render different labels` | Contract §5 |
| `REMUX and RECODE render different colours` | Not colour-only equal |
| `every plan action has a label` | Exhaustive map; fails when a new action is added without a label |

**File**: `web/src/screens/ReviewScreen.test.tsx`

| Test | Verifies |
|---|---|
| `confidence 0.0 renders "no sé" with its reason` | Contract §2 |
| `confidence 0.72 renders the value` | Normal path |
| `filter by review_reason narrows the rows` | Filtering |
| `missing thumbnail renders without layout shift` | Nullable `thumb_data_uri` |

**Definition of Done**

- [ ] All tests pass; no snapshot tests standing in for behavioural assertions.

---

## User story 5 — The APPROVE inbox

**Why**: the contract makes APPROVE manual and binds it to `plan_hash`. This is the one screen where a
destructive action becomes runnable, so the binding must be enforced in the database rather than in
the UI.

**Acceptance criteria**

- [ ] Approving records the exact `plan_hash` approved, with who and when.
- [ ] A job carrying a different `plan_hash` than its approval is rejected server-side.
- [ ] Nothing destructive can reach `PENDING` without an approval row.
- [ ] An approval can be withdrawn while the job is still `PENDING`.

### Task 5.1 — Approvals schema

**Action 5.1.1** — create `supabase/migrations/0005_fleet_approvals.sql`

```sql
create table fleet.approvals (
  approval_id  uuid primary key default gen_random_uuid(),
  device_id    uuid not null references device_registry.devices(device_id) on delete cascade,
  plan_hash    text not null,
  summary      jsonb not null,
  approved_by  text not null references device_storage.viewers(email),
  approved_at  timestamptz not null default now(),
  withdrawn_at timestamptz
);
alter table fleet.approvals enable row level security;
alter table fleet.jobs add column plan_hash text;
alter table fleet.jobs add constraint destructive_carries_plan_hash
  check (not destructive or plan_hash is not null);
```

**Action 5.1.2** — append: `public.fleet_approve_plan(p_device_id uuid, p_plan_hash text,
p_summary jsonb, p_kind text, p_params jsonb)`

Creates the approval and the matching job in one transaction with `destructive = true`,
`approval_id`, and `plan_hash` copied from the approval. Rejects a caller who is not a viewer, or a
device not owned by them.

**Action 5.1.3** — append: `public.fleet_withdraw_approval(p_approval_id uuid)`

Sets `withdrawn_at` and deletes the linked job only while it is still `PENDING`; a `CLAIMED` job
cannot be withdrawn and the function says so rather than failing silently.

**Action 5.1.4** — modify `public.fleet_claim_job`: refuse to hand out a job whose `approval_id`
points at a withdrawn approval, or whose `plan_hash` differs from the approval's.

**Definition of Done**

- [ ] A job whose `plan_hash` was tampered with is never claimed.
- [ ] Withdrawing a `PENDING` approval removes the job; withdrawing a `CLAIMED` one reports why not.
- [ ] `fleet_approve_plan` is the only path that can set `destructive = true`.

### Task 5.2 — Approval screen

**Action 5.2.1** — create `web/src/screens/ApprovalsScreen.tsx`

One card per pending proposal: device, action counts by type (using `ActionLabel`), total bytes
affected, `plan_hash` shown in full in the monospace face, and the list of affected media with their
cross-device state. Approve requires the plan hash to be visible on screen at the moment of approval.

For any proposal touching media whose `safe_to_release_mobile` is false, the approve control is
disabled with the reason stated — the panel reads that flag and refuses; it never overrides it.

**Definition of Done**

- [ ] Approving posts the displayed `plan_hash` verbatim.
- [ ] A proposal containing one unsafe medium cannot be approved.
- [ ] Withdrawal is available and reflected without a reload.

### Task 5.3 — Tests for user story 5

**File**: `web/src/screens/ApprovalsScreen.test.tsx`

| Test | Verifies |
|---|---|
| `approve sends the displayed plan hash` | Hash binding |
| `approve is disabled when any medium is not safe_to_release_mobile` | Contract §5 |
| `withdraw is offered for pending and hidden for claimed` | State handling |
| `action counts use distinct REMUX and RECODE labels` | Contract §5 |

**Definition of Done**

- [ ] All tests pass.

---

## User story 6 — Scheduler that proposes, never executes

**Why**: the user's decision is explicit — the scheduler produces proposals for the APPROVE inbox and
never executes. Combined with the database constraints above, this makes autonomous destruction
structurally impossible rather than merely discouraged.

**Acceptance criteria**

- [ ] A schedule produces a proposal, never a `destructive` job.
- [ ] Schedules are per device and per job kind, with a cron-like cadence.
- [ ] A schedule for a revoked device stops firing.
- [ ] The panel shows the next run and the last outcome for each schedule.

### Task 6.1 — Schedules schema and runner

**Action 6.1.1** — create `supabase/migrations/0006_fleet_schedules.sql`

```sql
create table fleet.schedules (
  schedule_id  uuid primary key default gen_random_uuid(),
  device_id    uuid not null references device_registry.devices(device_id) on delete cascade,
  kind         text not null check (kind in ('INVENTORY','EXT_HISTOGRAM','HASH_BATCH')),
  params       jsonb not null default '{}'::jsonb,
  cadence      text not null check (cadence in ('HOURLY','DAILY','WEEKLY')),
  enabled      boolean not null default true,
  next_run_at  timestamptz not null default now(),
  last_run_at  timestamptz,
  last_outcome text,
  created_by   text not null references device_storage.viewers(email),
  created_at   timestamptz not null default now()
);
alter table fleet.schedules enable row level security;
```

The `kind` check excludes every destructive kind at the schema level: a schedule cannot name one.

**Action 6.1.2** — append: `public.fleet_due_schedules()` and `public.fleet_fire_schedule(uuid)`,
the latter enqueuing through the same non-destructive path as `fleet_enqueue_job` and advancing
`next_run_at` by the cadence.

**Action 6.1.3** — create `web/scripts/tick-schedules.mjs`, invoked by a Netlify scheduled function
or an external cron, which calls `fleet_due_schedules` and fires each.

**Definition of Done**

- [ ] A schedule naming a destructive kind is rejected by the check constraint.
- [ ] Firing advances `next_run_at` and records the outcome.
- [ ] A revoked device's schedules stop firing.

### Task 6.2 — Schedule screen and the default hashing programme

**Action 6.2.1** — create `web/src/screens/ScheduleScreen.tsx`: list, create, enable/disable, and
next-run display.

**Action 6.2.2** — create `web/src/fleet/defaultProgramme.ts`: the first-run programme for a newly
paired Android device — a daily `INVENTORY`, then `HASH_BATCH` schedules in the priority order fixed
in task 2.4 (DCIM, WhatsApp, WhatsApp Business, storage-0, remainder), so `SOLO_MOVIL` rows surface
first.

**Definition of Done**

- [ ] Pairing a device offers the default programme; declining leaves no schedules.
- [ ] The programme's order matches task 2.4 exactly.

### Task 6.3 — Tests for user story 6

**File**: `web/src/fleet/defaultProgramme.test.ts`

| Test | Verifies |
|---|---|
| `programme orders hashing DCIM first` | Priority order |
| `programme contains no destructive kinds` | Safety |
| `programme is empty for a PC device` | Android-only |

**Definition of Done**

- [ ] All tests pass.

---

## Closing tasks

### Task 7.1 — Documentation

**Action 7.1.1** — modify `docs/ARCHITECTURE.md`: add a Mermaid component diagram for the fleet
transport (device → queue → panel, and device ← tunnel ← panel), validated with `mmdc`.

**Action 7.1.2** — modify `docs/PROJECT.md`: add the fleet section — schemas, job kinds, the
approval binding, and the invariant that the panel never computes `safe_to_*`.

**Definition of Done**

- [ ] Every Mermaid diagram validated with `mmdc`; no ASCII art.

### Task 7.2 — Quality gates

Run only after every user story above is implemented.

- [ ] `make lint` clean (ktlint and detekt), with no new suppressions.
- [ ] `./gradlew build` succeeds with no warnings.
- [ ] `./gradlew :app:test` green.
- [ ] `npm test --prefix web` green; `npm run build --prefix web` succeeds.
- [ ] `code-reviewer` subagent in plan compliance mode reports no issues; re-run until clean.

---

## Open questions for the user — MUST be resolved before Task 3.3

1. **Engine → panel authentication.** `fleet_ingest` is written for an authenticated viewer session.
   The engine runs unattended on the PC. Options: (a) pair the PC as a device and give it a device
   token, reusing user story 1 unchanged; (b) a dedicated ingest token. (a) is recommended — it makes
   the PC a first-class device, which the cross-device JOIN needs anyway. **Not implemented until
   answered.**
2. **Thumbnails.** Contract §4 caps them at 240 px as `data:` URIs for visible rows. At 207 REVIEW
   rows this is roughly 2–4 MB per run, which exceeds the 2 MB ingest cap in task 3.3. Options:
   raise the cap, ingest thumbnails in a second call, or fetch them on demand per `media_id`.
   Recommended: on demand, keeping the ingest payload small.
3. **`docs/seeds/pc_baseline_20260909.json`.** Contract §8 gives totals but not the 207 REVIEW rows
   or the per-`media_id` detail. Confirm whether the engine exports that file, or whether the seed
   should carry aggregates and opportunities only, with REVIEW arriving on the engine's first run.
