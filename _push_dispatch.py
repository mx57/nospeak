import os, urllib.request, json, subprocess

with open("/root/workspace/nospeak/.ghtoken") as f:
    tok = f.read().strip()

req = urllib.request.Request("https://api.github.com/user",
    headers={"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json"})
with urllib.request.urlopen(req) as r:
    print("login:", json.load(r).get("login"))

user = "x-access-token"
host = "github.com"
owner = "mx57"
repo = "nospeak"
remote = f"https://{user}:{tok}@{host}/{owner}/{repo}.git"

res = subprocess.run(["git", "-c", "credential.helper=", "push", remote, "fix/android-test-keystore"],
    capture_output=True, text=True, cwd="/root/workspace/nospeak")
print("push RC:", res.returncode)
print("OUT:", res.stdout[-400:], "ERR:", res.stderr[-400:])

url = f"https://api.github.com/repos/{owner}/{repo}/actions/workflows/android-apk.yaml/dispatches"
payload = {"ref": "fix/android-test-keystore", "inputs": {}}
dreq = urllib.request.Request(url, data=json.dumps(payload).encode(),
    headers={"Authorization": f"Bearer {tok}", "Accept": "application/vnd.github+json",
             "Content-Type": "application/json"}, method="POST")
try:
    with urllib.request.urlopen(dreq) as r:
        print("dispatch status:", r.status)
except urllib.error.HTTPError as e:
    print("dispatch HTTP", e.code, e.read().decode()[:200])
