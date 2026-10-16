// xrnhabit status page: a read-only view of the state the phone and the laptop share
// (docs/domain-contract.md §2.4), kept live with a long-poll. It shares its login with the
// xrnloops web app on the same server (same origin, same localStorage key).

const KEY_AUTH = "xrnloops.auth";
const DAY_MS = 86400000;

let auth = load();
let doc = null;
let etag = "";
let online = true;
let updated = { at: "", by: "" };
let devices = {}; // device -> epoch ms of its last check-in

function load() {
  try {
    return JSON.parse(localStorage.getItem(KEY_AUTH));
  } catch {
    return null;
  }
}

function save(value) {
  try {
    if (value) localStorage.setItem(KEY_AUTH, JSON.stringify(value));
    else localStorage.removeItem(KEY_AUTH);
  } catch {
    /* private window: works for this visit only */
  }
}

function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else if (k in el) el[k] = v;
    else el.setAttribute(k, v);
  }
  for (const c of children.flat(Infinity)) if (c != null && c !== false) el.append(c instanceof Node ? c : String(c));
  return el;
}

class AuthError extends Error {}

/** The server's answer to too many wrong passwords from this address. */
const LOCKED_OUT = "Too many wrong passwords from here. Wait 15 minutes, then try again.";

function check(res) {
  if (res.status === 401) throw new AuthError();
  if (res.status === 429) throw new Error(LOCKED_OUT);
}

async function fetchState(wait) {
  const headers = { Authorization: auth, "X-Xrn-Client": "web" };
  if (wait && etag) headers["If-None-Match"] = etag;
  const res = await fetch(new URL(wait ? `state?wait=${wait}` : "state", location.href), { headers, cache: "no-store" });
  check(res);
  if (res.status === 304) return false;
  if (!res.ok) throw new Error(`${res.status}`);
  doc = await res.json();
  etag = res.headers.get("ETag") ?? "";
  updated = { at: res.headers.get("X-Updated") ?? "", by: res.headers.get("X-Updated-By") ?? "" };
  return true;
}

/** When the phone and the laptop last checked in; refreshed with each redraw. */
async function fetchDevices() {
  try {
    const res = await fetch(new URL("devices", location.href), { headers: { Authorization: auth, "X-Xrn-Client": "web" }, cache: "no-store" });
    check(res);
    if (res.ok) devices = await res.json();
  } catch {
    /* the state still shows; the next redraw tries again */
  }
}

async function live(gen) {
  while (gen === generation) {
    try {
      if (await fetchState(25)) render();
      setOnline(true);
    } catch (e) {
      if (e instanceof AuthError) return signOut("Your password was refused. Sign in again.");
      setOnline(false);
      await new Promise((r) => setTimeout(r, 5000));
    }
  }
}

let generation = 0;

function setOnline(value) {
  if (online === value) return;
  online = value;
  render();
}

async function start() {
  const gen = ++generation;
  await fetchState(0);
  await fetchDevices();
  render();
  live(gen);
}

function signOut(message) {
  generation++;
  auth = null;
  save(null);
  loginView(message);
}

function loginView(message = "") {
  const user = h("input", { name: "user", value: "sonn", autocomplete: "username", required: true });
  const password = h("input", { name: "password", type: "password", autocomplete: "current-password", required: true });
  const error = h("div", { class: "error" }, message);
  const form = h("form", {
    onsubmit: async (e) => {
      e.preventDefault();
      auth = "Basic " + btoa(unescape(encodeURIComponent(`${user.value.trim()}:${password.value}`)));
      try {
        await start();
        save(auth);
      } catch (err) {
        auth = null;
        error.textContent = err instanceof AuthError ? "Wrong user name or password." : err.message === LOCKED_OUT ? LOCKED_OUT : `Can't reach the server (${err.message}).`;
      }
    },
  }, h("label", {}, "User", user), h("label", {}, "Password", password), error, h("button", { class: "btn", type: "submit" }, "Sign in"));
  document.getElementById("app").replaceChildren(h("div", { class: "wrap login" },
    h("h1", {}, h("img", { src: "icon.svg", alt: "", width: 32, height: 32 }), "xrnhabit"),
    h("p", {}, "What your phone and laptop are blocking. Sign in with the home server's password."),
    form));
  setTimeout(() => password.focus(), 0);
}

// ---- time ----------------------------------------------------------------------------------

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
const whenFmt = new Intl.DateTimeFormat(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });

function localDayStartMs(now = new Date()) {
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
}

function epochDay(now = new Date()) {
  return Date.UTC(now.getFullYear(), now.getMonth(), now.getDate()) / DAY_MS;
}

const minutes = (ms) => Math.round(ms / 60000);

function ago(ms) {
  const m = Math.floor(ms / 60000);
  if (m < 1) return "just now";
  if (m < 60) return `${m} min ago`;
  if (m < 48 * 60) return `${Math.floor(m / 60)} h ago`;
  return `${Math.floor(m / 1440)} days ago`;
}

