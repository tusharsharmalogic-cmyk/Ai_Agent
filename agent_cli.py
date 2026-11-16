#!/usr/bin/env python3
"""
agent_cli.py - serverless adapter for the Termux AI Agent.

Flow:  Ninja browser -> Termux RUN_COMMAND intent -> this script -> agent.py

agent.py's Flask app is NOT served on a port. It is driven in-process through
Flask's test client, so every endpoint (/edit, /write, /grep, /pdf, /run ...)
behaves exactly like before, with zero duplicated logic.

Usage:   python agent_cli.py <endpoint> <request_id>

Shared dir (default /sdcard/.termux_agent):
  req_<id>.json  -> written by the browser (request payload)
  res_<id>.json  -> written by this script (result for file endpoints)
  run.out        -> appended live output of the running command
  run.meta       -> JSON snapshot (done, input_needed, final_output, ...)
  run.input      -> written by the browser to answer a prompt
  run.kill       -> written by the browser to kill the running command
"""
import os
import sys
import json
import time

SHARED = os.environ.get('AGENT_SHARED_DIR', '/sdcard/.termux_agent')

ALLOWED = {
    'run', 'status', 'read', 'write', 'edit', 'append', 'delete', 'move',
    'copy', 'list', 'mkdir', 'info', 'find', 'grep', 'head', 'tail', 'pdf',
}


def write_atomic(path, text):
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(text)
    os.replace(tmp, path)


def find_agent():
    here = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        here,
        '/sdcard/Ai-agent-script',
        os.path.expanduser('~/Ai-agent-script'),
        os.path.expanduser('~'),
    ]
    for d in candidates:
        if os.path.exists(os.path.join(d, 'agent.py')):
            if d not in sys.path:
                sys.path.insert(0, d)
            return d
    return None


def run_live(client, payload, rid):
    out_path = os.path.join(SHARED, 'run.out')
    meta_path = os.path.join(SHARED, 'run.meta')
    inp_path = os.path.join(SHARED, 'run.input')
    kill_path = os.path.join(SHARED, 'run.kill')

    r = client.post('/run', json={'cmd': payload.get('cmd', '')})
    d = r.get_json(silent=True) or {}
    if d.get('status') != 'started':
        meta = {
            'id': rid, 'done': True, 'running': False,
            'final_output': d.get('output', '❌ run failed to start'),
            'input_needed': False, 'input_context': '',
            'elapsed': 0, 'out_bytes': 0,
        }
        write_atomic(meta_path, json.dumps(meta))
        return 1

    open(out_path, 'wb').close()
    out_bytes = 0

    while True:
        if os.path.exists(kill_path):
            try:
                os.remove(kill_path)
            except OSError:
                pass
            client.post('/kill', json={})

        if os.path.exists(inp_path):
            try:
                with open(inp_path, 'r', encoding='utf-8') as f:
                    val = f.read()
                os.remove(inp_path)
                client.post('/input', json={'value': val})
            except OSError:
                pass

        snap = client.get('/poll').get_json(silent=True) or {}
        chunks = snap.get('chunks') or []
        if chunks:
            data = ''.join(chunks).encode('utf-8')
            with open(out_path, 'ab') as f:
                f.write(data)
            out_bytes += len(data)

        meta = {
            'id': rid,
            'done': bool(snap.get('done')),
            'running': bool(snap.get('running')),
            'final_output': snap.get('final_output', ''),
            'input_needed': bool(snap.get('input_needed')),
            'input_context': snap.get('input_context', ''),
            'elapsed': snap.get('elapsed', 0),
            'out_bytes': out_bytes,
        }
        write_atomic(meta_path, json.dumps(meta))

        if snap.get('done'):
            break
        time.sleep(0.15)
    return 0


def main():
    if len(sys.argv) < 3:
        print('usage: agent_cli.py <endpoint> <request_id>')
        return 2

    endpoint = sys.argv[1].strip().strip('/')
    rid = sys.argv[2].strip()
    os.makedirs(SHARED, exist_ok=True)
    req_path = os.path.join(SHARED, 'req_%s.json' % rid)
    res_path = os.path.join(SHARED, 'res_%s.json' % rid)

    def fail(msg):
        write_atomic(res_path, json.dumps({'status': 'error', 'output': msg}))
        return 1

    if endpoint not in ALLOWED:
        return fail('❌ endpoint not allowed: %s' % endpoint)

    try:
        with open(req_path, 'r', encoding='utf-8') as f:
            payload = json.load(f)
    except Exception as e:
        return fail('❌ bad request file: %s' % e)

    if not find_agent():
        return fail('❌ agent.py nahi mila. agent_cli.py ke saath ya /sdcard/Ai-agent-script me rakho.')

    try:
        import agent
    except Exception as e:
        return fail('❌ agent.py import fail (pip install flask?): %s' % e)

    client = agent.app.test_client()

    try:
        if endpoint == 'run':
            return run_live(client, payload, rid)

        if endpoint == 'status':
            resp = client.get('/status')
        else:
            resp = client.post('/' + endpoint, json=payload)

        data = resp.get_json(silent=True)
        if data is None:
            data = {'status': 'error', 'output': resp.get_data(as_text=True)[:2000]}
        write_atomic(res_path, json.dumps(data))
        return 0
    except Exception as e:
        return fail('❌ agent_cli exception: %s' % e)
    finally:
        try:
            os.remove(req_path)
        except OSError:
            pass


if __name__ == '__main__':
    sys.exit(main())