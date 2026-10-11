// Omni Message — референсный бэкенд раздачи бесплатных MTProto-прокси.
// Supabase Edge Function (Deno, ноль зависимостей), по мотивам серверной части
// NAUA Security Mirage (supabase/functions/france-config): те же три эндпоинта,
// только вместо VLESS-ключа отдаём MTProto-прокси.
//
// Deploy:
//   supabase functions deploy omni-proxy --no-verify-jwt
//   supabase secrets set PROXY_LIST_JSON='[{"host":"...","port":443,"secret":"ee..."}]' REQUIRE_TOKEN=0
//
// Env:
//   PROXY_LIST_JSON - JSON-массив [{host,port,secret}] (обязателен)
//   REQUIRE_TOKEN   - "1" = проверять token из /auth/register, иначе open mode
//   SHARED_SECRET   - секрет подписи токенов (при REQUIRE_TOKEN=1)
//
// Контракт полностью: docs/PROXY_SERVER.md

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, X-Anonymous-Key",
  "Content-Type": "application/json",
};

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: cors });
}

// Простая подпись без зависимостей: base64url(deviceId + "." + sha256hex(deviceId + secret)).
// Deno: crypto.subtle доступен глобально.
async function sha256hex(s: string): Promise<string> {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function makeToken(deviceId: string, secret: string): Promise<string> {
  const sig = await sha256hex(deviceId + "." + secret);
  return btoa(deviceId + "." + sig).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function checkToken(token: string, secret: string): Promise<string | null> {
  try {
    const raw = atob(token.replace(/-/g, "+").replace(/_/g, "/"));
    const i = raw.lastIndexOf(".");
    if (i < 0) return null;
    const deviceId = raw.slice(0, i);
    const sig = raw.slice(i + 1);
    if ((await sha256hex(deviceId + "." + secret)) !== sig) return null;
    return deviceId;
  } catch {
    return null;
  }
}

function proxyList(): Array<{ host: string; port: number; secret: string }> {
  try {
    const raw = Deno.env.get("PROXY_LIST_JSON") ?? "[]";
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr.filter(
      (p) =>
        p && typeof p.host === "string" && p.host.length > 0 &&
        typeof p.port === "number" && p.port > 0 && p.port < 65536 &&
        typeof p.secret === "string" && p.secret.length >= 32,
    );
  } catch {
    return [];
  }
}

Deno.serve(async (req: Request): Promise<Response> => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "");
  const requireToken = Deno.env.get("REQUIRE_TOKEN") === "1";
  const secret = Deno.env.get("SHARED_SECRET") ?? "";

  // GET .../client-api/v1/download-anonymous-key (паттерн Mirage; у нас ключей нет — пусто)
  if (req.method === "GET" && path.endsWith("/client-api/v1/download-anonymous-key")) {
    return json({ key: "" });
  }

  // POST .../auth/register {deviceId, app, v} (паттерн Mirage: registerOrAuth)
  if (req.method === "POST" && path.endsWith("/auth/register")) {
    let deviceId = "";
    try {
      deviceId = (await req.json()).deviceId ?? "";
    } catch { /* ignore */ }
    if (!deviceId) return json({ error: "deviceId required" }, 400);
    if (!requireToken || !secret) return json({ token: "" });
    return json({ token: await makeToken(deviceId, secret) });
  }

  // POST .../proxy/config {deviceId, token}
  if (req.method === "POST" && path.endsWith("/proxy/config")) {
    let body: { deviceId?: string; token?: string } = {};
    try {
      body = await req.json();
    } catch { /* ignore */ }
    if (!body.deviceId) return json({ error: "deviceId required" }, 400);
    if (requireToken && secret) {
      const bound = await checkToken(body.token ?? "", secret);
      if (bound === null || bound !== body.deviceId) {
        return json({ error: "bad token" }, 403);
      }
    }
    return json({ proxies: proxyList() });
  }

  return json({ error: "not found" }, 404);
});
