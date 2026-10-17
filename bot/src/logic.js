// Alert logic, ported from the Android caregiver side (Care.kt) so the bot
// and the app raise the same alerts. Times are shown in the parent phone's
// local time (Tehran), using the UTC offset the phone reports.

export const PENDING = "pending", TAKEN = "taken", SKIPPED = "skipped", MISSED = "missed";
const MIN = 60_000;

// ---------------------------------------------------------------- Persian text

export function faDigits(s) {
  return String(s).replace(/[0-9]/g, (d) => "۰۱۲۳۴۵۶۷۸۹"[d]);
}

/** "08:05" -> "۸:۰۵" */
export function faTime(t) {
  const [h, m] = String(t).split(":");
  return faDigits(`${Number(h)}:${m}`);
}

export function escapeHtml(s) {
  return String(s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]);
}

export function ago(ms, now = Date.now()) {
  if (!ms) return "هرگز";
  const min = Math.floor((now - ms) / MIN);
  if (min < 1) return "همین الان";
  if (min < 60) return faDigits(min) + " دقیقه پیش";
  if (min < 60 * 24) return faDigits(Math.floor(min / 60)) + " ساعت پیش";
  return faDigits(Math.floor(min / (60 * 24))) + " روز پیش";
}

// ---------------------------------------------------------------- dates in a fixed offset

export function dateOf(ms, offMin) {
  return new Date(ms + offMin * MIN).toISOString().slice(0, 10);
}

export function clockOf(ms, offMin) {
  return new Date(ms + offMin * MIN).toISOString().slice(11, 16);
}

