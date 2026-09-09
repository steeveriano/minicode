# Visor de almacenamiento

Página que muestra qué ocupa el almacenamiento del teléfono, medido a través del servidor MCP de la
app Android de este mismo repositorio.

## Por qué vive acá

Lo que produce la captura y lo que consume la página son **el mismo contrato** (`src/snapshot.ts` es
un espejo de `DiskUsageResult` en el lado Android). En un solo repositorio no pueden
desincronizarse en silencio: un campo renombrado en el dispositivo rompe el `tsc` de acá.

## Configuración

Ninguna. `.env.production` está commiteado y el build lo toma solo.

Esas dos credenciales **no son secretas**: identifican al proyecto, no a una persona, y Vite las
inlinea en el JavaScript, así que son legibles por cualquiera que abra el sitio, estén en el repo o
no. Lo que protege los datos no es esa clave —es `device_storage.viewers`, verificada en el
servidor en cada pedido.

La clave de servicio de Supabase es harina de otro costal: saltea row level security y **nunca** se
commitea. Vive solo en el entorno de quien corre la captura.

Para desarrollo local con otro proyecto: `cp .env.example .env` (ignorado por git) o variables de
entorno en Netlify — cualquiera de las dos le gana a `.env.production`.

## Cómo llega la página al teléfono

Por una función, nunca directo.

```
navegador  →  /api/device/:slug/…  →  túnel  →  celular
   (sesión de Supabase)   (verifica el padrón,
                           agrega el bearer)
```

El motivo es que **un sitio estático no puede guardar un secreto**: lo que esté en el bundle o en el
almacenamiento del navegador pertenece a quien tenga el navegador. Autenticar la página decide
quién entra, no qué se filtra una vez adentro. Así que el token del dispositivo vive solo en el
entorno de la función y el navegador habla únicamente con su propio origen.

Eso también resuelve el `Content-Security-Policy`. Si el navegador llamara al túnel, `connect-src`
tendría que nombrar cada hostname y cambiar cada vez que uno cambiara. Nombra dos orígenes —este
sitio y Supabase— y los dispositivos están deliberadamente ausentes.

Y revocar a alguien en `device_storage.viewers` le corta el acceso **al teléfono**, no solo a los
datos guardados: la función pregunta quién llama antes de reenviar nada.

### Solo siete herramientas

El dispositivo expone cincuenta, y una docena escribe, mueve, borra, pone en cuarentena o maneja la
pantalla. La función reenvía siete, por nombre: listar ubicaciones, listar archivos, uso de disco,
leer archivo, listar apps, listar cuarentenas y estado de pantalla.

Lista blanca y no lista negra, a propósito: un dispositivo que gane una herramienta destructiva no
debe volverse alcanzable por ella solo porque nadie se acordó de prohibirla. Los lotes se revisan
mensaje por mensaje, que es justo donde una llamada bloqueada viajaría al lado de una permitida.

### Agregar un dispositivo

Dos variables de entorno y una fila. Sin cambios de código.

```
DEVICE_<SLUG>_URL     https://…            (el túnel, con https)
DEVICE_<SLUG>_TOKEN   …                    (el bearer del dispositivo, marcado como secreto)
```

```sql
insert into fleet.devices (slug, alias, platform) values ('pc', 'PC de escritorio', 'PC');
```

La función además necesita `SUPABASE_URL` y `SUPABASE_PUBLISHABLE_KEY` para verificar la sesión.
Ninguna credencial vive en la base ni en el bundle.

> **Al cargarlas por la API de Netlify:** pasar `scopes` específicos (`functions`, `builds`)
> devuelve «upserted» y **no guarda nada**. Hay que usar `all`. Verificá siempre con
> `getAllEnvVars` después de escribir; el silencio no es confirmación.

### El visor histórico sigue

La vista de instantáneas consulta Supabase y funciona con el celular apagado. Las dos conviven: en
vivo cuando el dispositivo responde, último censo guardado cuando no.

## Quién puede entrar

El padrón de usuarios de ese proyecto de Supabase pertenece a otra aplicación, así que **poder
iniciar sesión no es permiso para estar acá**. Lo decide `device_storage.viewers`, una lista de
correos autorizados que el servidor consulta en cada pedido. Un usuario autenticado pero no listado
recibe `null`, no un inventario vacío — la página distingue las dos cosas.

Autorizar a alguien:

```sql
insert into device_storage.viewers (email, note) values ('persona@ejemplo.com', 'quién es');
```

## La captura

Paso aparte, desde una máquina de confianza: tiene las dos credenciales que nunca deben llegar a un
navegador — el token del dispositivo (control total del celular) y la clave de servicio de Supabase
(saltea row level security).

```sh
MCP_URL=https://<tunel>.trycloudflare.com/mcp \
MCP_TOKEN=<token del celular> \
SUPABASE_URL=https://<proyecto>.supabase.co \
SUPABASE_SERVICE_ROLE_KEY=<clave de servicio> \
npm run capture
```

Inserta una instantánea nueva; la página siempre muestra la última. Para una corrida en seco sin
tocar la base: `SNAPSHOT_OUT=tmp/snapshot.json`.

## Las tablas

`device_storage` está **fuera** de la API REST a propósito. La única puerta es la función
`public.device_storage_latest_snapshot()`, que verifica la lista antes de devolver nada.

| tabla | qué guarda |
|---|---|
| `snapshots` | una fila por captura |
| `locations` | ubicaciones autorizadas en esa captura, con su nivel de acceso |
| `usage_nodes` | el árbol aplanado — es lo que permite comparar capturas y medir crecimiento |
| `viewers` | correos autorizados |

## Comandos

| | |
|---|---|
| `npm run dev` | servidor de desarrollo |
| `npm run build` | build de producción a `dist/` |
| `npm run lint` | chequeo de tipos |
| `npm test` | tests del parser |
| `npm run capture` | mide el teléfono y guarda una instantánea en Supabase |

## Lo que falta

El navegador de archivos sobre la función: árbol real del dispositivo, estado de cada equipo, y los
hallazgos del censo como pantallas en vez de un informe. El backend ya está; falta la interfaz.

La cola de trabajos saliente —que el dispositivo pregunte si hay tarea pendiente en vez de que lo
llamen— sigue pendiente y es lo que permitirá programar sin túnel activo. No hace falta para
navegar.
