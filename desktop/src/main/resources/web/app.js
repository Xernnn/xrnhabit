// xrnhabit on the laptop: the local page ("/") and the block page ("/block?app=…" or
// "/block?site=…&back=…") that blocked apps and sites land on. Everything comes from the
// daemon's /api/state, polled every second; the engine behind it is the phone's.

const params = new URLSearchParams(location.search);
const onBlockPage = location.pathname === "/block";
const blockedApp = params.get("app");
const blockedSite = params.get("site");
const backUrl = params.get("back");

let state = null;
let apps = null; // installed desktop apps, loaded when an editor opens
let editing = null; // { blockId, apps: Set, sites: string, filter: string, error: string }
let editorNode = null;
let guard = null; // { token, until, label }
let message = "";
let failed = false;

// ---- data ----------------------------------------------------------------------------------

async function post(path, body) {
  const res = await fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Xrnhabit": "1" },
    body: JSON.stringify(body),
    keepalive: true,
  });
  return res.json().catch(() => ({}));
}

async function refresh() {
  try {
    const res = await fetch("/api/state", { cache: "no-store" });
    state = await res.json();
    failed = false;
  } catch {
    failed = true;
  }
  render();
}

async function act(body) {
  const result = await post("/api/event", body);
  if (result.token) startGuard(result.token, result.waitMs, "Starting the break");
  else if (result.error) message = result.error;
  refresh();
}

function startGuard(token, waitMs, label) {
  guard = { token, until: Date.now() + waitMs, label };
  render();
}

async function tickGuard() {
  if (!guard || Date.now() < guard.until) return;
  const { token } = guard;
  guard = null;
  const result = await post("/api/confirm", { token });
  message = result.error ?? "";
  if (!result.error) editing = null;
  refresh();
}

// Leaving the page cancels a wait, as leaving the screen does on the phone (contract §2.3).
function cancelGuard() {
  if (!guard) return;
  post("/api/confirm", { token: guard.token, cancel: true });
  guard = null;
  message = "The wait was cancelled because you left the page.";
}
document.addEventListener("visibilitychange", () => document.visibilityState === "hidden" && cancelGuard());
window.addEventListener("pagehide", cancelGuard);

// The block page stands in for the blocked app or site while you wait on it (I4).
function heartbeat() {
  if (!onBlockPage) return;
  post("/api/heartbeat", { app: blockedApp, host: blockedSite, focused: document.hasFocus() && document.visibilityState === "visible" }).catch(() => {});
}

// ---- helpers -------------------------------------------------------------------------------

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

const time = new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" });
const min = (ms) => Math.max(0, Math.round(ms / 60000));
const clock = (ms) => {
  const s = Math.max(0, Math.round(ms / 1000));
  return s >= 3600 ? `${Math.floor(s / 3600)}:${String(Math.floor((s % 3600) / 60)).padStart(2, "0")}:${String(s % 60).padStart(2, "0")}` : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};
const siteMatches = (host, target) => host === target || host.endsWith("." + target);
const appName = (id) => state?.appNames?.[id] ?? apps?.find((a) => a.id === id)?.name ?? id;

function status(b) {
  if (!b.enabled) return ["idle", "Turned off"];
  if (state.pauseRemainingMs > 0 && !b.hardcore && b.mode !== "LOCKDOWN") return ["open", `On a break for ${clock(state.pauseRemainingMs)}`];
  switch (b.mode) {
    case "HABIT":
      if (b.openUntilWall) return ["open", `Open until ${time.format(b.openUntilWall)} (${clock(b.openUntilWall - state.nowWall)} left)`];
      if (!b.blocking) return ["idle", "Not blocking now" + (b.scheduleText ? ` (${b.scheduleText})` : "")];
      if (b.state === "EARNED") return ["earned", `Earned: ${min(b.unlockMs)} min ready until ${time.format(b.creditExpiresAtWall)}`];
      return ["blocked", "Blocked until you do the habit"];
    case "ALLOWANCE":
      return b.blocking ? ["blocked", `Used up: ${min(b.allowanceUsedMs)} of ${min(b.allowanceMs)} min today`] : ["open", `${min(b.allowanceMs - b.allowanceUsedMs)} of ${min(b.allowanceMs)} min left today`];
    case "SCHEDULE":
      return b.blocking ? ["blocked", `Blocked by its schedule (${b.scheduleText ?? "all day"})`] : ["idle", `Not now (${b.scheduleText ?? "all day"})`];
    case "LOCKDOWN":
      return b.lockdownRemainingMs > 0 ? ["blocked", `Locked down for ${clock(b.lockdownRemainingMs)}`] : ["idle", `Not running (${min(b.lockdownMs)} min when started)`];
  }
  return ["idle", b.mode];
}

