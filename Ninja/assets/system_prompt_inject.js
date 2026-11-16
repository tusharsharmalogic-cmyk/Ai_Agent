function (p) {
    var H = location.hostname;
    var IS_CLAUDE   = H === 'claude.ai';
    var IS_GEMINI   = H === 'gemini.google.com';
    var IS_CHATGPT  = H === 'chatgpt.com';
    var IS_DEEPSEEK = H === 'chat.deepseek.com';

    function insertText(editor, text) {
        try {
            editor.focus();
            var range = document.createRange();
            range.selectNodeContents(editor);
            var sel = window.getSelection();
            sel.removeAllRanges();
            sel.addRange(range);
            document.execCommand('delete', false, null);
            var ok = document.execCommand('insertText', false, text);
            if (!ok || !editor.textContent) {
                editor.textContent = text;
                editor.dispatchEvent(new InputEvent('input', { bubbles: true }));
            }
            return true;
        } catch (e) {
            try {
                editor.textContent = text;
                editor.dispatchEvent(new InputEvent('input', { bubbles: true }));
                return true;
            } catch (e2) {
                return false;
            }
        }
    }

    // ── Claude ──
    if (IS_CLAUDE) {
        var ce = document.querySelector('.ProseMirror');
        if (!ce) { return 'noinput'; }
        if (!insertText(ce, p)) { return 'noinput'; }
        ce.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
        setTimeout(function () {
            var b = document.querySelector('button[aria-label="Send message"]');
            if (b) { b.click(); }
        }, 1000);
        return 'ok';
    }

    // ── Gemini ──
    if (IS_GEMINI) {
        var ge = document.querySelector('rich-textarea div[contenteditable="true"]')
              || document.querySelector('.ql-editor[contenteditable="true"]')
              || document.querySelector('div[contenteditable="true"][role="textbox"]')
              || document.querySelector('div[contenteditable="true"]');
        if (!ge) { return 'noinput'; }
        if (!insertText(ge, p)) { return 'noinput'; }
        ge.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true }));
        ge.dispatchEvent(new Event('change', { bubbles: true }));
        setTimeout(function () {
            var b = document.querySelector('button[aria-label="Send message"]');
            if (b && !b.disabled) {
                b.click();
            } else {
                var fb = document.querySelector('button[data-mat-icon-name="arrow_upward"]');
                if (!fb) {
                    var ic = document.querySelector('button mat-icon[data-mat-icon-name="arrow_upward"]');
                    fb = ic ? ic.closest('button') : null;
                }
                if (fb) { fb.click(); }
            }
        }, 1000);
        return 'ok';
    }

    // ── ChatGPT ──
    if (IS_CHATGPT) {
        var pe = document.querySelector('div#prompt-textarea[contenteditable="true"]');
        if (!pe) { return 'noinput'; }
        if (!insertText(pe, p)) {
            pe.textContent = p;
            pe.dispatchEvent(new InputEvent('input', { bubbles: true }));
        }
        setTimeout(function () {
            var b1 = document.querySelector('button#composer-submit-button[data-testid="send-button"]');
            if (b1 && b1.getAttribute('aria-disabled') !== 'true') { b1.click(); return; }
            var b2 = document.querySelector('[data-testid="send-button"]');
            if (b2 && b2.getAttribute('aria-disabled') !== 'true') { b2.click(); return; }
            var b3 = document.querySelector('button[aria-label="Send prompt"]');
            if (b3 && b3.getAttribute('aria-disabled') !== 'true') { b3.click(); }
        }, 1000);
        return 'ok';
    }

    // ── DeepSeek ──
    if (IS_DEEPSEEK) {
        var ta = document.querySelector('textarea[placeholder="Message DeepSeek"]')
              || document.querySelector('textarea');
        if (!ta) { return 'noinput'; }
        var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
        setter.call(ta, p);
        ta.dispatchEvent(new InputEvent('input', { bubbles: true }));
        setTimeout(function () {
            var clicked = false;
            var b1 = document.querySelector('button[aria-label*="Send"]')
                  || document.querySelector('button[aria-label*="send"]')
                  || document.querySelector('div[role="button"][aria-label*="Send"]');
            if (b1 && !b1.disabled) { b1.click(); clicked = true; }
            if (!clicked) {
                var b2 = document.querySelector('[data-testid*="send" i]')
                      || document.querySelector('[data-testid*="submit" i]');
                if (b2) { b2.click(); clicked = true; }
            }
            if (!clicked) {
                var b3 = document.querySelector('button[type="submit"]');
                if (b3 && !b3.disabled) { b3.click(); clicked = true; }
            }
            if (!clicked) {
                var paths = document.querySelectorAll('path[d*="M8.3125"]');
                for (var i = 0; i < paths.length; i++) {
                    var bb = paths[i].closest('div[role="button"]');
                    if (bb) { bb.click(); clicked = true; break; }
                }
            }
        }, 1500);
        return 'ok';
    }

    return 'noinput';
}