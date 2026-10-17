import { hmac, hex } from "./crypto.js";

export class Bot {
  /** base: overridable only for local tests (a fake Telegram). */
  constructor(token, base = "https://api.telegram.org") {
    this.token = token;
    this.base = base;
  }

  async call(method, params = {}) {
    const res = await fetch(`${this.base}/bot${this.token}/${method}`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(params),
    });
    let data;
    try {
      data = await res.json();
    } catch {
      data = { ok: false, description: `HTTP ${res.status}` };
    }
    return data;
  }

  send(chatId, text, extra = {}) {
    return this.call("sendMessage", {
      chat_id: chatId,
      text,
      parse_mode: "HTML",
      disable_web_page_preview: true,
      ...extra,
    });
  }

  async isMember(chatId, userId) {
    const r = await this.call("getChatMember", { chat_id: chatId, user_id: userId });
    if (!r.ok) return false;
    const s = r.result.status;
    return s === "creator" || s === "administrator" || s === "member" || (s === "restricted" && r.result.is_member);
  }
}

/**
 * Validates Mini App init data (https://core.telegram.org/bots/webapps).
 * Returns the Telegram user object, or null.
 */
export async function verifyInitData(initData, botToken, maxAgeSec = 24 * 3600) {
  if (!initData) return null;
  const params = new URLSearchParams(initData);
  const hash = params.get("hash");
  if (!hash) return null;
  params.delete("hash");
  const check = [...params.entries()]
    .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
    .map(([k, v]) => `${k}=${v}`)
    .join("\n");
  const secret = await hmac(new TextEncoder().encode("WebAppData"), botToken);
  const sig = hex(await hmac(secret, check));
  if (sig !== hash) return null;
  const authDate = Number(params.get("auth_date") || 0);
  if (!authDate || Date.now() / 1000 - authDate > maxAgeSec) return null;
  try {
    return JSON.parse(params.get("user") || "null");
  } catch {
    return null;
  }
}
