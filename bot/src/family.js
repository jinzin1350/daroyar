import { DurableObject } from "cloudflare:workers";
import { connect } from "cloudflare:sockets";
import { topicId, extractCode, encrypt, open } from "./crypto.js";
import { connectAll, closeAll, stateTopic, configTopic, BROKERS } from "./mqtt.js";
import { Report, evaluate, statusText, mergeSince, escapeHtml, clockOf, faTime, ago } from "./logic.js";
import { Bot } from "./telegram.js";

const dial = async (host, port, tls) => {
  const s = connect({ hostname: host, port }, { secureTransport: tls ? "on" : "off", allowHalfOpen: false });
  await s.opened;
  return s;
};

const rev = (o) => (o && Number(o.rev)) || -1;
const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

/**
 * One Durable Object holds the whole family state (devices, last reports,
 * medicine lists, sent alerts). Single-threaded, so no races between the
 * cron run, the webhook and the Mini App.
 *
 * devices: { [topicId]: { code, label, pairedAt, chats: [chatId], editors: [userId] } }
 */
export class Family extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    // TEST_BROKERS / TEST_TELEGRAM_API exist only for local end-to-end tests.
    this.bot = new Bot(env.TELEGRAM_BOT_TOKEN, env.TEST_TELEGRAM_API || undefined);
    this.brokers = env.TEST_BROKERS ? JSON.parse(env.TEST_BROKERS) : BROKERS;
  }

  get store() {
    return this.ctx.storage;
  }

  async devices() {
    return (await this.store.get("devices")) || {};
  }

  async report(tid) {
    const j = await this.store.get("rep:" + tid);
    return j ? new Report(j) : null;
  }

  async config(tid) {
    return (await this.store.get("cfg:" + tid)) || null;
  }

  /** Medicines as they should be: newest of our list and what the phone reports. */
  async currentMeds(tid) {
    const cfg = await this.config(tid);
    const rep = await this.report(tid);
    if (rep && rep.cfgRev > rev(cfg)) return { meds: rep.meds, rev: rep.cfgRev };
    return { meds: (cfg && cfg.meds) || [], rev: rev(cfg) };
  }

  async pending(tid) {
    const cfg = await this.config(tid);
    if (!cfg) return false;
    const rep = await this.report(tid);
    return !rep || rep.cfgRev < cfg.rev;
  }

  async origin() {
    return (await this.store.get("origin")) || "";
  }

  // ------------------------------------------------------------ linking

  async link(code, label, chatId, userId) {
    const tid = await topicId(code);
    const devs = await this.devices();
    const isNew = !devs[tid];
    const d = devs[tid] || { code, label: label || "مامان", pairedAt: Date.now(), chats: [], editors: [] };
    if (label) d.label = label;
    if (!d.chats.includes(chatId)) d.chats.push(chatId);
    if (userId && !d.editors.includes(userId)) d.editors.push(userId);
    devs[tid] = d;
    await this.store.put("devices", devs);
    return { tid, d, isNew };
  }

  async unlink(tid, chatId) {
    const devs = await this.devices();
    const d = devs[tid];
    if (!d) return null;
    d.chats = d.chats.filter((c) => c !== chatId);
    if (!d.chats.length) {
      delete devs[tid];
      await this.store.delete(["rep:" + tid, "cfg:" + tid]);
    }
    await this.store.put("devices", devs);
    return d;
  }

  async devicesOfChat(chatId) {
    const devs = await this.devices();
    return Object.entries(devs).filter(([, d]) => d.chats.includes(chatId));
  }

  /** Who may edit medicines in the Mini App: whoever linked it, or members of a linked chat. */
  async canManage(user, d) {
    if (d.editors.includes(user.id) || d.chats.includes(user.id)) return true;
    for (const c of d.chats) {
      if (c < 0 && (await this.bot.isMember(c, user.id))) {
        d.editors.push(user.id); // remember, saves a lookup next time
        return true;
      }
    }
    return false;
  }

  // ------------------------------------------------------------ the periodic check

  async tick() {
    const devs = await this.devices();
    const tids = Object.keys(devs);
    if (!tids.length) return { devices: 0 };
    const log = [];
    const conns = await connectAll(dial, log, this.brokers);
    let sent = 0;
    try {
      const topics = tids.flatMap((t) => [stateTopic(t), configTopic(t)]);
      await Promise.all(conns.map((c) => c.collect(topics, 2500).catch((e) => log.push(`${c.name}: ${e.message}`))));
      const alerted = new Set((await this.store.get("alerted")) || []);
      const before = alerted.size;

      for (const tid of tids) {
        const d = devs[tid];
        const prev = await this.report(tid);
        let best = null;
        for (const c of conns) {
          const o = await open(d.code, c.retained.get(stateTopic(tid)));
          if (o && (!best || o.sentAt > best.sentAt)) best = o;
        }
        if (best && (!prev || best.sentAt > prev.sentAt)) await this.store.put("rep:" + tid, best);
        const cur = await this.report(tid);

        await this.syncConfig(tid, d, conns, cur, log);

        // Only judge silence when OUR side reached a broker.
        if (!conns.length) continue;
        const alerts = evaluate({ label: d.label, topic: tid, pairedAt: d.pairedAt }, prev, cur, alerted);
        for (const a of alerts) sent += await this.deliver(tid, d, a);
      }
      if (alerted.size !== before) await this.store.put("alerted", [...alerted].slice(-500));
    } finally {
      await closeAll(conns);
    }
    await this.store.put("lastTick", { at: Date.now(), brokers: conns.length, log: log.slice(-12) });
    return { devices: tids.length, brokers: conns.length, alertsSent: sent, log };
  }

  /** Newest medicine list wins; push it to brokers that hold an older one. */
  async syncConfig(tid, d, conns, cur, log) {
    let newest = await this.config(tid);
    const remote = [];
    for (const c of conns) {
      const o = await open(d.code, c.retained.get(configTopic(tid)));
      remote.push(o);
      if (o && rev(o) > rev(newest)) newest = o;
    }
    if (cur && cur.cfgRev > 0 && cur.cfgRev > rev(newest)) newest = { v: 1, rev: cur.cfgRev, meds: cur.meds };
    if (!newest) return;
    if (rev(newest) > rev(await this.config(tid))) await this.store.put("cfg:" + tid, newest);
    let payload = null;
    for (let i = 0; i < conns.length; i++) {
      if (rev(remote[i]) >= rev(newest)) continue;
      payload = payload || (await encrypt(d.code, JSON.stringify(newest)));
      const ok = await conns[i].publish(configTopic(tid), payload).catch(() => false);
      log.push(`${conns[i].name}: config ${ok ? "sent" : "✗"}`);
    }
  }

  async deliver(tid, d, alert) {
    let n = 0;
    for (const chatId of [...d.chats]) {
      if (alert.optional && !(await this.store.get("taken:" + chatId))) continue;
      const r = await this.bot.send(chatId, alert.text, { reply_markup: await this.appButton(chatId) });
      if (r.ok) n++;
      else if (r.error_code === 403 || r.error_code === 400) await this.unlink(tid, chatId); // bot removed / chat gone
    }
    return n;
  }

  // ------------------------------------------------------------ Telegram updates

  async appButton(chatId) {
    const origin = await this.origin();
    if (!origin) return undefined;
    if (chatId > 0) {
      return { inline_keyboard: [[{ text: "💊 مدیریت داروها", web_app: { url: origin + "/app" } }]] };
    }
    const name = await this.store.get("botname");
    return name ? { inline_keyboard: [[{ text: "💊 مدیریت داروها", url: `https://t.me/${name}?start=app` }]] } : undefined;
  }

  async onUpdate(u) {
    try {
      if (u.message) return await this.onMessage(u.message);
      if (u.callback_query) return await this.onCallback(u.callback_query);
      if (u.my_chat_member) {
        const m = u.my_chat_member;
        const st = m.new_chat_member && m.new_chat_member.status;
        if (m.chat.type !== "private" && (st === "member" || st === "administrator")) {
          await this.bot.send(m.chat.id, GROUP_WELCOME);
        }
      }
    } catch (e) {
      console.log("update failed", e && e.stack);
    }
  }

  async onMessage(msg) {
    const chat = msg.chat;
    const text = (msg.text || msg.caption || "").trim();
    const user = msg.from || {};
    const isPrivate = chat.type === "private";
    const cmd = text.startsWith("/") ? text.split(/\s+/)[0].split("@")[0].toLowerCase() : "";
    const code = extractCode(text);

    if (code && (cmd === "/add" || !cmd)) return this.addDevice(chat, user, text, code);

    switch (cmd) {
      case "/start":
      case "/help":
        return this.bot.send(chat.id, isPrivate ? PRIVATE_WELCOME : GROUP_WELCOME, {
          reply_markup: await this.appButton(chat.id),
        });
      case "/app":
        return this.bot.send(chat.id, "داروها را از اینجا اضافه یا ویرایش کنید:", {
          reply_markup: await this.appButton(chat.id),
        });
      case "/add":
        return this.bot.send(chat.id, "کد را هم بنویسید، مثلاً:\n<code>/add ABCD-EFGH-JKLM-NPQR-STUV مامان</code>");
      case "/status":
        return this.status(chat.id);
      case "/remove":
        return this.removeMenu(chat.id);
      case "/taken_on":
        await this.store.put("taken:" + chat.id, true);
        return this.bot.send(chat.id, "✅ از این به بعد، هر بار دارو خورده شد هم اینجا خبر می‌دهم.");
      case "/taken_off":
        await this.store.delete("taken:" + chat.id);
        return this.bot.send(chat.id, "باشد، فقط وقتی مشکلی باشد خبر می‌دهم.");
      default:
        if (isPrivate) return this.bot.send(chat.id, PRIVATE_WELCOME, { reply_markup: await this.appButton(chat.id) });
    }
  }

  async addDevice(chat, user, text, code) {
    const paren = text.match(/\(([^)]{1,30})\)/);
    let label = paren ? paren[1].trim() : "";
    if (!label) {
      label = text
        .replace(/^\/add(@\S+)?/i, "")
        .replace(/[A-Za-z2-9]{4}(-[A-Za-z2-9]{4}){4}/, "")
        .replace(/کد اتصال دارویار:?/g, "")
        .trim()
        .slice(0, 30);
    }
    const { tid, isNew } = await this.link(code, label, chat.id, user.id);
    const devs = await this.devices();
    const d = devs[tid];
    await this.bot.send(
      chat.id,
      `✅ گوشی «${escapeHtml(d.label)}» ${isNew ? "وصل شد" : "به این گفتگو هم وصل شد"}.\n` +
        `حالا داروها را با دکمهٔ زیر وارد کنید. هشدارها همین‌جا می‌آید.`,
      { reply_markup: await this.appButton(chat.id) }
    );
    // Pick up its first report right away.
    this.ctx.waitUntil(this.tick().catch(() => {}));
  }

  async status(chatId) {
    const list = await this.devicesOfChat(chatId);
    if (!list.length) {
      return this.bot.send(chatId, "هنوز گوشی‌ای به این گفتگو وصل نشده. کد گوشی مامان یا بابا را بفرستید (در گروه: <code>/add کد اسم</code>).");
    }
    const parts = [];
    for (const [tid, d] of list) parts.push(statusText(d.label, await this.report(tid), await this.pending(tid)));
    const last = await this.store.get("lastTick");
    if (last) parts.push(`<i>آخرین بررسی ربات: ${ago(last.at)} — ${last.brokers} سرویس در دسترس</i>`);
    return this.bot.send(chatId, parts.join("\n\n"), { reply_markup: await this.appButton(chatId) });
  }

  async removeMenu(chatId) {
    const list = await this.devicesOfChat(chatId);
    if (!list.length) return this.bot.send(chatId, "گوشی‌ای به این گفتگو وصل نیست.");
    return this.bot.send(chatId, "کدام گوشی از این گفتگو جدا شود؟", {
      reply_markup: { inline_keyboard: list.map(([tid, d]) => [{ text: "🗑 " + d.label, callback_data: "rm:" + tid }]) },
    });
  }

  async onCallback(q) {
    const data = q.data || "";
    if (data.startsWith("rm:") && q.message) {
      const d = await this.unlink(data.slice(3), q.message.chat.id);
      await this.bot.call("answerCallbackQuery", { callback_query_id: q.id, text: d ? "جدا شد" : "پیدا نشد" });
      if (d) {
        await this.bot.call("editMessageText", {
          chat_id: q.message.chat.id,
          message_id: q.message.message_id,
          text: `گوشی «${d.label}» از این گفتگو جدا شد.`,
        });
      }
      return;
    }
    await this.bot.call("answerCallbackQuery", { callback_query_id: q.id });
  }

  // ------------------------------------------------------------ Mini App API

  async apiState(user) {
    const devs = await this.devices();
    const out = [];
    let changed = false;
    for (const [tid, d] of Object.entries(devs)) {
      const before = d.editors.length;
      if (!(await this.canManage(user, d))) continue;
      if (d.editors.length !== before) changed = true;
      const rep = await this.report(tid);
      const { meds } = await this.currentMeds(tid);
      out.push({
        id: tid,
        label: d.label,
        meds,
        today: rep ? rep.todayLine() : null,
        lastSeen: rep ? ago(rep.sentAt) : null,
        nowThere: faTime(clockOf(Date.now(), rep ? rep.off : 210)),
        pending: await this.pending(tid),
        problems: rep ? rep.diagProblems() : [],
      });
    }
    if (changed) await this.store.put("devices", devs);
    return { devices: out };
  }

  async apiSaveMeds(user, tid, meds) {
    const devs = await this.devices();
    const d = devs[tid];
    if (!d || !(await this.canManage(user, d))) return { ok: false, error: "دسترسی ندارید" };
    if (!Array.isArray(meds) || meds.length > 40) return { ok: false, error: "فهرست نامعتبر" };
    for (const m of meds) {
      if (!m || !String(m.name || "").trim()) return { ok: false, error: "اسم دارو خالی است" };
      if (!Array.isArray(m.times) || !m.times.length || !m.times.every((t) => TIME_RE.test(t))) {
        return { ok: false, error: "ساعت‌ها نامعتبرند" };
      }
      if (!Number.isFinite(Number(m.id))) m.id = Date.now() + Math.floor(Math.random() * 1000);
      m.id = Number(m.id);
      m.name = String(m.name).trim();
    }
    const now = Date.now();
    const current = await this.currentMeds(tid);
    const merged = mergeSince(current.meds, meds, now);
    const rep = await this.report(tid);
    const cfg = {
      v: 1,
      rev: Math.max(now, rev(await this.config(tid)) + 1, (rep ? rep.cfgRev : 0) + 1),
      meds: merged,
    };
    await this.store.put("cfg:" + tid, cfg);

    // Send it right away instead of waiting for the next cron run.
    const log = [];
    const conns = await connectAll(dial, log, this.brokers);
    let sent = 0;
    try {
      const payload = await encrypt(d.code, JSON.stringify(cfg));
      const results = await Promise.all(conns.map((c) => c.publish(configTopic(tid), payload).catch(() => false)));
      sent = results.filter(Boolean).length;
    } finally {
      await closeAll(conns);
    }
    return { ok: true, sent, meds: merged };
  }

  // ------------------------------------------------------------ one-time setup

  async setup(origin) {
    await this.store.put("origin", origin);
    const me = await this.bot.call("getMe");
    if (me.ok) await this.store.put("botname", me.result.username);
    const commands = await this.bot.call("setMyCommands", {
      commands: [
        { command: "status", description: "وضعیت داروهای امروز" },
        { command: "app", description: "مدیریت داروها" },
        { command: "add", description: "وصل کردن گوشی مامان/بابا با کد" },
        { command: "remove", description: "جدا کردن یک گوشی" },
        { command: "taken_on", description: "خبر بده وقتی دارو خورده شد" },
        { command: "taken_off", description: "فقط وقتی مشکلی بود خبر بده" },
        { command: "help", description: "راهنما" },
      ],
    });
    const menu = await this.bot.call("setChatMenuButton", {
      menu_button: { type: "web_app", text: "💊 داروها", web_app: { url: origin + "/app" } },
    });
    return { origin, bot: me.ok ? me.result.username : me.description, commands: commands.ok, menu: menu.ok };
  }
}

