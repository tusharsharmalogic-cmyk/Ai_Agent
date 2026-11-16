package io.github.mthli.Ninja.Browser;

import android.content.Context;
import android.content.Intent;
import android.webkit.JavascriptInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JS bridge: lets the AI chat pages (userscript) run Termux actions
 * without a Flask server.
 *
 * browser JS -> TermuxBridge.request() -> Termux RUN_COMMAND intent
 *            -> python agent_cli.py -> agent.py (in-process)
 *            -> result files in SHARED_DIR -> browser JS reads them back.
 */
public class TermuxBridge {
    public static final String JS_NAME = "TermuxBridge";

    // Folder shared between Ninja and Termux (needs termux-setup-storage).
    private static final String SHARED_DIR = "/sdcard/.termux_agent";
    private static final String CLI_PATH   = "/sdcard/Ai_Agent/agent_cli.py";
    private static final String TERMUX_PY  = "/data/data/com.termux/files/usr/bin/python";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";

    // Only these sites may use the bridge (a random website must NEVER reach Termux).
    private static final String[] ALLOWED_HOSTS = {
            "claude.ai", "chatgpt.com", "gemini.google.com", "chat.deepseek.com"
    };

    private static final Set<String> ENDPOINTS = new HashSet<String>(Arrays.asList(
            "run", "status", "read", "write", "edit", "append", "delete", "move",
            "copy", "list", "mkdir", "info", "find", "grep", "head", "tail", "pdf"
    ));

    private static final Pattern ID_RE  = Pattern.compile("^[A-Za-z0-9_-]{1,40}$");
    private static final Pattern RES_RE = Pattern.compile("^res_[A-Za-z0-9_-]{1,40}\\.json$");

    private final Context context;
    private volatile String currentHost = "";

    public TermuxBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Called by the WebViewClient on every navigation (UI thread). */
    public void setCurrentHost(String host) {
        this.currentHost = host == null ? "" : host.toLowerCase();
    }

    public static boolean isAllowedHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        for (String h : ALLOWED_HOSTS) {
            if (host.equals(h)) return true;
        }
        return false;
    }

    private boolean allowed() {
        return isAllowedHost(currentHost);
    }

    private File sharedFile(String name) {
        File dir = new File(SHARED_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, name);
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return allowed();
    }

    /** Clears the live-run files before a new /run so stale results are never read. */
    @JavascriptInterface
    public void reset() {
        if (!allowed()) return;
        String[] names = {"run.meta", "run.out", "run.input", "run.kill"};
        for (String n : names) {
            sharedFile(n).delete();
        }
    }

    /** Writes req_<id>.json and asks Termux to run agent_cli.py. Returns "ok" or "error: ...". */
    @JavascriptInterface
    public String request(String endpoint, String payloadJson, String id) {
        if (!allowed()) return "error: host not allowed";
        if (endpoint == null || !ENDPOINTS.contains(endpoint)) return "error: bad endpoint";
        if (id == null || !ID_RE.matcher(id).matches()) return "error: bad id";

        try {
            sharedFile("res_" + id + ".json").delete();
            FileOutputStream fos = new FileOutputStream(sharedFile("req_" + id + ".json"));
            fos.write((payloadJson == null ? "{}" : payloadJson).getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            return "error: cannot write shared dir (" + e.getMessage() + "). Ninja ko storage permission do.";
        }

        try {
            Intent i = new Intent();
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_PY);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{CLI_PATH, endpoint, id});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            context.startService(i);
            return "ok";
        } catch (SecurityException se) {
            return "error: RUN_COMMAND permission denied. Termux me allow-external-apps=true set karo.";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** Reads a small result file (res_<id>.json or run.meta). Returns "" if missing. */
    @JavascriptInterface
    public String readText(String name) {
        if (!allowed()) return "";
        if (name == null || !(RES_RE.matcher(name).matches() || name.equals("run.meta"))) return "";
        File f = sharedFile(name);
        if (!f.exists()) return "";
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            byte[] buf = new byte[(int) raf.length()];
            raf.readFully(buf);
            raf.close();
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Deletes a consumed result file. */
    @JavascriptInterface
    public void consume(String name) {
        if (!allowed()) return;
        if (name != null && RES_RE.matcher(name).matches()) {
            sharedFile(name).delete();
        }
    }

    /** Reads [offset, offset+length) bytes of run.out (live command output). */
    @JavascriptInterface
    public String readRange(long offset, long length) {
        if (!allowed()) return "";
        File f = sharedFile("run.out");
        if (!f.exists() || length <= 0 || offset < 0) return "";
        try {
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long avail = raf.length() - offset;
            if (avail <= 0) { raf.close(); return ""; }
            int n = (int) Math.min(length, avail);
            byte[] buf = new byte[n];
            raf.seek(offset);
            raf.readFully(buf);
            raf.close();
            return new String(buf, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Sends input / kill to the running command: name = run.input | run.kill. */
    @JavascriptInterface
    public String control(String name, String value) {
        if (!allowed()) return "error: host not allowed";
        if (!"run.input".equals(name) && !"run.kill".equals(name)) return "error: bad name";
        try {
            FileOutputStream fos = new FileOutputStream(sharedFile(name));
            fos.write((value == null ? "" : value).getBytes("UTF-8"));
            fos.close();
            return "ok";
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }
}