/** Each device's last check-in. The phone checks in while its screen is on, the laptop while it's awake. */
function devicesCard(now) {
  const names = { phone: "Phone", laptop: "Laptop" };
  const known = Object.entries(devices).sort(([a], [b]) => a.localeCompare(b));
  if (!known.length) return null;
  return h("section", { class: "card devices" }, known.map(([device, at]) => {
    const stale = now.getTime() - at > 60 * 60000;
    return h("div", { class: "device" + (stale ? " stale" : ""), title: stale ? "Not heard from in over an hour" : "" },
      h("b", {}, names[device] ?? device), h("span", {}, `synced ${ago(now.getTime() - at)}`));
  }));
}

/** BlockEngine.scheduleActive (I20): a window past midnight belongs to the day it starts on. */
function scheduleActive(schedule, now = new Date()) {
  const days = schedule?.activeDays ?? [1, 2, 3, 4, 5, 6, 7];
  const iso = ((now.getDay() + 6) % 7) + 1;
  const w = schedule?.activeWindow;
  if (!w) return days.includes(iso);
  const minute = now.getHours() * 60 + now.getMinutes();
  if (w.startMinuteOfDay <= w.endMinuteOfDay) return days.includes(iso) && minute >= w.startMinuteOfDay && minute < w.endMinuteOfDay;
  if (minute >= w.startMinuteOfDay) return days.includes(iso);
  if (minute < w.endMinuteOfDay) return days.includes(iso === 1 ? 7 : iso - 1);
  return false;
}

function hhmm(minuteOfDay) {
  return `${String(Math.floor(minuteOfDay / 60)).padStart(2, "0")}:${String(minuteOfDay % 60).padStart(2, "0")}`;
}

// ---- rendering -----------------------------------------------------------------------------

function blockStatus(block, now) {
  const wall = now.getTime();
  const mode = block.mode ?? "HABIT";
  const session = doc.sessions?.[block.id];
  const credit = doc.credits?.[block.id];
  const pause = doc.pause;
  const onBreak = pause && wall < pause.startedAtWall + pause.durationMs && (block.enforcementTier ?? "SOFT") === "SOFT" && mode !== "LOCKDOWN";
  if (block.enabled === false) return ["idle", "Turned off"];
  if (onBreak) return ["open", `On a break until ${timeFmt.format(pause.startedAtWall + pause.durationMs)}`];
  switch (mode) {
    case "HABIT": {
      if (session && wall < session.startedAtWall + session.durationMs) {
        return ["open", `Open until ${timeFmt.format(session.startedAtWall + session.durationMs)}`];
      }
      if (!scheduleActive(block.schedule, now)) return ["idle", "Outside its hours"];
      if (credit && wall < credit.expiresAtWall) {
        return ["earned", `Earned · ${minutes(credit.amountMs)} min ready, until ${timeFmt.format(credit.expiresAtWall)}`];
      }
      return ["blocked", "Blocked until you do the habit"];
    }
    case "ALLOWANCE": {
      const today = localDayStartMs(now);
      const used = Object.values(doc.allowance?.[block.id] ?? {}).reduce((sum, u) => sum + (u.dayStartWall === today ? u.ms : 0), 0);
      const allowance = block.dailyAllowanceMs ?? 0;
      if (!scheduleActive(block.schedule, now)) return ["idle", "Outside its hours"];
      return used >= allowance
        ? ["blocked", `Used up: ${minutes(used)} of ${minutes(allowance)} min today`]
        : ["open", `${minutes(allowance - used)} of ${minutes(allowance)} min left today`];
    }
    case "SCHEDULE":
      return scheduleActive(block.schedule, now) ? ["blocked", "Blocked by its schedule"] : ["idle", "Outside its hours"];
    case "LOCKDOWN": {
      const l = doc.lockdowns?.[block.id];
      return l && wall < l.startedAtWall + l.durationMs
        ? ["blocked", `Locked down until ${timeFmt.format(l.startedAtWall + l.durationMs)}`]
        : ["idle", `Not running (${minutes(block.lockdownMs ?? 0)} min when started)`];
    }
  }
  return ["idle", mode];
}