export function addDays(date, n) {
  const d = new Date(date + "T12:00:00Z");
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

export function dueAt(date, time, offMin) {
  return Date.parse(`${date}T${time}:00Z`) - offMin * MIN;
}

// ---------------------------------------------------------------- reports

export class Report {
  constructor(json) {
    this.json = json;
    this.sentAt = Number(json.sentAt || 0);
    this.off = Number.isFinite(json.tzOff) ? json.tzOff : 210;
    this.grace = Number(json.grace || 30);
    this.cfgRev = Number(json.cfgRev || 0);
    this.version = json.ver || "";
    this.meds = Array.isArray(json.meds) ? json.meds : [];
    this.events = Array.isArray(json.events) ? json.events : [];
    this.diag = json.diag || {};
  }

  today(now = Date.now()) {
    return dateOf(now, this.off);
  }

  times() {
    return [...new Set(this.meds.filter((m) => m.active !== false).flatMap((m) => m.times || []))].sort();
  }

  due(date, time) {
    return dueAt(date, time, this.off);
  }

  medsAt(date, time) {
    const d = this.due(date, time);
    return this.meds.filter((m) => m.active !== false && (m.times || []).includes(time) && d >= (m.since || 0) - MIN);
  }

  slotEvents(date, time) {
    return this.events.filter((e) => e.d === date && e.t === time);
  }

  symbol(date, time, now = Date.now()) {
    const evs = this.slotEvents(date, time);
    const d = this.due(date, time);
    if (evs.length) {
      if (evs.every((e) => e.s === TAKEN)) return "✅";
      if (evs.some((e) => e.s === SKIPPED)) return "❌";
      if (evs.some((e) => e.s === MISSED)) return "⚠️";
      return now < d + this.grace * MIN ? "⏳" : "❓";
    }
    if (!this.medsAt(date, time).length) return "";
    return d > now ? "▫️" : "❓";
  }

  todayLine(now = Date.now()) {
    const today = this.today(now);
    const parts = this.times()
      .map((t) => [t, this.symbol(today, t, now)])
      .filter(([, s]) => s)
      .map(([t, s]) => `${faTime(t)} ${s}`);
    return parts.length ? parts.join("   ") : "امروز دارویی ثبت نشده";
  }

  diagProblems() {
    const d = this.diag, p = [];
    if (d.notif === false) p.push("اجازهٔ اعلان خاموش است");
    if (d.exact === false) p.push("آلارم دقیق مجاز نیست");
    if (d.fsi === false) p.push("نمایش تمام‌صفحه مجاز نیست");
    if (d.overlay === false) p.push("«نمایش روی برنامه‌های دیگر» مجاز نیست");
    if (d.battery === false) p.push("بهینه‌سازی باتری روی برنامه فعال است");
    if (d.tz === false) p.push("ساعت یا منطقهٔ زمانی گوشی مشکل دارد");
    return p;
  }
}

export function medLabel(m) {
  return m.dose ? `${m.name} — ${m.dose}` : m.name;
}

function evLabel(e) {
  return e.q ? `${e.n} — ${e.q}` : e.n;
}

// ---------------------------------------------------------------- alerts

/**
 * Returns new alerts for one parent phone: [{ key, text, optional }].
 * `optional` alerts ("took it") go only to chats that asked for them.
 * `alerted` (a Set of keys) is updated in place.
 */
export function evaluate({ label, topic, pairedAt }, prev, cur, alerted, now = Date.now()) {
  if (!cur) return [];
  const out = [];
  const name = escapeHtml(label);
  const fire = (key, text, optional = false) => {
    if (alerted.has(key)) return;
    alerted.add(key);
    out.push({ key, text, optional });
  };
  const list = (evs) => evs.map((e) => "• " + escapeHtml(evLabel(e))).join("\n");

  // 1) answers and missed doses reported by the parent phone
  const bySlot = new Map();
  for (const e of cur.events) {
    const k = `${e.d}T${e.t}`;
    if (!bySlot.has(k)) bySlot.set(k, []);
    bySlot.get(k).push(e);
  }
  for (const [slot, evs] of bySlot) {
    if ((evs[0].due || 0) < pairedAt) continue;
    const t = faTime(evs[0].t);
    const skipped = evs.filter((e) => e.s === SKIPPED);
    if (skipped.length) {
      const at = Math.max(...skipped.map((e) => e.sa || 0));
      fire(`skip|${topic}|${slot}`,
        `❌ <b>${name}</b> زد «نخوردم»\nداروی ساعت ${t}:\n${list(skipped)}\n(جواب ساعت ${faTime(clockOf(at, cur.off))})`);
    }
    const missed = evs.filter((e) => e.s === MISSED);
    if (missed.length) {
      fire(`miss|${topic}|${slot}`,
        `⚠️ <b>${name}</b>: داروی ساعت ${t} بی‌جواب ماند\n${list(missed)}\nتا ${faDigits(cur.grace)} دقیقه بعد از زنگ، هیچ دکمه‌ای زده نشد.`);
    }
    if (evs.every((e) => e.s === TAKEN)) {
      const at = Math.max(...evs.map((e) => e.ta || 0));
      const was = ["miss", "none", "skip"].some((k) => alerted.has(`${k}|${topic}|${slot}`));
      if (was) {
        fire(`late|${topic}|${slot}`, `✅ <b>${name}</b>: داروی ساعت ${t} خورده شد\nدیرتر تأیید شد، ساعت ${faTime(clockOf(at, cur.off))}.`);
      } else if (now - at < 12 * 60 * MIN) {
        fire(`ok|${topic}|${slot}`, `✅ <b>${name}</b> داروی ساعت ${t} را خورد\n${list(evs)}`, true);
      }
    }
  }

  // 2) dead-man switch: a dose is overdue and nothing at all is known about it
  const today = cur.today(now);
  for (const date of [addDays(today, -1), today]) {
    for (const time of cur.times()) {
      const due = cur.due(date, time);
      if (due < pairedAt || due > now) continue;
      if (now < due + (cur.grace + 30) * MIN || now - due > 20 * 60 * MIN) continue;
      if (!cur.medsAt(date, time).length) continue;
      const evs = cur.slotEvents(date, time);
      if (evs.length && !evs.some((e) => e.s === PENDING)) continue;
      fire(`none|${topic}|${date}T${time}`,
        `❓ <b>${name}</b>: از گوشی خبری نرسیده\nداروی ساعت ${faTime(time)} هنوز تأیید نشده و آخرین خبر از گوشی ${ago(cur.sentAt, now)} بوده. ممکن است گوشی خاموش باشد یا اینترنت قطع باشد.`);
    }
  }

  // 3) long silence
  const silentHours = Math.floor((now - cur.sentAt) / (60 * MIN));
  if (silentHours >= 6) {
    fire(`silent|${topic}|${cur.sentAt}`,
      `📵 <b>${name}</b>: ${faDigits(silentHours)} ساعت است خبری نیست\nممکن است گوشی خاموش باشد، شارژش تمام شده باشد یا اینترنت قطع باشد. زنگ دارو روی خود گوشی بدون اینترنت هم کار می‌کند.`);
  }

  // 4) back online after a long gap
  if (prev && cur.sentAt - prev.sentAt >= 6 * 60 * MIN) {
    fire(`back|${topic}|${cur.sentAt}`, `🔄 <b>${name}</b> دوباره وصل شد\nامروز: ${cur.todayLine(now)}`);
  }

  // 5) settings on the parent phone that will break the alarm
  const probs = cur.diagProblems();
  if (probs.length) {
    fire(`diag|${topic}|${probs.join("")}`,
      `🛠 <b>${name}</b>: تنظیمات گوشی ناقص است\n${probs.map((p) => "• " + p).join("\n")}\nروی گوشی، برنامه را باز کنند و «بررسی تنظیمات» را بزنند.`);
  }
  return out;
}

/** Status text for /status. */
export function statusText(label, rep, pending, now = Date.now()) {
  const name = `👤 <b>${escapeHtml(label)}</b>`;
  if (!rep) return `${name}\nهنوز گزارشی نرسیده. گوشی آن‌ها باید یک بار به اینترنت وصل شود.`;
  const lines = [name, `آخرین خبر: ${ago(rep.sentAt, now)}`, `امروز: ${rep.todayLine(now)}`];
  if (!rep.meds.length) lines.push("💊 هنوز دارویی ثبت نشده");
  if (pending) lines.push("⏳ تغییرات داروها هنوز به گوشی نرسیده");
  const probs = rep.diagProblems();
  if (probs.length) lines.push("🛠 " + probs.join("، "));
  return lines.join("\n");
}

/** Keeps a medicine's "since" unless its times changed (same rule as the app). */
export function mergeSince(oldMeds, newMeds, now = Date.now()) {
  const byId = new Map(oldMeds.map((m) => [m.id, m]));
  return newMeds.map((m) => {
    const old = byId.get(m.id);
    const times = [...new Set(m.times)].sort();
    const same = old && JSON.stringify([...(old.times || [])].sort()) === JSON.stringify(times);
    return {
      id: m.id,
      name: String(m.name).slice(0, 80),
      dose: String(m.dose || "").slice(0, 60),
      times,
      since: same ? old.since || 0 : now,
      active: true,
    };
  });
}
