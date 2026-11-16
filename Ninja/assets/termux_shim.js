// Termux Agent shim: GM_xmlhttpRequest -> TermuxBridge (no localhost server).
(function () {
    'use strict';
    if (window.__termuxShimLoaded) return;
    window.__termuxShimLoaded = true;

    var B = window.TermuxBridge;
    var BASE = 'http://localhost:5000';
    var run = { id: null, offset: 0, startedAt: 0 };

    function newId() {
        return 'r' + Date.now().toString(36) + Math.floor(Math.random() * 1e6).toString(36);
    }

    function reply(opts, obj) {
        var text = typeof obj === 'string' ? obj : JSON.stringify(obj);
        setTimeout(function () {
            if (opts.onload) opts.onload({ status: 200, responseText: text });
        }, 0);
    }

    function fail(opts) {
        setTimeout(function () { if (opts.onerror) opts.onerror({}); }, 0);
    }

    function bridgeOk() {
        try { return !!(B && B.isAvailable()); } catch (e) { return false; }
    }

    function doRun(opts, payload) {
        var id = newId();
        B.reset();
        var r = B.request('run', JSON.stringify(payload), id);
        if (r !== 'ok') { reply(opts, { status: 'error', output: '❌ ' + r }); return; }
        run = { id: id, offset: 0, startedAt: Date.now() };
        reply(opts, { status: 'started' });
    }

    function doPoll(opts) {
        var snap = { chunks: [], done: false, final_output: '', input_needed: false,
                     input_context: '', running: true, elapsed: 0 };
        var raw = B.readText('run.meta');
        var meta = null;
        if (raw) { try { meta = JSON.parse(raw); } catch (e) { meta = null; } }

        if (!meta || meta.id !== run.id) {
            if (Date.now() - run.startedAt > 20000) {
                snap.done = true; snap.running = false;
                snap.final_output = '❌ Termux ne 20s me respond nahi kiya. Check: Termux installed, allow-external-apps=true, python+flask installed, storage permission.';
            } else {
                snap.elapsed = (Date.now() - run.startedAt) / 1000;
            }
            reply(opts, snap);
            return;
        }

        if (meta.out_bytes > run.offset) {
            var txt = B.readRange(run.offset, meta.out_bytes - run.offset);
            if (txt) snap.chunks.push(txt);
            run.offset = meta.out_bytes;
        }
        snap.done = !!meta.done;
        snap.running = !!meta.running;
        snap.final_output = meta.final_output || '';
        snap.input_needed = !!meta.input_needed;
        snap.input_context = meta.input_context || '';
        snap.elapsed = meta.elapsed || 0;
        reply(opts, snap);
    }

    function doOneShot(opts, endpoint, payload) {
        var id = newId();
        var r = B.request(endpoint, JSON.stringify(payload), id);
        if (r !== 'ok') { reply(opts, { status: 'error', output: '❌ ' + r }); return; }
        var name = 'res_' + id + '.json';
        var t0 = Date.now();
        var timer = setInterval(function () {
            var txt = B.readText(name);
            if (txt) {
                clearInterval(timer);
                B.consume(name);
                reply(opts, txt);
            } else if (Date.now() - t0 > 300000) {
                clearInterval(timer);
                reply(opts, { status: 'error', output: '❌ Termux se result nahi aaya (timeout 300s).' });
            }
        }, 150);
    }

    window.GM_xmlhttpRequest = function (opts) {
        if (!bridgeOk()) { fail(opts); return; }
        var url = opts.url || '';
        if (url.indexOf(BASE) !== 0) { fail(opts); return; }
        var ep = url.substring(BASE.length).replace(/^\/+/, '');
        var payload = {};
        if (opts.data) { try { payload = JSON.parse(opts.data); } catch (e) { payload = {}; } }

        try {
            if (ep === 'run')        doRun(opts, payload);
            else if (ep === 'poll')  doPoll(opts);
            else if (ep === 'input') { B.control('run.input', payload.value || ''); reply(opts, { status: 'ok' }); }
            else if (ep === 'kill')  { B.control('run.kill', '1'); reply(opts, { status: 'ok', output: '🛑 Kill signal bhej diya' }); }
            else                     doOneShot(opts, ep, payload);
        } catch (e) {
            fail(opts);
        }
    };
})();