function unlockMethod(block, items) {
  const mode = block.mode ?? "HABIT";
  if (mode === "ALLOWANCE") return `${minutes(block.dailyAllowanceMs ?? 0)} min a day`;
  if (mode === "SCHEDULE") {
    const w = block.schedule?.activeWindow;
    return w ? `Blocked ${hhmm(w.startMinuteOfDay)}–${hhmm(w.endMinuteOfDay)}` : "Blocked all day on its days";
  }
  if (mode === "LOCKDOWN") return "Blocked for a while once started";
  const t = block.trigger ?? {};
  const reward = `opens for ${minutes(block.reward?.unlockDurationMs ?? 300000)} min`;
  const timer = `${minutes(t.requiredAmount ?? 0)} min timer`;
  if (t.type === "TIMER") return `${timer}, ${reward}`;
  const count = t.requiredAmount === -1 || t.requiredAmount == null ? "all" : t.requiredAmount;
  if (t.type === "CHECKLIST") return `${count === "all" ? "Every" : count} item${count === 1 ? "" : "s"} of ${items.length}, ${reward}`;
  return `${timer} ${t.combineMode === "OR" ? "or" : "and"} every item, ${reward}`;
}

function render() {
  if (!doc) return;
  const now = new Date();
  const blocks = Object.values(doc.blocks ?? {});
  const items = Object.values(doc.items ?? {});
  const today = epochDay(now);
  const usedMs = doc.unlockEpochDay === today ? Object.values(doc.unlockLog ?? {}).reduce((a, b) => a + b, 0) : 0;
  const cap = doc.settings?.dailyCapMs ?? 3600000;
  const pause = doc.pause;
  const onBreak = pause && now.getTime() < pause.startedAtWall + pause.durationMs;
  const breakText = onBreak ? `Until ${timeFmt.format(pause.startedAtWall + pause.durationMs)}` : doc.lastPauseEpochDay === today ? "Used today" : "Available";

  const cards = blocks.map((block) => {
    const own = items.filter((i) => i.blockId === block.id).sort((a, b) => a.order - b.order);
    const [cls, text] = blockStatus(block, now);
    const phone = block.targetPackages ?? [];
    const sites = block.targetSites ?? [];
    const laptop = block.targetDesktopApps ?? [];
    return h("section", { class: "card block" },
      h("div", { class: "top" },
        h("h2", {}, block.name),
        h("span", { class: "chip" }, (block.mode ?? "HABIT").toLowerCase()),
        (block.enforcementTier ?? "SOFT") === "HARDCORE" && h("span", { class: "chip" }, "hardcore")),
      h("div", { class: `status ${cls}` }, text),
      h("div", { class: "targets" },
        phone.length > 0 && h("span", { class: "target", title: phone.join("\n") }, `${phone.length} phone app${phone.length === 1 ? "" : "s"}`),
        sites.map((s) => h("span", { class: "target" }, s)),
        laptop.map((a) => h("span", { class: "target", title: "Laptop app" }, `💻 ${a}`))),
      h("div", { class: "how" }, unlockMethod(block, own)),
      own.length > 0 && (block.mode ?? "HABIT") === "HABIT" && h("ul", { class: "items" }, own.map((i) =>
        h("li", { class: i.done && !i.externalHabitId ? "done" : "" }, i.text,
          i.externalHabitId && h("span", { class: "link" }, i.linkedDay === "YESTERDAY" ? "Loop · yesterday" : "Loop")))));
  });

  const updatedText = updated.at ? `Updated ${whenFmt.format(new Date(updated.at))}${updated.by ? ` by the ${updated.by}` : ""}` : "Nothing synced yet";
  document.getElementById("app").replaceChildren(
    h("header", { class: "bar" }, h("div", { class: "wrap" },
      h("h1", {}, h("img", { src: "icon.svg", alt: "" }), "xrnhabit"),
      h("span", { class: "spacer" }),
      h("span", { class: "live" + (online ? "" : " off") }, online ? "Live" : "Offline"),
      h("a", { href: "../xrnloops/" }, "Habits"))),
    h("main", { class: "wrap" },
      h("section", { class: "card summary" },
        h("div", {}, h("b", {}, `${minutes(usedMs)} / ${minutes(cap)} min`), h("span", {}, "Unlocked today"),
          h("div", { class: "meter" }, h("i", { style: `width:${Math.min(100, (usedMs / cap) * 100)}%` }))),
        h("div", {}, h("b", {}, breakText), h("span", {}, "15-minute break")),
        h("div", {}, h("b", {}, blocks.length), h("span", {}, blocks.length === 1 ? "Block" : "Blocks"))),
      devicesCard(now),
      blocks.length ? cards : h("div", { class: "card empty" }, "No blocks yet. Set them up in xrnhabit on the phone; they show up here after its next sync."),
      h("div", { class: "foot" },
        h("span", {}, updatedText),
        h("a", { href: "#", onclick: (e) => { e.preventDefault(); signOut(""); } }, "Sign out"))));
}

// Statuses change with the clock alone (sessions end, schedules start): redraw each half minute.
setInterval(async () => {
  if (auth) await fetchDevices();
  render();
}, 30000);

if (auth) {
  start().catch((e) => (e instanceof AuthError ? signOut("Your password was refused. Sign in again.") : loginView(e.message === LOCKED_OUT ? LOCKED_OUT : `Can't reach the server (${e.message}).`)));
} else {
  loginView();
}
