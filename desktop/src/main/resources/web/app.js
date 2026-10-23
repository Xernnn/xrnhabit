// xrnhabit on the laptop: the local page ("/") and the block page ("/block?app=…" or
// "/block?site=…&back=…") that blocked apps and sites land on. Everything comes from the
// daemon's /api/state, polled every second; the engine behind it is the phone's.

const params = new URLSearchParams(location.search);
const onBlockPage = location.pathname === "/block";
const blockedApp = params.get("app");
const blockedSite = params.get("site");
const backUrl = params.get("back");

let state = null;
let apps = null; // installed desktop apps, loaded when the form opens
let habits = null; // [{uuid, name}] to link tasks to, or {error}; loaded when the form opens
let form = null; // the block form: { blockId (null: a new block), data, apps: Set, sitesText, filter, error }
let formNode = null; // kept across redraws, so typing survives the once-a-second refresh
let guard = null; // { token, until, label, close }: a loosening waiting out its 60 s
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

function startGuard(token, waitMs, label, close = true) {
  guard = { token, until: Date.now() + waitMs, label, close };
  render();
}

async function tickGuard() {
  if (!guard || Date.now() < guard.until) return;
  const { token, close } = guard;
  guard = null;
  const result = await post("/api/confirm", { token });
  message = result.error ?? "";
  if (!result.error && close) closeForm();
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
  if (!onBlockPage && form?.blockId !== b.id) {
    actions.push(h("button", { class: "btn ghost", onclick: () => openForm(b) }, "Edit"));
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
        h("input", { type: "checkbox", checked: i.done || i.used, disabled: i.linked || !manualOpen, onchange: (e) => act({ type: "toggle", itemId: i.id, done: e.target.checked }) }),
        i.text,
        i.linked && (i.used
          ? h("span", { class: "linked" }, i.yesterday ? "done yesterday; that tick already opened this block" : "done; that tick already opened this block today")
          : h("span", { class: "linked" }, state.habitsUrl ? h("a", { href: state.habitsUrl, target: "_blank" }, i.yesterday ? "tick it in Habits (yesterday)" : "tick it in Habits") : "ticked in xrnloops"))))),
    how(b) && h("div", { class: "how" }, how(b)),
    h("div", { class: "targets" },
      b.phoneApps > 0 && h("span", { class: "target" }, `${b.phoneApps} phone app${b.phoneApps === 1 ? "" : "s"}`),
      b.sites.map((s) => h("span", { class: "target" }, s)),
      b.desktopApps.map((a) => h("span", { class: "target" }, `💻 ${appName(a)}`))),
    actions.length > 0 && h("div", { class: "actions" }, actions),
    form?.blockId === b.id && formView(b));
}

// ---- the block form ------------------------------------------------------------------------
// The phone's create screen, on the laptop (BlockForms.kt has the rules). A new block targets
// laptop apps and websites; phone apps are added on the phone. An edit keeps the block's kind
// and unlock method; anything that loosens it waits 60 s with the page open.

const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const hhmm = (m) => `${String(Math.floor(m / 60)).padStart(2, "0")}:${String(m % 60).padStart(2, "0")}`;
const minutesOf = (text) => {
  const [hh, mm] = text.split(":").map(Number);
  return (hh || 0) * 60 + (mm || 0);
};

async function openForm(b) {
  cancelGuard();
  message = "";
  const base = { checklist: true, timer: false, combineOr: false, timerMin: 30, unlockMin: 5, allowanceMin: 30, lockdownMin: 60, days: [1, 2, 3, 4, 5], startMin: 540, endMin: 1020 };
  form = b
    ? {
      blockId: b.id,
      data: {
        ...base,
        name: b.name,
        mode: b.mode,
        timerMin: min(b.requiredMs) || 30,
        combineOr: b.combineOr,
        unlockMin: min(b.unlockMs),
        allowanceMin: min(b.allowanceMs) || 30,
        lockdownMin: min(b.lockdownMs) || 60,
        days: b.scheduleDays.length ? b.scheduleDays : base.days,
        startMin: b.scheduleStart ?? base.startMin,
        endMin: b.scheduleEnd ?? base.endMin,
        newTask: "",
      },
      apps: new Set(b.desktopApps), sitesText: b.sites.join("\n"), filter: "", error: "",
    }
    : { blockId: null, data: { ...base, name: "", kind: "HABIT", setTime: "SCHEDULE", itemsText: "", linked: new Map() }, apps: new Set(), sitesText: "", filter: "", error: "" };
  formNode = null;
  render();
  const loads = [];
  if (!apps) loads.push(fetch("/api/apps").then((r) => r.json()).then((list) => (apps = list)));
  if (!habits && state?.syncConfigured) loads.push(fetch("/api/habits").then((r) => r.json()).then((list) => (habits = list)).catch(() => (habits = { error: "Can't list your habits." })));
  await Promise.allSettled(loads);
  if (form) rebuildForm();
}