function how(b) {
  if (b.mode !== "HABIT") return null;
  const unlock = `opens for ${min(b.unlockMs)} min`;
  const items = b.items.length;
  if (b.trigger === "TIMER") return `Wait ${min(b.requiredMs)} min on the block page, then it ${unlock}.`;
  const need = b.requiredItems === -1 ? (items === 1 ? "the item" : `all ${items} items`) : `${b.requiredItems} of ${items} items`;
  if (b.trigger === "CHECKLIST") return `Do ${need}, then it ${unlock}.`;
  return `Wait ${min(b.requiredMs)} min ${b.combineOr ? "or" : "and"} do every item, then it ${unlock}.`;
}

// ---- views ---------------------------------------------------------------------------------

function blockCard(b) {
  const [cls, text] = status(b);
  const hasTimer = b.mode === "HABIT" && (b.trigger === "TIMER" || b.trigger === "COMBINED") && b.state === "ARMED";
  const manualOpen = b.mode === "HABIT" && b.state === "ARMED";
  const actions = [];
  if (b.state === "EARNED" && b.mode === "HABIT") {
    const capped = b.redeemRejection === "CAP_REACHED";
    actions.push(h("button", { class: "btn primary", disabled: capped, onclick: () => act({ type: "redeem", blockId: b.id }) }, `Open for ${min(b.unlockMs)} min`));
    if (capped) actions.push(h("span", { class: "note" }, `Today's ${min(state.dailyCapMs)} min of unlocks is used up.`));
  }
  if (b.mode === "LOCKDOWN" && b.lockdownRemainingMs === 0 && b.enabled) {
    actions.push(h("button", { class: "btn", onclick: () => act({ type: "lockdown", blockId: b.id }) }, `Start lockdown (${min(b.lockdownMs)} min)`));
  }
  if (!onBlockPage) {
    actions.push(h("button", { class: "btn ghost", onclick: () => openEditor(b) }, "Laptop apps & sites"));
  }
  return h("section", { class: "card block" },
    h("div", { class: "top" },
      h("h3", {}, b.name),
      h("span", { class: "chip" }, b.mode.toLowerCase()),
      b.hardcore && h("span", { class: "chip" }, "hardcore")),
    h("div", { class: `status ${cls}` }, text),
    hasTimer && h("div", {},
      h("div", { class: "progress" }, h("i", { style: `width:${Math.min(100, (b.timerMs / Math.max(1, b.requiredMs)) * 100)}%` })),
      h("div", { class: "actions", style: "margin-top:8px" },
        h("span", { class: "note" }, `${clock(b.timerMs)} of ${clock(b.requiredMs)}`),
        h("button", { class: "btn", onclick: () => act({ type: "timer", blockId: b.id, running: !b.timerRunning }) }, b.timerRunning ? "Pause" : "Start"),
        b.timerRunning && !onBlockPage && h("span", { class: "note" }, "It counts while you wait on this block's block page."))),
    b.items.length > 0 && b.mode === "HABIT" && h("div", { class: "items" }, b.items.map((i) =>
      h("label", {},
        h("input", { type: "checkbox", checked: i.done, disabled: i.linked || !manualOpen, onchange: (e) => act({ type: "toggle", itemId: i.id, done: e.target.checked }) }),
        i.text,
        i.linked && h("span", { class: "linked" }, state.habitsUrl ? h("a", { href: state.habitsUrl, target: "_blank" }, i.yesterday ? "tick it in Habits (yesterday)" : "tick it in Habits") : "ticked in xrnloops")))),
    how(b) && h("div", { class: "how" }, how(b)),
    h("div", { class: "targets" },
      b.phoneApps > 0 && h("span", { class: "target" }, `${b.phoneApps} phone app${b.phoneApps === 1 ? "" : "s"}`),
      b.sites.map((s) => h("span", { class: "target" }, s)),
      b.desktopApps.map((a) => h("span", { class: "target" }, `💻 ${appName(a)}`))),
    actions.length > 0 && h("div", { class: "actions" }, actions),
    editing?.blockId === b.id && editor(b));
}

