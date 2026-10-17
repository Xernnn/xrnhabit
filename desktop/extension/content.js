// xrnhabit extension, in every http(s) page: while this tab is visible, report its site every
// few seconds (and at once on focus changes), so xrnhabit-desktop knows what's in front and
// can count allowance time; when the site is blocked, step over to the block page, which comes
// back here once it's open again.

(() => {
  let gone = false;

  function beat() {
    if (gone || document.visibilityState !== "visible") return;
    const focused = document.hasFocus();
    try {
      chrome.runtime.sendMessage({ type: "beat", host: location.hostname, focused }, (verdict) => {
        void chrome.runtime.lastError; // the extension was reloaded: this page's copy is orphaned
        if (verdict?.blocked && !gone) {
          gone = true;
          location.replace(`${verdict.page}&back=${encodeURIComponent(location.href)}`);
        }
      });
    } catch {
      gone = true; // extension context invalidated
    }
  }

  beat();
  setInterval(beat, 2500);
  document.addEventListener("visibilitychange", beat);
  window.addEventListener("focus", beat);
  window.addEventListener("blur", beat);
})();
