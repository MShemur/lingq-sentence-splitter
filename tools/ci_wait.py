import sys, time, json, urllib.request
sha = sys.argv[1]
for _ in range(40):
    try:
        r = json.load(urllib.request.urlopen('https://api.github.com/repos/MShemur/lingq-sentence-splitter/actions/runs?per_page=5'))
        for x in r['workflow_runs']:
            if x['head_sha'].startswith(sha) and x['status'] == 'completed':
                print(x['run_number'], x['conclusion'], x['jobs_url']); sys.exit(0)
    except Exception as e:
        pass
    time.sleep(60)
print('timeout')
