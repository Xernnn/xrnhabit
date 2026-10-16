// xrnhabit for KWin (Plasma 6): reports each window to xrnhabit-desktop over D-Bus and closes
// the ones it says are blocked. The daemon decides; this script only reports and obeys.
// Arguments go as strings: a JS number would reach D-Bus as a double.

const SERVICE = "com.xern.xrnhabit";
const PATH = "/Desktop";
const IFACE = "com.xern.xrnhabit.Desktop";

function relevant(w) {
    return w && !w.specialWindow && (w.normalWindow || w.dialog);
}

function ask(method, w) {
    if (!relevant(w)) return;
    callDBus(SERVICE, PATH, IFACE, method,
        String(w.desktopFileName || ""), String(w.resourceClass || ""), String(w.pid || 0),
        function (answer) {
            if (answer === "block") w.closeWindow();
        });
}

workspace.windowActivated.connect(function (w) {
    if (!w) {
        // Nothing focused (the desktop): nothing in use.
        callDBus(SERVICE, PATH, IFACE, "Activated", "", "", "0");
        return;
    }
    ask("Activated", w);
});

workspace.windowAdded.connect(function (w) {
    ask("Added", w);
});

workspace.windowRemoved.connect(function (w) {
    if (relevant(w)) callDBus(SERVICE, PATH, IFACE, "Removed", String(w.pid || 0));
});

// On (re)load: the daemon may have just started, so list what's already open.
for (const w of workspace.windowList()) ask("Added", w);
if (workspace.activeWindow) ask("Activated", workspace.activeWindow);
