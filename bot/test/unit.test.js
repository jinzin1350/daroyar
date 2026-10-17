// node --test test/
import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import net from "node:net";
import { once } from "node:events";
import { Duplex } from "node:stream";
import { fileURLToPath } from "node:url";
import { Aedes } from "aedes";

import * as C from "../src/crypto.js";
import { Mqtt, connectAll, closeAll } from "../src/mqtt.js";
import { Report, evaluate, mergeSince, dueAt, dateOf } from "../src/logic.js";

const JAVA = fileURLToPath(new URL("./CryptoJava.java", import.meta.url));
const java = (...args) => execFileSync("java", [JAVA, ...args], { encoding: "utf8", env: { ...process.env, JAVA_TOOL_OPTIONS: "" } });
const CODE = "K7QF-2M9X-ABCD-EFGH-JKLM";

// ------------------------------------------------------------- crypto vs the Android app

test("topic id matches the Android implementation", async () => {
  assert.equal(await C.topicId(CODE), java("topic", CODE));
  assert.equal(await C.topicId(CODE.toLowerCase().replace(/-/g, " ")), java("topic", CODE));
});

test("payload from the app decrypts in the bot, and back", async () => {
  const text = JSON.stringify({ hello: "سلام مامان", n: 42 });
  const b64 = (t) => Buffer.from(t, "utf8").toString("base64");
  const fromApp = Uint8Array.from(Buffer.from(java("enc", CODE, b64(text)), "base64"));
  assert.equal(await C.decrypt(CODE, fromApp), text);
  const fromBot = await C.encrypt(CODE, text);
  assert.equal(Buffer.from(java("dec", CODE, Buffer.from(fromBot).toString("base64")), "base64").toString("utf8"), text);
  assert.equal(await C.decrypt("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ", fromBot), null, "wrong code must fail");
});

test("code extraction from the shared message", () => {
  const msg = "کد اتصال دارویار (مامان):\nK7QF-2M9X-ABCD-EFGH-JKLM";
  assert.equal(C.extractCode(msg), C.normalize(CODE));
  assert.equal(C.extractCode("/add k7qf-2m9x-abcd-efgh-jklm بابا"), C.normalize(CODE));
  assert.equal(C.extractCode("hello"), null);
});

// ------------------------------------------------------------- MQTT client vs a real broker

let broker, server, port;
async function dial(host, p) {
  const sock = net.connect(p, host);
  await once(sock, "connect");
  const { readable, writable } = Duplex.toWeb(sock);
  return { readable, writable, close: () => sock.destroy() };
}

before(async () => {
  broker = await Aedes.createBroker();
  server = net.createServer(broker.handle);
  server.listen(0);
  await once(server, "listening");
  port = server.address().port;
});
after(async () => {
  server.close();
  await new Promise((r) => broker.close(r));
});

test("publish retained, then a new session collects it", async () => {
  const a = new Mqtt(await dial("127.0.0.1", port), "a");
  await a.connect("t1");
  const payload = await C.encrypt(CODE, '{"x":1}');
  assert.equal(await a.publish("daroyar/v1/t/state", payload), true);
  await a.close();

  const b = new Mqtt(await dial("127.0.0.1", port), "b");
  await b.connect("t2");
  const t0 = Date.now();
  const got = await b.collect(["daroyar/v1/t/state", "daroyar/v1/t/config"], 1500);
  assert.deepEqual(JSON.parse(await C.decrypt(CODE, got.get("daroyar/v1/t/state"))), { x: 1 });
  assert.equal(got.has("daroyar/v1/t/config"), false);
  assert.ok(Date.now() - t0 >= 1400, "waits for the missing topic until the deadline");
  await b.close();
});

test("connectAll falls back to the next port and skips dead brokers", async () => {
  const log = [];
  const brokers = [
    { name: "local", host: "127.0.0.1", ports: [[1, false], [port, false]] },
    { name: "dead", host: "127.0.0.1", ports: [[2, false]] },
  ];
  const conns = await connectAll(dial, log, brokers);
  assert.equal(conns.length, 1);
  assert.equal(conns[0].name, "local");
  await closeAll(conns);
});

// ------------------------------------------------------------- alert logic

const MIN = 60_000;
const OFF = 210; // Tehran
function reportAt(now, events, extra = {}) {
  return new Report({
    sentAt: now, tzOff: OFF, grace: 30, cfgRev: 5,
    meds: [{ id: 1, name: "قرص فشار", dose: "یک عدد", times: ["08:00"], since: 0, active: true }],
    events, diag: { notif: true, exact: true, fsi: true, overlay: true, battery: true, tz: true }, ...extra,
  });
}

test("skipped dose alerts immediately, and only once", () => {
  const today = dateOf(Date.now(), OFF);
  const due = dueAt(today, "08:00", OFF);
  const now = due + 5 * MIN;
  const cur = reportAt(now, [{ m: 1, n: "قرص فشار", q: "یک عدد", d: today, t: "08:00", due, s: "skipped", sa: now }]);
  const seen = new Set();
  const a = evaluate({ label: "مامان", topic: "t", pairedAt: 0 }, null, cur, seen, now);
  assert.equal(a.length, 1);
  assert.match(a[0].text, /نخوردم/);
  assert.equal(evaluate({ label: "مامان", topic: "t", pairedAt: 0 }, null, cur, seen, now).length, 0);
});

test("no news from the phone triggers the dead-man alert, then a correction", () => {
  const today = dateOf(Date.now(), OFF);
  const due = dueAt(today, "08:00", OFF);
  const seen = new Set();
  const dev = { label: "بابا", topic: "t", pairedAt: 0 };
  // last report before the dose, nothing since; 61 minutes later
  const stale = reportAt(due - 60 * MIN, []);
  const a = evaluate(dev, null, stale, seen, due + 61 * MIN);
  assert.equal(a.length, 1);
  assert.match(a[0].text, /خبری نرسیده/);
  // phone comes back: it was taken late
  const late = reportAt(due + 70 * MIN, [{ m: 1, n: "قرص فشار", d: today, t: "08:00", due, s: "taken", ta: due + 65 * MIN }]);
  const b = evaluate(dev, stale, late, seen, due + 71 * MIN);
  assert.equal(b.length, 1);
  assert.match(b[0].text, /خورده شد/);
});

test("taken on time sends only the optional note; nothing before pairing", () => {
  const today = dateOf(Date.now(), OFF);
  const due = dueAt(today, "08:00", OFF);
  const ev = [{ m: 1, n: "قرص فشار", d: today, t: "08:00", due, s: "taken", ta: due + 2 * MIN }];
  const a = evaluate({ label: "مامان", topic: "t", pairedAt: 0 }, null, reportAt(due + 3 * MIN, ev), new Set(), due + 3 * MIN);
  assert.equal(a.length, 1);
  assert.equal(a[0].optional, true);
  const b = evaluate({ label: "مامان", topic: "t", pairedAt: due + MIN }, null, reportAt(due + 3 * MIN, ev), new Set(), due + 3 * MIN);
  assert.equal(b.length, 0);
});

test("since is kept unless the times change", () => {
  const old = [{ id: 1, name: "a", dose: "", times: ["08:00"], since: 111 }];
  const same = mergeSince(old, [{ id: 1, name: "b", dose: "", times: ["08:00"] }], 999);
  assert.equal(same[0].since, 111);
  const changed = mergeSince(old, [{ id: 1, name: "a", dose: "", times: ["08:00", "20:00"] }], 999);
  assert.equal(changed[0].since, 999);
});
