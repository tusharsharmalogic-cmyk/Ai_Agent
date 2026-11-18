package io.github.mthli.Ninja.Browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mthli.Ninja.R;

/**
 * TUXORA redirect + popup ad blocker.
 *
 * Handles the two problems user-facing download pages exploit:
 *   1. Download-button trap — click opens an ad page; user has to go back
 *      and tap again to actually get the file.
 *   2. Popup / interstitial ads that hijack the page.
 *
 * Strategy (all layered, no false positives on legit external links):
 *   A. Domain blocklist (assets/adblock_domains.txt — HaGeZi popup + urlshortener)
 *      Suffix match: block adserver.example.com if "example.com" is listed.
 *   B. Click heuristic: if a navigation to a *new* domain happens within
 *      CLICK_WINDOW_MS of a real user tap, and the target isn't a download
 *      URL and isn't an allowed same-site navigation, treat it as a trap.
 *   C. Redirect bomb: more than BOMB_COUNT top-level navigations in
 *      BOMB_WINDOW_MS → block.
 *   D. Per-site allow list (URL hostname) persisted in SharedPreferences so
 *      the user can whitelist a site that breaks (via menu / toast action).
 *
 * All methods are safe to call from the UI thread; the blocklist loads on a
 * background thread on first use.
 */
public class RedirectAdBlocker {

    private static final String TAG = "TuxoraRedirect";

    private static final long CLICK_WINDOW_MS = 1500;   // user tap → navigation
    private static final long BOMB_WINDOW_MS  = 2500;   // rapid nav chain
    private static final int  BOMB_COUNT      = 3;      // navs in window

    /** File extensions we always treat as a legit download (not an ad trap). */
    private static final Set<String> DOWNLOAD_EXTS = new HashSet<String>();
    static {
        Collections.addAll(DOWNLOAD_EXTS,
                "apk","zip","rar","7z","tar","gz","bz2","xz","iso","dmg",
                "pdf","epub","mobi","azw3","doc","docx","xls","xlsx","ppt","pptx",
                "mp3","m4a","flac","wav","ogg","opus","mp4","mkv","avi","mov","webm",
                "jpg","jpeg","png","gif","webp","svg","exe","msi","deb","rpm");
    }

    /** Very common safe redirectors that sites use for outbound links. */
    private static final Set<String> SAFE_REDIRECTORS = new HashSet<String>();
    static {
        Collections.addAll(SAFE_REDIRECTORS,
                "google.com","www.google.com","google.co.in",
                "bing.com","www.bing.com",
                "duckduckgo.com",
                "youtube.com","www.youtube.com","youtu.be",
                "facebook.com","www.facebook.com",
                "twitter.com","x.com","t.co",
                "instagram.com","www.instagram.com",
                "linkedin.com","www.linkedin.com",
                "reddit.com","www.reddit.com",
                "wikipedia.org","en.wikipedia.org",
                "github.com","gitlab.com",
                "stackoverflow.com","stackexchange.com",
                "medium.com","substack.com",
                "amazon.com","www.amazon.com","amazon.in","www.amazon.in",
                "flipkart.com","www.flipkart.com",
                "paypal.com","www.paypal.com",
                "android.com","play.google.com","developer.android.com");
    }

    private final Context context;

    /** Blocklist — suffix match on registrable-ish domain. */
    private final Set<String> blockDomains = new HashSet<String>();
    private volatile boolean domainsLoaded = false;
    private volatile boolean domainsLoading = false;

    /** Per-site allow list (hostname → true). */
    private final Set<String> allowSites = new HashSet<String>();

    /** Click tracking. */
    private long lastClickTs = 0L;
    private String lastClickHost = "";
    private String currentPageHost = "";

    /** Redirect bomb tracking. */
    private long navWindowStart = 0L;
    private int navWindowCount = 0;

    public RedirectAdBlocker(Context context) {
        this.context = context.getApplicationContext();
        loadAllowSites();
        ensureDomainsLoaded();
    }

    // ── Preference gate ──────────────────────────────────────────────────────