const PRIVATE_WELCOME =
  "سلام 👋 من بات <b>دارویار</b> هستم.\n\n" +
  "۱. پیامی را که از گوشی مامان یا بابا با «فرستادن کد» آمده، همین‌جا forward کنید.\n" +
  "۲. داروها و ساعت‌هایشان را با دکمهٔ «💊 مدیریت داروها» وارد کنید.\n" +
  "۳. اگر دارو بی‌جواب بماند، «نخوردم» بزنند یا گوشی‌شان خاموش شود، همین‌جا خبر می‌دهم.\n\n" +
  "برای اینکه همهٔ خواهر و برادرها هم خبر بگیرند، من را به گروه خانوادگی اضافه کنید و آنجا بنویسید:\n" +
  "<code>/add کد اسم</code>\n\n" +
  "/status وضعیت امروز";

const GROUP_WELCOME =
  "سلام 👋 من بات <b>دارویار</b> هستم و هشدارهای داروی مامان و بابا را اینجا می‌فرستم.\n\n" +
  "برای وصل کردن یک گوشی بنویسید:\n<code>/add کد اسم</code>\n" +
  "مثلاً: <code>/add ABCD-EFGH-JKLM-NPQR-STUV مامان</code>\n\n" +
  "/status وضعیت امروز\n/app مدیریت داروها";

