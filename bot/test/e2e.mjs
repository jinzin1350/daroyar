// End-to-end check of the real Worker (wrangler dev / workerd) with:
//   - a local MQTT broker (aedes) standing in for EMQX/HiveMQ/Mosquitto
//   - a fake Telegram Bot API that records every call
//   - a simulated parent phone publishing an encrypted report
// Run: node test/e2e.mjs
import net from "node:net";
import http from "node:http";
import { once } from "node:events";
import { spawn } from "node:child_process";
import { Duplex } from "node:stream";
import { Aedes } from "aedes";
import * as C from "../src/crypto.js";
import { Mqtt, stateTopic, configTopic } from "../src/mqtt.js";
import { dateOf, dueAt } from "../src/logic.js";

const TOKEN = "123456:TEST-token";
const CODE = "K7QF-2M9X-ABCD-EFGH-JKLM";
const OFF = 210;
const MIN = 60_000;
let failures = 0;
const check = (ok, what) => {
  console.log((ok ? "  ✓ " : "  ✗ ") + what);
  if (!ok) failures++;
};

// ---- local broker
const broker = await Aedes.createBroker();
const mqttServer = net.createServer(broker.handle).listen(0);
await once(mqttServer, "listening");
const MQTT_PORT = mqttServer.address().port;

async function dial() {
  const sock = net.connect(MQTT_PORT, "127.0.0.1");
  await once(sock, "connect");
  const { readable, writable } = Duplex.toWeb(sock);
  return { readable, writable, close: () => sock.destroy() };
}

// ---- fake Telegram
const calls = [];
const tgServer = http.createServer(async (req, res) => {
  let body = "";
  for await (const c of req) body += c;
  const method = req.url.split("/").pop();
  const params = body ? JSON.parse(body) : {};
  calls.push({ method, params });
  const result =
    method === "getMe" ? { id: 1, username: "daroyar_test_bot" }
    : method === "getChatMember" ? { status: "member" }
    : method === "sendMessage" ? { message_id: calls.length }
    : true;
  res.setHeader("content-type", "application/json");
  res.end(JSON.stringify({ ok: true, result }));
});
tgServer.listen(0);
await once(tgServer, "listening");
const TG_PORT = tgServer.address().port;

// ---- the Worker
const brokers = JSON.stringify([{ name: "local", host: "127.0.0.1", ports: [[MQTT_PORT, false]] }]);
const worker = spawn("npx", [
  "wrangler", "dev", "--port", "8799", "--ip", "127.0.0.1", "--persist-to", ".wrangler/e2e-" + Date.now(),
  "--var", `TELEGRAM_BOT_TOKEN:${TOKEN}`,
  "--var", `TEST_BROKERS:${brokers}`,
  "--var", `TEST_TELEGRAM_API:http://127.0.0.1:${TG_PORT}`,
], { cwd: new URL("..", import.meta.url).pathname, detached: true, env: { ...process.env, WRANGLER_SEND_METRICS: "false", NO_COLOR: "1" } });
let wlog = "";
worker.stdout.on("data", (d) => (wlog += d));
worker.stderr.on("data", (d) => (wlog += d));
const BASE = "http://127.0.0.1:8799";
for (let i = 0; i < 120; i++) {
  try {
    if ((await fetch(BASE + "/")).ok) break;
  } catch {}
  await new Promise((r) => setTimeout(r, 500));
}

const secret = await C.webhookSecret(TOKEN);
const admin = (path) => fetch(BASE + path, { headers: { "X-Setup": secret } }).then((r) => r.json());
const tg = (update) =>
  fetch(BASE + "/tg", {
    method: "POST",
    headers: { "content-type": "application/json", "X-Telegram-Bot-Api-Secret-Token": secret },
    body: JSON.stringify(update),
  });
const waitCalls = async (pred, ms = 8000) => {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (calls.some(pred)) return true;
    await new Promise((r) => setTimeout(r, 150));
  }
  return false;
};

async function initData(user) {
  const p = new URLSearchParams({ auth_date: String(Math.floor(Date.now() / 1000)), user: JSON.stringify(user), query_id: "q1" });
  const check = [...p.entries()].sort(([a], [b]) => (a < b ? -1 : 1)).map(([k, v]) => `${k}=${v}`).join("\n");
  const secretKey = await C.hmac(new TextEncoder().encode("WebAppData"), TOKEN);
  p.set("hash", C.hex(await C.hmac(secretKey, check)));
  return p.toString();
}

