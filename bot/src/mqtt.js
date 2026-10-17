// Minimal MQTT 3.1.1 client over a byte stream (Cloudflare TCP socket in the
// Worker, a Node socket in tests). Only what we need: connect, subscribe and
// collect retained messages, publish retained with QoS 1, disconnect.

const enc = new TextEncoder();
const dec = new TextDecoder();

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

function varint(n) {
  const out = [];
  do {
    let b = n % 128;
    n = Math.floor(n / 128);
    if (n > 0) b |= 0x80;
    out.push(b);
  } while (n > 0);
  return out;
}

function str(s) {
  const b = enc.encode(s);
  return [b.length >> 8, b.length & 0xff, ...b];
}

function packet(first, body) {
  const rl = varint(body.length);
  const out = new Uint8Array(1 + rl.length + body.length);
  out[0] = first;
  out.set(rl, 1);
  out.set(body, 1 + rl.length);
  return out;
}

export class Mqtt {
  /** socket: { readable, writable, close() } */
  constructor(socket, name = "") {
    this.socket = socket;
    this.name = name;
    this.reader = socket.readable.getReader();
    this.writer = socket.writable.getWriter();
    this.buf = new Uint8Array(0);
    this.pending = null;
    this.pid = 1;
    this.retained = new Map(); // topic -> Uint8Array
  }

  async write(bytes) {
    await this.writer.write(bytes);
  }

  async fill(deadline) {
    const left = deadline - Date.now();
    if (left <= 0) return false;
    if (!this.pending) this.pending = this.reader.read();
    let timer;
    const timeout = new Promise((r) => (timer = setTimeout(() => r("timeout"), left)));
    const res = await Promise.race([this.pending, timeout]);
    clearTimeout(timer);
    if (res === "timeout") return false;
    this.pending = null;
    if (res.done) throw new Error("connection closed");
    this.buf = concat(this.buf, res.value);
    return true;
  }

  tryParse() {
    const b = this.buf;
    if (b.length < 2) return null;
    let mult = 1, rl = 0, i = 1;
    for (;;) {
      if (i >= b.length) return null;
      const d = b[i++];
      rl += (d & 0x7f) * mult;
      if ((d & 0x80) === 0) break;
      mult *= 128;
      if (i > 4) throw new Error("bad length");
    }
    if (b.length < i + rl) return null;
    const p = { type: b[0] >> 4, flags: b[0] & 0x0f, body: b.slice(i, i + rl) };
    this.buf = b.slice(i + rl);
    return p;
  }

  /** Next packet, handling incoming PUBLISH on the way. Null on timeout. */
  async next(deadline) {
    for (;;) {
      const p = this.tryParse();
      if (p) {
        if (p.type === 3) {
          await this.onPublish(p);
          continue;
        }
        return p;
      }
      if (!(await this.fill(deadline))) return null;
    }
  }

  async onPublish(p) {
    const qos = (p.flags >> 1) & 3;
    const tl = (p.body[0] << 8) | p.body[1];
    const topic = dec.decode(p.body.slice(2, 2 + tl));
    let off = 2 + tl;
    if (qos > 0) {
      const id = [p.body[off], p.body[off + 1]];
      off += 2;
      await this.write(packet(0x40, id)); // PUBACK
    }
    if (!this.retained.has(topic)) this.retained.set(topic, p.body.slice(off));
  }

  async connect(clientId, timeoutMs = 8000) {
    const body = [0, 4, 77, 81, 84, 84, 4, 0x02, 0, 60, ...str(clientId)];
    await this.write(packet(0x10, body));
    const p = await this.next(Date.now() + timeoutMs);
    if (!p || p.type !== 2 || p.body[1] !== 0) throw new Error("connect refused");
  }

  /** Subscribes and waits until every topic delivered its retained message, or [waitMs]. */
  async collect(topics, waitMs = 2500) {
    if (!topics.length) return this.retained;
    const id = this.pid++;
    const body = [id >> 8, id & 0xff];
    for (const t of topics) body.push(...str(t), 0);
    await this.write(packet(0x82, body));
    const deadline = Date.now() + waitMs;
    while (Date.now() < deadline && !topics.every((t) => this.retained.has(t))) {
      const p = await this.next(deadline);
      if (!p) break; // SUBACK and others are simply consumed
    }
    return this.retained;
  }

  /** Retained publish, QoS 1 (waits for the broker's acknowledgement). */
  async publish(topic, payload, timeoutMs = 6000) {
    const id = this.pid++;
    const body = new Uint8Array([...str(topic), id >> 8, id & 0xff, ...payload]);
    await this.write(packet(0x33, body));
    const deadline = Date.now() + timeoutMs;
    for (;;) {
      const p = await this.next(deadline);
      if (!p) return false;
      if (p.type === 4 && ((p.body[0] << 8) | p.body[1]) === id) return true;
    }
  }

  async close() {
    try {
      await this.write(new Uint8Array([0xe0, 0x00]));
    } catch {}
    try {
      this.reader.releaseLock();
    } catch {}
    try {
      await this.writer.close();
    } catch {}
    try {
      await this.socket.close();
    } catch {}
  }
}

export const BROKERS = [
  { name: "EMQX", host: "broker.emqx.io", ports: [[8883, true], [1883, false]] },
  { name: "HiveMQ", host: "broker.hivemq.com", ports: [[8883, true], [1883, false]] },
  { name: "Mosquitto", host: "test.mosquitto.org", ports: [[8886, true], [1883, false]] },
];

function clientId() {
  return "dyb" + Array.from(crypto.getRandomValues(new Uint8Array(6)), (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Connects to every broker in parallel (TLS first, then plain — payloads are
 * encrypted anyway). dial(host, port, tls) must return a socket.
 */
export async function connectAll(dial, log = [], brokers = BROKERS) {
  const tries = brokers.map(async (b) => {
    for (const [port, tls] of b.ports) {
      let socket;
      try {
        socket = await dial(b.host, port, tls);
        const m = new Mqtt(socket, b.name);
        await m.connect(clientId());
        log.push(`${b.name}: ok (${tls ? "tls" : "tcp"})`);
        return m;
      } catch (e) {
        log.push(`${b.name}:${port} ✗ ${e && e.message}`);
        try {
          socket && socket.close();
        } catch {}
      }
    }
    return null;
  });
  return (await Promise.all(tries)).filter(Boolean);
}

export async function closeAll(conns) {
  await Promise.all(conns.map((c) => c.close()));
}

export const stateTopic = (tid) => `daroyar/v1/${tid}/state`;
export const configTopic = (tid) => `daroyar/v1/${tid}/config`;