function closeForm() {
  form = null;
  formNode = null;
  render();
}

/** The mode the form is about: the block's own, or the one picked for a new block. */
function formMode() {
  const d = form.data;
  if (form.blockId) return d.mode;
  return d.kind === "TIME" ? d.setTime : d.kind;
}

function formView(b) {
  if (!formNode) formNode = buildForm(b);
  formNode.querySelector(".guard").replaceChildren(guardView());
  const howNote = formNode.querySelector(".form-how");
  if (howNote && b && howNote.textContent !== (how(b) ?? "")) howNote.textContent = how(b) ?? "";
  const list = formNode.querySelector(".form-items");
  if (list && b) {
    const key = JSON.stringify(b.items);
    if (list.dataset.key !== key) {
      list.dataset.key = key;
      list.replaceChildren(...itemRows(b));
    }
  }
  return formNode;
}

function rebuildForm() {
  if (!form) return;
  const old = formNode;
  formNode = null;
  if (old?.isConnected) old.replaceWith(formView(state?.blocks.find((x) => x.id === form.blockId)));
  else render();
}

function buildForm(b) {
  const d = form.data;
  const isNew = !form.blockId;
  const mode = formMode();
  const errorBox = h("div", { class: "error" }, form.error);
  const number = (key, attrs = {}) => h("input", { type: "number", min: 1, step: 1, value: d[key], oninput: (e) => (d[key] = Number(e.target.value)), ...attrs });
  const field = (label, input, hint) => h("label", { class: "field" }, h("span", {}, label), input, hint && h("small", {}, hint));
  const check = (key, label) => h("label", { class: "check" }, h("input", { type: "checkbox", checked: d[key], onchange: (e) => { d[key] = e.target.checked; rebuildForm(); } }), label);
  const seg = (key, options) => h("div", { class: "seg" }, options.map(([value, label]) =>
    h("button", { type: "button", class: d[key] === value ? "on" : "", onclick: () => { d[key] = value; rebuildForm(); } }, label)));

  // What to block
  const list = h("div", { class: "applist" });
  const drawList = () => {
    const q = form.filter.toLowerCase();
    const shown = (apps ?? []).filter((a) => form.apps.has(a.id) || !q || a.name.toLowerCase().includes(q) || a.id.toLowerCase().includes(q));
    const missing = [...form.apps].filter((id) => !(apps ?? []).some((a) => a.id === id));
    list.replaceChildren(...(apps ? [] : [h("div", { class: "note" }, "Loading your apps…")]),
      ...[...missing.map((id) => ({ id, name: id })), ...shown].slice(0, 200).map((a) =>
        h("label", {},
          h("input", { type: "checkbox", checked: form.apps.has(a.id), onchange: (e) => { e.target.checked ? form.apps.add(a.id) : form.apps.delete(a.id); } }),
          a.name, h("small", {}, a.id))));
  };
  drawList();
  const targets = h("fieldset", {},
    h("legend", {}, "What to block"),
    h("input", { type: "search", placeholder: "Find a laptop app", value: form.filter, oninput: (e) => { form.filter = e.target.value; drawList(); } }),
    list,
    field("Websites", h("textarea", { value: form.sitesText, placeholder: "youtube.com\nreddit.com", oninput: (e) => (form.sitesText = e.target.value) }),
      "One per line; each covers its subdomains. The phone blocks these too."),
    b?.phoneApps > 0 && h("div", { class: "note" }, `Also ${b.phoneApps} phone app${b.phoneApps === 1 ? "" : "s"}: change those on the phone.`));

  // How it unlocks
  let rules;
  if (mode === "HABIT") {
    const hasTasks = isNew ? d.checklist : b.trigger !== "TIMER";
    const hasTimer = isNew ? d.timer : b.trigger !== "CHECKLIST";
    const linkList = !isNew ? null : Array.isArray(habits)
      ? h("div", { class: "applist short" }, habits.length ? habits.map((x) => {
        const on = d.linked.has(x.uuid);
        return h("label", {},
          h("input", { type: "checkbox", checked: on, onchange: (e) => { e.target.checked ? d.linked.set(x.uuid, { uuid: x.uuid, name: x.name, yesterday: false }) : d.linked.delete(x.uuid); rebuildForm(); } }),
          x.name,
          on && h("small", {}, h("label", { class: "check inline" }, h("input", { type: "checkbox", checked: d.linked.get(x.uuid).yesterday, onchange: (e) => (d.linked.get(x.uuid).yesterday = e.target.checked) }), "counts yesterday's tick")));
      }) : h("div", { class: "note" }, "No habits yet."))
      : h("div", { class: "note" }, habits?.error ?? (state?.syncConfigured ? "Loading your habits…" : "Linking habits needs sync with the home server."));
    rules = h("fieldset", {},
      h("legend", {}, "How it unlocks"),
      isNew
        ? h("div", { class: "checks" }, check("checklist", "Do tasks"), check("timer", "Wait on a timer"))
        : h("div", { class: "note form-how" }, how(b) ?? ""),
      hasTasks && hasTimer && field("Needs", seg("combineOr", [[false, "Both"], [true, "Either one"]])),
      hasTimer && field("Timer (minutes)", number("timerMin"), "It counts while you wait on the block page."),
      isNew && hasTasks && field("Tasks", h("textarea", { value: d.itemsText, placeholder: "One per line", oninput: (e) => (d.itemsText = e.target.value) })),
      isNew && hasTasks && h("div", { class: "field" }, h("span", {}, "Habits that unlock it"), linkList),
      !isNew && hasTasks && h("div", { class: "field" }, h("span", {}, "Tasks"), h("div", { class: "form-items" }),
        h("div", { class: "row" },
          h("input", { type: "text", placeholder: "A new task", value: d.newTask, oninput: (e) => (d.newTask = e.target.value), onkeydown: (e) => e.key === "Enter" && addTask() }),
          h("button", { class: "btn", type: "button", onclick: addTask }, "Add")),
        Array.isArray(habits) && habits.length > 0 && h("div", { class: "row" },
          h("select", { id: "link-pick" }, habits.filter((x) => !b.items.some((i) => i.linked && i.text === x.name)).map((x) => h("option", { value: x.uuid }, x.name))),
          h("button", {
            class: "btn", type: "button",
            onclick: () => {
              const pick = formNode.querySelector("#link-pick");
              const x = habits.find((y) => y.uuid === pick?.value);
              if (x) itemOp({ op: "link", habit: { uuid: x.uuid, name: x.name } });
            },
          }, "Link habit"))),
      field("Each unlock lasts (minutes)", number("unlockMin", { max: min(state.dailyCapMs) }), `Up to ${min(state.dailyCapMs)} min a day across phone and laptop.`));
  } else if (mode === "ALLOWANCE") {
    rules = h("fieldset", {}, h("legend", {}, "Daily limit"), field("Minutes a day", number("allowanceMin"), "Once used up, it's blocked until tomorrow. Phone and laptop share it."));
  } else if (mode === "SCHEDULE") {
    rules = h("fieldset", {}, h("legend", {}, "When it blocks"),
      h("div", { class: "daypick" }, DAYS.map((name, i) => h("label", {},
        h("input", { type: "checkbox", checked: d.days.includes(i + 1), onchange: (e) => { d.days = e.target.checked ? [...new Set([...d.days, i + 1])].sort() : d.days.filter((x) => x !== i + 1); } }), name))),
      h("div", { class: "row" },
        field("From", h("input", { type: "time", value: hhmm(d.startMin), oninput: (e) => (d.startMin = minutesOf(e.target.value)) })),
        field("Until", h("input", { type: "time", value: hhmm(d.endMin), oninput: (e) => (d.endMin = minutesOf(e.target.value)) }))),
      h("div", { class: "note" }, "A window past midnight belongs to the day it starts on."));
  } else {
    rules = h("fieldset", {}, h("legend", {}, "Lockdown"), field("Minutes", number("lockdownMin"), isNew ? "It starts as soon as you create it." : "How long each lockdown lasts."));
  }

  // An error is about what was sent: it goes as soon as something changes.
  return h("div", { class: "editor form", "data-block": form.blockId ?? "", oninput: () => form.error && showFormError(""), onchange: () => form.error && showFormError("") },
    isNew && h("h3", {}, "New block"),
    field("Name", h("input", { type: "text", value: d.name, placeholder: "Games, Social, …", oninput: (e) => (d.name = e.target.value) })),
    isNew && field("Kind", seg("kind", [["HABIT", "Habit"], ["ALLOWANCE", "Daily limit"], ["TIME", "Set time"]])),
    isNew && d.kind === "TIME" && seg("setTime", [["SCHEDULE", "On a schedule"], ["LOCKDOWN", "For a while, from now"]]),
    targets,
    rules,
    errorBox,
    h("div", { class: "guard" }),
    h("div", { class: "actions" },
      h("button", { class: "btn primary", type: "button", onclick: submitForm }, isNew ? "Create block" : "Save"),
      h("button", { class: "btn ghost", type: "button", onclick: () => { cancelGuard(); message = ""; closeForm(); } }, "Cancel"),
      !isNew && h("span", { class: "spacer" }),
      !isNew && h("button", { class: "btn danger", type: "button", onclick: () => deleteBlock(b) }, "Delete block")));
}

