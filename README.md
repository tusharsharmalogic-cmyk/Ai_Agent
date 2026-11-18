# TUXORA

An Android browser that runs an **AI agent loop entirely on your phone**.

TUXORA is a heavily modified fork of [Ninja](https://github.com/mthli/Ninja) by mthli (Apache 2.0). Ninja's classic browsing shell is still here — but the entire point of this fork is the **Termux AI-agent bridge**: the browser can talk to your filesystem and shell, and an AI (ChatGPT / Claude / DeepSeek / Gemini) can drive it from inside the page.

No cloud. No server port. No extension. Just your browser, your AI tab, and your shell — all one loop.

---

## What this fork adds (and only this)

### 🔌 Serverless Termux bridge
- Java **`TermuxBridge`** interface + **`agent_cli.py`** adapter — the browser talks to Termux through a shared directory at `/sdcard/.termux_agent/` (`req_<id>.json`, `res_<id>.json`, `run.out`, `run.meta`, `run.input`, `run.kill`).
- **No Flask port is exposed.** `agent.py` is driven in-process via Flask's test client — every endpoint (`/run`, `/read`, `/write`, `/edit`, `/append`, `/delete`, `/move`, `/copy`, `/list`, `/mkdir`, `/info`, `/find`, `/grep`, `/head`, `/tail`, `/pdf`, `/status`) behaves exactly like the original Flask app, with zero duplicated logic.
- **Live command streaming** — output is tailed from `run.out` while the command runs; stdin prompts (`[y/n]`, `Password:`, `Login:`) are answered from the browser via `run.input`, and `run.kill` hard-stops the current process.

### 🤖 Automated AI ↔ Termux loop
- Injected **userscript shim** parses `<pre>` code blocks from AI responses (`EDIT_FILE`, `WRITE_FILE`, `READ_FILE`, `APPEND_FILE`, `DELETE_FILE`, `MOVE_FILE`, `COPY_FILE`, `LIST_DIR`, `MAKE_DIR`, `FILE_INFO`, `FIND_FILE`, `GREP`, `HEAD`, `TAIL`, `RUN_CMD`, `RUN_CMD_START/END`), executes them in Termux, and feeds the result back to the AI as the next message.
- Deduped by code-block fingerprint + 2-cycle stability wait, so streaming/re-renders never double-execute.

### 🎯 Per-site DOM selector packs
- **ChatGPT (current DOM)** — `MarkdownRoot`, `RichTextInput` editor, `aria-label="Send"` button, `CodeContent` code blocks, Enter-key fallback.
- **Claude / DeepSeek / Gemini** — matching selector sets kept in sync with the injected system prompt.
- Robust editor / send-button / message-detection / code-block extraction so a cosmetic site change doesn't silently break the agent.

### ⚙️ Agent controls in the UI
- **System prompt injector** — paste / edit / inject the agent's system prompt directly from the 3-dot overflow menu.
- **Pause / Resume agent toggle** — 3-dot menu switch with a clear **`AGENT OFF`** badge when paused.
- **Resume last site on restart** — reopen the last-used AI site instead of a blank page.

### 🌐 Compatibility & layout
- **Chrome-like User-Agent + third-party cookies enabled** — Google login finally works.
- **`android.max_aspect` declared** — kills the bottom blank bar on tall / notch screens.

### 🏗️ Build & CI
- **GitHub Actions workflow** builds an APK on every push.
- **Signed debug APK from permanent key** stored in repo secrets, so installs upgrade in place.
- **Auto-incrementing `versionCode`** per build.
- **JDK 17** + `setup-java v5` for `sdkmanager`.

### 🎨 Rebrand
- App renamed to **TUXORA** with a new launcher icon.

---

## What it still is underneath
Ninja's original browsing shell is untouched: open links in background, custom home, fashion tab switcher, AdBlock + whitelist, Readability, whole-page screenshot, custom volume control, etc. — see the upstream [Ninja README](https://github.com/mthli/Ninja) for the full classic feature set.

## How to build
- **Push to your fork** → GitHub Actions builds a signed debug APK automatically.
- **Local** → open the `Ninja/` folder in IntelliJ IDEA / Android Studio (classic Ninja build path).

## How to use the AI loop
1. Install the APK.
2. Install **Termux** + `python`, `flask`.
3. Put `agent.py` + `agent_cli.py` under `~/Ai-agent-script/` (or the same directory — `agent_cli.py` auto-discovers it).
4. In TUXORA, open ChatGPT / Claude / DeepSeek / Gemini.
5. From the 3-dot menu, set the system prompt, and let the AI drive.

Every AI reply containing a `<pre>` action block gets executed in Termux, and the output is sent back to the AI — the loop runs until you pause it.

## License
Apache License 2.0 — inherited from Ninja by mthli.