async function openEditor(b) {
  editing = { blockId: b.id, apps: new Set(b.desktopApps), sites: b.sites.join("\n"), filter: "", error: "" };
  editorNode = null;
  if (!apps) apps = await (await fetch("/api/apps")).json();
  render();
}

function editor(b) {
  if (editorNode && editorNode.dataset.block === b.id) {
    editorNode.querySelector(".guard").replaceChildren(guardView());
    return editorNode;
  }
  const list = h("div", { class: "applist" });
  const drawList = () => {
    const q = editing.filter.toLowerCase();
    const shown = (apps ?? []).filter((a) => editing.apps.has(a.id) || !q || a.name.toLowerCase().includes(q) || a.id.toLowerCase().includes(q));
    const missing = [...editing.apps].filter((id) => !(apps ?? []).some((a) => a.id === id));
    list.replaceChildren(...[...missing.map((id) => ({ id, name: id })), ...shown].slice(0, 200).map((a) =>
      h("label", {},
        h("input", { type: "checkbox", checked: editing.apps.has(a.id), onchange: (e) => { e.target.checked ? editing.apps.add(a.id) : editing.apps.delete(a.id); } }),
        a.name, h("small", {}, a.id))));
  };
  drawList();
  const search = h("input", { type: "search", placeholder: "Find an app", oninput: (e) => { editing.filter = e.target.value; drawList(); } });
  const sites = h("textarea", { value: editing.sites, placeholder: "youtube.com\nreddit.com", oninput: (e) => (editing.sites = e.target.value) });
  const errorBox = h("div", { class: "error" }, editing.error);
  editorNode = h("div", { class: "editor", "data-block": b.id },
    h("div", { class: "note" }, "Laptop apps this block closes"),
    search, list,
    h("div", { class: "note" }, "Websites (one per line; each covers its subdomains). The phone blocks these too."),
    sites,
    errorBox,
    h("div", { class: "guard" }, guardView()),
    h("div", { class: "actions" },
      h("button", {
        class: "btn primary",
        onclick: async () => {
          const result = await post("/api/laptop", { blockId: b.id, desktopApps: [...editing.apps], sites: sites.value.split(/[\s,]+/).filter(Boolean) });
          if (result.error) errorBox.textContent = result.error;
          else if (result.token) startGuard(result.token, result.waitMs, "Saving (it makes the block easier to get past)");
          else { editing = null; editorNode = null; refresh(); }
        },
      }, "Save"),
      h("button", { class: "btn ghost", onclick: () => { cancelGuard(); message = ""; editing = null; editorNode = null; render(); } }, "Cancel")));
  return editorNode;
}

function guardView() {
  if (!guard) return "";
  return h("div", { class: "alert" }, h("b", {}, `${guard.label}…`), `Wait ${Math.ceil((guard.until - Date.now()) / 1000)} s and keep this page open. Leaving it cancels.`);
}

function alerts() {
  const out = [];
  if (failed) out.push(["The laptop app isn't answering", "Is xrnhabit-desktop running? systemctl --user status xrnhabit-desktop"]);
  if (!state) return out;
  if (state.kwinSeenAgoMs == null) out.push(["Desktop apps aren't being watched", "The KWin script hasn't reported in. Run desktop/install.sh again, or turn on \"xrnhabit\" in System Settings → Window Management → KWin Scripts."]);
  if (state.extensionMissing) out.push(["Website blocking is off in this browser", "The xrnhabit extension isn't answering. Turn it on in the browser's extensions page."]);
  if (state.syncConfigured && state.sync && !state.sync.ok) out.push(["Not syncing with the phone", state.sync.message]);
  if (!state.syncConfigured) out.push(["Not syncing", `Fill in ~/.config/xrnhabit/desktop.properties to share blocks and unlocks with the phone.`]);
  return out;
}