    public static boolean isEnabled(Context ctx) {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(ctx);
            return sp.getBoolean(ctx.getString(R.string.sp_redirect_block), true);
        } catch (Exception e) {
            return true;
        }
    }

    // ── Domain blocklist ─────────────────────────────────────────────────────

    private void ensureDomainsLoaded() {
        if (domainsLoaded || domainsLoading) return;
        domainsLoading = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(
                            context.getAssets().open("adblock_domains.txt"), "UTF-8"));
                    String line;
                    Set<String> tmp = new HashSet<String>(131072);
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.charAt(0) == '#') continue;
                        if (line.startsWith("*.")) line = line.substring(2);
                        if (!line.isEmpty()) tmp.add(line.toLowerCase());
                    }
                    r.close();
                    synchronized (blockDomains) {
                        blockDomains.clear();
                        blockDomains.addAll(tmp);
                    }
                    domainsLoaded = true;
                } catch (Exception e) {
                    Log.w(TAG, "blocklist load failed", e);
                } finally {
                    domainsLoading = false;
                }
            }
        }, "tuxora-adblock-load");
        t.setDaemon(true);
        t.start();
    }

    /** Suffix match: "a.b.example.com" matches listed domain "example.com". */
    private boolean isListedDomain(String host) {
        if (host == null) return false;
        host = host.toLowerCase();
        if (host.isEmpty()) return false;
        if (!domainsLoaded) {
            // Graceful degradation while list still loading.
            ensureDomainsLoaded();
            return false;
        }
        synchronized (blockDomains) {
            String h = host;
            while (true) {
                if (blockDomains.contains(h)) return true;
                int dot = h.indexOf('.');
                if (dot < 0) break;
                h = h.substring(dot + 1);
                if (h.isEmpty()) break;
            }
        }
        return false;
    }

    // ── Allow list ───────────────────────────────────────────────────────────

    private static final String PREF_ALLOW = "tuxora_redirect_allow";

    private void loadAllowSites() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            String raw = sp.getString(PREF_ALLOW, "");
            if (raw == null || raw.isEmpty()) return;
            for (String s : raw.split("\n")) {
                s = s.trim().toLowerCase();
                if (!s.isEmpty()) allowSites.add(s);
            }
        } catch (Exception ignored) {}
    }

    public synchronized void allowSite(String host) {
        if (host == null) return;
        host = host.toLowerCase();
        if (host.isEmpty() || allowSites.contains(host)) return;
        allowSites.add(host);
        persistAllow();
    }

    public synchronized void disallowSite(String host) {
        if (host == null) return;
        if (allowSites.remove(host.toLowerCase())) persistAllow();
    }

    public synchronized boolean isSiteAllowed(String host) {
        if (host == null) return false;
        return allowSites.contains(host.toLowerCase());
    }

    private void persistAllow() {
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
            sp.edit().putString(PREF_ALLOW, join(allowSites)).apply();
        } catch (Exception ignored) {}
    }

    private static String join(Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String v : s) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(v);
        }
        return sb.toString();
    }

    // ── Click tracking (called from the WebView's touch handler) ─────────────

    public void onUserClick(String pageHost) {
        this.lastClickTs = System.currentTimeMillis();
        this.lastClickHost = pageHost == null ? "" : pageHost.toLowerCase();
    }

    public void onPageStart(String url) {
        Uri u = safeParse(url);
        if (u != null) currentPageHost = hostOf(u);
        // Reset bomb counter when a new top-level page starts.
        navWindowStart = 0L;
        navWindowCount = 0;
    }

    // ── Main decision ────────────────────────────────────────────────────────

    /**
     * @param pageUrl   URL of the page the user is currently on (source).
     * @param targetUrl URL the WebView is being asked to navigate to.
     * @return true if this navigation should be blocked.
     */
    public synchronized boolean shouldBlock(String pageUrl, String targetUrl) {
        if (!isEnabled(context)) return false;
        if (targetUrl == null) return false;

        Uri tu = safeParse(targetUrl);
        if (tu == null) return false;
        String targetHost = hostOf(tu);
        if (targetHost.isEmpty()) return false;

        // Never block same-host navigations.
        Uri pu = safeParse(pageUrl);
        String pageHost = pu != null ? hostOf(pu) : currentPageHost;
        if (!pageHost.isEmpty() && targetHost.equals(pageHost)) return false;

        // User has whitelisted this site → nothing is blocked on it.
        if (!pageHost.isEmpty() && isSiteAllowed(pageHost)) return false;

        // Always allow safe redirectors (Google/Bing/etc.).
        if (SAFE_REDIRECTORS.contains(targetHost)) return false;

        // Download URLs (e.g. .apk / .zip / .pdf) → not an ad.
        if (isDownloadUrl(targetUrl)) return false;

        // Redirect bomb: too many top-level navs in a short window.
        long now = System.currentTimeMillis();
        if (navWindowStart == 0L || now - navWindowStart > BOMB_WINDOW_MS) {
            navWindowStart = now;
            navWindowCount = 1;
        } else {
            navWindowCount++;
            if (navWindowCount > BOMB_COUNT) {
                Log.i(TAG, "redirect bomb: " + targetUrl);
                return true;
            }
        }

        // A. Domain blocklist hit.
        if (isListedDomain(targetHost)) {
            Log.i(TAG, "blocked by domain list: " + targetHost);
            return true;
        }

        // B. Click heuristic: within CLICK_WINDOW of a genuine tap, going to a
        //    foreign domain that isn't a download → likely a redirect trap.
        if (now - lastClickTs < CLICK_WINDOW_MS && !lastClickHost.isEmpty()) {
            if (!targetHost.equals(lastClickHost)
                    && !targetHost.endsWith("." + lastClickHost)
                    && !lastClickHost.endsWith("." + targetHost)
                    && !isKnownSafeExternal(targetHost)) {
                Log.i(TAG, "blocked by click heuristic: " + lastClickHost + " → " + targetHost);
                return true;
            }
        }

        return false;
    }

    /** Builds the JS snippet that removes meta-refresh and JS location hijacks. */
    public static String getMetaRefreshKillerJs() {
        return "(function(){"
            + "try{"
            + "var ms=document.querySelectorAll('meta[http-equiv=\"refresh\" i]');"
            + "for(var i=0;i<ms.length;i++){var c=ms[i].getAttribute('content')||'';"
            + "if(/url\\s*=/i.test(c)){ms[i].parentNode&&ms[i].parentNode.removeChild(ms[i]);}}}catch(e){}"
            + "try{window.open=function(){return null;};}catch(e){}"
            + "})();";
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Uri safeParse(String url) {
        if (url == null) return null;
        try { return Uri.parse(url); } catch (Exception e) { return null; }
    }

    private static String hostOf(Uri u) {
        String h = u.getHost();
        if (h == null) return "";
        h = h.toLowerCase();
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    private static boolean isDownloadUrl(String url) {
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        int hash = lower.indexOf('#');
        if (hash >= 0) lower = lower.substring(0, hash);
        int slash = lower.lastIndexOf('/');
        if (slash < 0 || slash == lower.length() - 1) return false;
        String file = lower.substring(slash + 1);
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) return false;
        String ext = file.substring(dot + 1);
        return DOWNLOAD_EXTS.contains(ext);
    }

    private static boolean isKnownSafeExternal(String host) {
        return SAFE_REDIRECTORS.contains(host);
    }
}