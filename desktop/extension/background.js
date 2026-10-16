// xrnhabit extension: the go-between for content.js and xrnhabit-desktop on 127.0.0.1. The
// daemon decides what's blocked (the same shared blocks as the phone); this only reports the
// site in front and passes the verdict back. If the daemon is down, nothing is blocked and the
// toolbar icon shows "!".

const DEFAULT_PORT = 47321;

async function daemon() {
  const { port } = await chrome.storage.local.get("port");
  return `http://127.0.0.1:${port || DEFAULT_PORT}`;
}

function badge(ok) {
  chrome.action.setBadgeText({ text: ok ? "" : "!" });
  if (!ok) chrome.action.setBadgeBackgroundColor({ color: "#c62828" });
  chrome.action.setTitle({ title: ok ? "xrnhabit" : "xrnhabit: the laptop app isn't running, so nothing is blocked" });
}

chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (msg?.type !== "beat") return false;
  (async () => {
    try {
      const res = await fetch(`${await daemon()}/ext/heartbeat`, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Xrnhabit": "1" },
        body: JSON.stringify({ host: msg.host, focused: msg.focused }),
      });
      const verdict = await res.json();
      badge(true);
      reply(verdict);
    } catch {
      badge(false);
      reply(null);
    }
  })();
  return true; // the reply comes later
});

// The toolbar button opens the laptop's xrnhabit page.
chrome.action.onClicked.addListener(async () => {
  chrome.tabs.create({ url: `${await daemon()}/` });
});
