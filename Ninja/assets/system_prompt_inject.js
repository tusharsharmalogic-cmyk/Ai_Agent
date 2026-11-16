function (p) {
    var el = document.querySelector('#prompt-textarea, div.ProseMirror[contenteditable="true"], rich-textarea .ql-editor, div[contenteditable="true"], textarea');
    if (!el) { return 'noinput'; }
    el.focus();
    if (el.tagName === 'TEXTAREA') {
        var setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
        setter.call(el, p);
        el.dispatchEvent(new Event('input', { bubbles: true }));
    } else {
        document.execCommand('selectAll', false, null);
        document.execCommand('insertText', false, p);
    }
    setTimeout(function () {
        var btn = document.querySelector('button[data-testid="send-button"], button[aria-label="Send message"], button[aria-label="Send Message"], button[aria-label="Send prompt"], button.send-button, button[aria-label*="Send"]');
        if (btn && !btn.disabled) {
            btn.click();
        } else {
            var o = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
            el.dispatchEvent(new KeyboardEvent('keydown', o));
            el.dispatchEvent(new KeyboardEvent('keyup', o));
        }
    }, 500);
    return 'ok';
}
