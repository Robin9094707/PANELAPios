"""Reuse an authenticated, successful test step only when all app inputs match.

This avoids rerunning simulator UI tests for packaging/docs-only fixes. Any change
under app source, tests, project settings, assets or entitlements runs the selected unit and UI smoke suites.
"""
import json
import os
from pathlib import Path
import subprocess
import urllib.request

VALIDATED_SOURCE = "0d704657e54866a0aaf856d89b721b8626f9635c"
VALIDATED_RUN = 39326270370
INPUTS = ["Panel", "PanelTests", "PanelUITests", "project.yml", "Config"]

def api(path):
    request = urllib.request.Request(
        "https://api.github.com/repos/" + os.environ["GITHUB_REPOSITORY"] + path,
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                 "Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)

reuse = False
proof = {"head": os.environ["GITHUB_SHA"], "reused": False}
try:
    identical = subprocess.run(["git", "diff", "--quiet", VALIDATED_SOURCE, "HEAD", "--", *INPUTS], check=False).returncode == 0
    if identical:
        run = api(f"/actions/runs/{VALIDATED_RUN}")
        jobs = api(f"/actions/runs/{VALIDATED_RUN}/jobs")
        successful = any(step["name"] == "Test on simulator" and step.get("conclusion") == "success"
                         for job in jobs["jobs"] for step in job.get("steps", []))
        reuse = run["head_sha"] == VALIDATED_SOURCE and successful
        if reuse:
            proof.update(reused=True, validated_source=VALIDATED_SOURCE, validated_run=VALIDATED_RUN,
                         test_step="Test on simulator", conclusion="success", identical_inputs=INPUTS)
except Exception as error:
    # The fallback always executes the complete test suite.
    print("Could not verify prior test evidence; executing selected simulator tests:", type(error).__name__)

Path("build").mkdir(exist_ok=True)
Path("build/validation-proof.json").write_text(json.dumps(proof, indent=2) + "\n")
with open(os.environ["GITHUB_OUTPUT"], "a") as output:
    output.write("reuse=" + str(reuse).lower() + "\n")
print("Reusing successful simulator tests for byte-identical app inputs." if reuse else "Running unit suite and UI smoke tour.")
