import { Family } from "./family.js";
import { verifyInitData } from "./telegram.js";
import { webhookSecret } from "./crypto.js";
import APP_HTML from "./app.html";

export { Family };

const family = (env) => env.FAMILY.get(env.FAMILY.idFromName("family"));

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    const path = url.pathname;

    // Telegram webhook
    if (path === "/tg" && req.method === "POST") {
      const secret = await webhookSecret(env.TELEGRAM_BOT_TOKEN);
      if (req.headers.get("X-Telegram-Bot-Api-Secret-Token") !== secret) return new Response("forbidden", { status: 403 });
      const update = await req.json();
      ctx.waitUntil(family(env).onUpdate(update));
      return new Response("ok");
    }

    // Mini App page
    if (path === "/app" || path === "/app/") {
      return new Response(APP_HTML, {
        headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store" },
      });
    }

    // Mini App API (authenticated with Telegram init data)
    if (path.startsWith("/api/")) {
      const user = await verifyInitData(req.headers.get("X-Init-Data"), env.TELEGRAM_BOT_TOKEN);
      if (!user) return json({ ok: false, error: "auth" }, 401);
      if (path === "/api/state" && req.method === "GET") return json(await family(env).apiState(user));
      if (path === "/api/meds" && req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        return json(await family(env).apiSaveMeds(user, body.device, body.meds));
      }
      return json({ ok: false, error: "not found" }, 404);
    }

    // Admin endpoints used by the deploy workflow (secret = derived from the bot token)
    if (path === "/setup" || path === "/tick") {
      const secret = await webhookSecret(env.TELEGRAM_BOT_TOKEN);
      if (req.headers.get("X-Setup") !== secret) return new Response("forbidden", { status: 403 });
      return json(path === "/setup" ? await family(env).setup(url.origin) : await family(env).tick());
    }

    return new Response("Daroyar bot is running ✓", { headers: { "content-type": "text/plain; charset=utf-8" } });
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(family(env).tick());
  },
};