function itemRows(b) {
  if (!b.items.length) return [h("div", { class: "note" }, "No tasks yet.")];
  return b.items.map((i) => h("div", { class: "row item" },
    h("span", {}, i.text, i.linked && h("small", {}, i.yesterday ? " · habit, yesterday's tick" : " · habit")),
    h("button", { class: "btn ghost", type: "button", title: "Removing a task waits 60 s", onclick: () => itemOp({ op: "remove", itemId: i.id }, `Removing "${i.text}"`) }, "Remove")));
}

function showFormError(text) {
  form.error = text;
  formNode?.querySelector(".error")?.replaceChildren(text);
}

async function submitForm() {
  const d = form.data;
  const result = await post("/api/block", {
    id: form.blockId,
    name: d.name,
    mode: formMode(),
    desktopApps: [...form.apps],
    sites: form.sitesText.split(/[\s,]+/).filter(Boolean),
    checklist: d.checklist,
    timer: d.timer,
    combineOr: d.combineOr,
    timerMin: d.timerMin,
    unlockMin: d.unlockMin,
    items: (d.itemsText ?? "").split("\n"),
    linked: [...(d.linked?.values() ?? [])],
    allowanceMin: d.allowanceMin,
    lockdownMin: d.lockdownMin,
    days: d.days,
    startMin: d.startMin,
    endMin: d.endMin,
  });
  if (result.error) showFormError(result.error);
  else if (result.token) startGuard(result.token, result.waitMs, "Saving (it makes the block easier to get past)");
  else {
    closeForm();
    refresh();
  }
}