function render() {
  const app = document.getElementById("app");
  const scrollY = window.scrollY;
  const header = h("header", { class: "bar" }, h("div", { class: "wrap" },
    h("h1", {}, h("a", { href: "/", style: "display:contents;color:inherit" }, h("img", { src: "/icon.svg", alt: "" }), "xrnhabit", h("small", {}, "laptop"))),
    h("span", { class: "spacer" }),
    state && h("span", { class: "dot" + (!state.syncConfigured ? " idle" : state.sync?.ok === false ? " off" : "") },
      !state.syncConfigured ? "Local only" : state.sync?.ok === false ? "Offline" : "Synced"),
    state?.habitsUrl && h("a", { href: state.habitsUrl, target: "_blank" }, "Habits")));

  if (!state) {
    app.replaceChildren(header, h("main", { class: "wrap" }, alerts().map(([t, d]) => h("div", { class: "alert" }, h("b", {}, t), d))));
    return;
  }

  let blocks = state.blocks;
  let hero = null;
  if (onBlockPage) {
    blocks = blocks.filter((b) => (blockedApp && b.desktopApps.includes(blockedApp)) || (blockedSite && b.sites.some((s) => siteMatches(blockedSite, s))));
    const still = blockedApp ? state.blockedApps.includes(blockedApp) : state.blockedSites.some((s) => siteMatches(blockedSite ?? "", s));
    const what = blockedApp ? appName(blockedApp) : blockedSite;
    if (!still && backUrl && /^https?:/.test(backUrl)) {
      location.replace(backUrl);
      return;
    }
    hero = h("section", { class: "card hero" + (still ? "" : " open") },
      h("h2", {}, still ? `${what} is blocked` : `${what} is open`),
      h("p", {}, still ? "Here's what opens it. You can wait on this page: a timer habit counts here." : blockedApp ? "You can start it again." : "Go back to it."));
    document.title = still ? `${what} is blocked · xrnhabit` : "xrnhabit";
  }

  const pause = state.pauseRemainingMs > 0
    ? h("div", { class: "actions" }, h("span", { class: "note" }, `On a break: ${clock(state.pauseRemainingMs)} left`), h("button", { class: "btn", onclick: () => act({ type: "endPause" }) }, "End it now"))
    : h("button", { class: "btn", disabled: !state.canPause || !!guard, title: state.canPause ? "Lifts SOFT blocks for 15 minutes, once a day" : "Today's break is used", onclick: () => act({ type: "pause" }) }, state.canPause ? "Take a 15-min break" : "Break used today");

  app.replaceChildren(header, h("main", { class: "wrap" },
    alerts().map(([t, d]) => h("div", { class: "alert" }, h("b", {}, t), d)),
    hero,
    message && h("div", { class: "alert" }, message),
    guard && !editing && guardView(),
    !onBlockPage && h("section", { class: "card summary" },
      h("div", { class: "stat" }, h("b", {}, `${min(state.unlockUsedMs)} / ${min(state.dailyCapMs)} min`), h("span", {}, "Unlocked today (phone and laptop)")),
      h("span", { class: "spacer" }),
      pause),
    blocks.length ? blocks.map(blockCard) : h("section", { class: "card note" }, onBlockPage ? "No block covers this any more." : "No blocks yet. Make them in xrnhabit on the phone; they arrive here with the next sync."),
    h("div", { class: "foot" },
      onBlockPage ? h("a", { href: "/" }, "All blocks") : h("span", {}, state.loop?.ok === false ? `Habits: ${state.loop.message}` : ""),
      h("span", {}, state.sync ? `${state.sync.ok ? "Last sync" : "Last try"} ${time.format(state.sync.atWallMs)}` : ""))));
  window.scrollTo(0, scrollY);
}

refresh();
setInterval(() => {
  refresh();
  tickGuard();
}, 1000);
setInterval(heartbeat, 2000);
heartbeat();
window.addEventListener("focus", heartbeat);
window.addEventListener("blur", heartbeat);