try {
  console.log("worker up:", (await (await fetch(BASE + "/")).text()).trim());

  console.log("setup");
  const s = await admin("/setup");
  check(s.bot === "daroyar_test_bot" && s.commands && s.menu, "setup registers commands + menu button");

  console.log("webhook security");
  const bad = await fetch(BASE + "/tg", { method: "POST", body: "{}" });
  check(bad.status === 403, "webhook without secret is rejected");

  console.log("child forwards the code in a private chat");
  const tid = await C.topicId(CODE);
  calls.length = 0;
  await tg({ update_id: 1, message: { message_id: 1, chat: { id: 555, type: "private" }, from: { id: 555, first_name: "Ali" }, text: `کد اتصال دارویار (مامان):\n${CODE}` } });
  check(await waitCalls((c) => c.method === "sendMessage" && /وصل شد/.test(c.params.text)), "bot confirms the phone is linked");
  const linkMsg = calls.find((c) => c.method === "sendMessage" && /وصل شد/.test(c.params.text));
  check(!!linkMsg && JSON.stringify(linkMsg.params.reply_markup || {}).includes("/app"), "confirmation carries the Mini App button");
  await new Promise((r) => setTimeout(r, 3500)); // let the link-time check finish

  console.log("parent phone answers «نخوردم» for a dose due after linking");
  const now = Date.now();
  const today = dateOf(now + 2 * MIN, OFF);
  const time = new Date(now + 2 * MIN + OFF * MIN).toISOString().slice(11, 16);
  const due = dueAt(today, time, OFF);
  const report = {
    v: 1, name: "مامان", sentAt: now, tz: "Asia/Tehran", tzOff: OFF, grace: 30, ver: "1.0.5", cfgRev: 0,
    meds: [{ id: 1, name: "قرص فشار", dose: "یک عدد", times: [time], since: 0, active: true }],
    events: [{ m: 1, n: "قرص فشار", q: "یک عدد", d: today, t: time, due, s: "skipped", sa: now }],
    diag: { notif: true, exact: true, fsi: true, overlay: true, battery: true, tz: true },
  };
  const phone = new Mqtt(await dial(), "phone");
  await phone.connect("phone1");
  await phone.publish(stateTopic(tid), await C.encrypt(CODE, JSON.stringify(report)));
  await phone.close();
  const t1 = await admin("/tick");
  check(t1.alertsSent === 1, "one alert sent by the check");
  check(await waitCalls((c) => c.method === "sendMessage" && /نخوردم/.test(c.params.text) && c.params.chat_id === 555), "«نخوردم» alert delivered to the chat");

  console.log("a second check must not repeat the alert");
  const before = calls.filter((c) => /نخوردم/.test(c.params.text || "")).length;
  const t = await admin("/tick");
  check(t.brokers === 1 && t.devices === 1, "tick reached the broker and sees 1 phone");
  check(calls.filter((c) => /نخوردم/.test(c.params.text || "")).length === before, "no duplicate alert");

  console.log("/status");
  await tg({ update_id: 2, message: { message_id: 2, chat: { id: 555, type: "private" }, from: { id: 555 }, text: "/status" } });
  check(await waitCalls((c) => c.method === "sendMessage" && /آخرین خبر/.test(c.params.text)), "status lists the phone");

  console.log("Mini App: auth, list, save medicines");
  const unauth = await fetch(BASE + "/api/state");
  check(unauth.status === 401, "API rejects requests without valid init data");
  const auth = { "X-Init-Data": await initData({ id: 555, first_name: "Ali" }), "content-type": "application/json" };
  const st = await (await fetch(BASE + "/api/state", { headers: auth })).json();
  check(st.devices && st.devices.length === 1 && st.devices[0].label === "مامان", "Mini App sees the linked phone");
  const stranger = { ...auth, "X-Init-Data": await initData({ id: 999 }) };
  // 999 is not in a linked chat; our fake getChatMember says "member" only for group chats, and there are none
  const st2 = await (await fetch(BASE + "/api/state", { headers: stranger })).json();
  check(st2.devices.length === 0, "a stranger sees nothing");

  const meds = [
    { id: 1, name: "قرص فشار", dose: "یک عدد", times: [time] },
    { id: 2, name: "ویتامین D", dose: "", times: ["21:00", "09:00"] },
  ];
  const saved = await (await fetch(BASE + "/api/meds", { method: "POST", headers: auth, body: JSON.stringify({ device: tid, meds }) })).json();
  check(saved.ok && saved.sent === 1, "save publishes the medicine list to the broker");
  check(saved.meds[1].times.join() === "09:00,21:00", "times are sorted");
  const badSave = await (await fetch(BASE + "/api/meds", { method: "POST", headers: auth, body: JSON.stringify({ device: tid, meds: [{ id: 3, name: "x", times: ["25:99"] }] }) })).json();
  check(!badSave.ok, "invalid times are rejected");

  console.log("the parent phone can read and decrypt the new list");
  const reader = new Mqtt(await dial(), "reader");
  await reader.connect("phone2");
  const got = await reader.collect([configTopic(tid)], 2000);
  const cfg = await C.open(CODE, got.get(configTopic(tid)));
  await reader.close();
  check(cfg && cfg.meds.length === 2 && cfg.rev > 0, "config on the broker decrypts with the pairing code");

  console.log("/remove unlinks");
  await tg({ update_id: 3, callback_query: { id: "cb1", data: "rm:" + tid, message: { message_id: 9, chat: { id: 555, type: "private" } } } });
  check(await waitCalls((c) => c.method === "editMessageText"), "phone removed from the chat");
  const st3 = await (await fetch(BASE + "/api/state", { headers: auth })).json();
  check(st3.devices.length === 0, "nothing left after removal");
} catch (e) {
  console.error(e);
  failures++;
} finally {
  try {
    process.kill(-worker.pid, "SIGTERM"); // whole group: npx, wrangler, workerd
  } catch {}
  mqttServer.close();
  tgServer.close();
  await new Promise((r) => broker.close(r));
  if (failures) console.log("\nworker log tail:\n" + wlog.split("\n").slice(-40).join("\n"));
  console.log(failures ? `\n${failures} check(s) FAILED` : "\nall end-to-end checks passed");
  process.exit(failures ? 1 : 0);
}