async function addTask() {
  const text = form.data.newTask.trim();
  if (!text) return;
  form.data.newTask = "";
  formNode.querySelector('.row input[type="text"]').value = "";
  await itemOp({ op: "add", text });
}

async function itemOp(body, label = "") {
  const result = await post("/api/item", { blockId: form.blockId, ...body });
  if (result.error) showFormError(result.error);
  else if (result.token) startGuard(result.token, result.waitMs, label, false);
  else showFormError("");
  refresh();
}

async function deleteBlock(b) {
  if (!confirm(`Delete "${b.name}"? It goes on the phone too.`)) return;
  const result = await post("/api/block/delete", { blockId: b.id });
  if (result.error) showFormError(result.error);
  else if (result.token) startGuard(result.token, result.waitMs, `Deleting ${b.name}`);
  else {
    closeForm();
    refresh();
  }
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
    guard && !form && guardView(),
    !onBlockPage && h("section", { class: "card summary" },
      h("div", { class: "stat" }, h("b", {}, `${min(state.unlockUsedMs)} / ${min(state.dailyCapMs)} min`), h("span", {}, "Unlocked today (phone and laptop)")),
      h("span", { class: "spacer" }),
      pause,
      !form && h("button", { class: "btn primary", onclick: () => openForm(null) }, "+ New block")),
    form && !form.blockId && !onBlockPage && h("section", { class: "card" }, formView(null)),
    blocks.length ? blocks.map(blockCard) : !form && h("section", { class: "card note" }, onBlockPage ? "No block covers this any more." : "No blocks yet. Make one here, or in xrnhabit on the phone."),
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
