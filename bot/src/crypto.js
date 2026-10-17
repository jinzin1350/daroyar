// Same scheme as the Android app (Crypto.kt), byte for byte:
//   topicId = hex(SHA-256("daroyar/topic/v1/" + code))[first 12 bytes]
//   key     = SHA-256("daroyar/key/v1/" + code)            (AES-256-GCM)
//   payload = 0x01 | iv(12) | ciphertext+tag(16)

const ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const LEN = 20;
const enc = new TextEncoder();
const dec = new TextDecoder();

export function normalize(input) {
  let out = "";
  for (const ch of String(input).toUpperCase()) if (ALPHA.includes(ch)) out += ch;
  return out;
}

export function isValid(code) {
  return normalize(code).length === LEN;
}

/** Finds a pairing code inside a message, e.g. one shared from the parent phone. */
export function extractCode(text) {
  const m = String(text).toUpperCase().match(/[A-Z2-9]{4}(-[A-Z2-9]{4}){4}/);
  if (m) return normalize(m[0]);
  const n = normalize(text);
  return n.length === LEN ? n : null;
}

export function pretty(code) {
  return normalize(code).match(/.{1,4}/g).join("-");
}

async function sha256(label, code) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(label + normalize(code))));
}

export async function topicId(code) {
  const h = await sha256("daroyar/topic/v1/", code);
  return Array.from(h.slice(0, 12), (b) => b.toString(16).padStart(2, "0")).join("");
}

const keyCache = new Map();
async function key(code) {
  const n = normalize(code);
  if (!keyCache.has(n)) {
    const raw = await sha256("daroyar/key/v1/", n);
    keyCache.set(n, await crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]));
  }
  return keyCache.get(n);
}

export async function encrypt(code, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await key(code), enc.encode(text)));
  const out = new Uint8Array(1 + 12 + ct.length);
  out[0] = 1;
  out.set(iv, 1);
  out.set(ct, 13);
  return out;
}

export async function decrypt(code, data) {
  try {
    if (!data || data.length < 1 + 12 + 16 || data[0] !== 1) return null;
    const pt = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: data.slice(1, 13) },
      await key(code),
      data.slice(13)
    );
    return dec.decode(pt);
  } catch {
    return null;
  }
}

/** Decrypts and parses JSON; null if anything is off. */
export async function open(code, data) {
  const s = await decrypt(code, data);
  if (s == null) return null;
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

export async function hmac(keyBytes, msg) {
  const k = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", k, typeof msg === "string" ? enc.encode(msg) : msg));
}

export function hex(bytes) {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** Webhook secret derived from the bot token (the deploy workflow computes the same). */
export async function webhookSecret(botToken) {
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", enc.encode(botToken)));
  return hex(h).slice(0, 32);
}
