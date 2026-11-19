"""Write status / doneIn / reply into in-app notes (feedback/<id>.json on the `sync` branch).

Works in a separate worktree so the shared working tree never switches branches:
  git worktree add --detach <W> origin/sync      (once; <W> e.g. in the session scratchpad)

Usage:
  python tools/notes.py <W> work <id> [<id> ...]          -> status "work"
  python tools/notes.py <W> no <id> "<reply>"              -> status "no" with a reply
  python tools/notes.py <W> done "<doneIn>" <replies.json> -> status "done"; replies.json = {"<id>": "<reply>", ...}
doneIn is the full string, e.g. "android v1.140, web v1.76".
"""
import json, subprocess, sys

W, mode = sys.argv[1], sys.argv[2]


def g(*a):
    subprocess.run(['git', '-C', W] + list(a), check=True)


g('fetch', '-q', 'origin', 'sync')
g('checkout', '-q', '--detach', 'origin/sync')
U = {}
if mode == 'work':
    for n in sys.argv[3:]:
        U[n] = ('work', None, None)
elif mode == 'no':
    U[sys.argv[3]] = ('no', None, sys.argv[4])
elif mode == 'done':
    for n, r in json.load(open(sys.argv[4], encoding='utf-8')).items():
        U[n] = ('done', sys.argv[3], r)
else:
    sys.exit('mode: work | no | done')
for n, (st, d, r) in U.items():
    p = W + '/feedback/' + n + '.json'
    o = json.load(open(p, encoding='utf-8'))
    o['status'] = st
    if d:
        o['doneIn'] = d
    if r:
        o['reply'] = r
    open(p, 'w', encoding='utf-8', newline='\n').write(json.dumps(o, ensure_ascii=False, indent=2) + '\n')
g('add', 'feedback')
g('commit', '-q', '-m', 'feedback: status ' + mode)
g('push', '-q', 'origin', 'HEAD:sync')
print('ok', mode, ', '.join(U))
