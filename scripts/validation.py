"""Reuse an authenticated, successful test step only when all app inputs match.

This avoids rerunning simulator UI tests for packaging/docs-only fixes. Any change
under app source, tests, project settings, assets or entitlements runs the selected unit and UI smoke suites.
All inputs must match the authenticated prior test run byte for byte.
"""
import json
import os
from pathlib import Path
import subprocess
import urllib.request

VALIDATED_SOURCE = "851f72c4b45f354463e95f5f419796e234f0c188"
VALIDATED_RUN = 39377109031
INPUTS = ["Shared", "PanelWidgets", "Panel", "PanelTests", "PanelUITests", "project.yml", "Config"]


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
                         test_step="Test on simulator", conclusion="success", checked_inputs=INPUTS,
                         byte_identical=identical)
except Exception as error:
    # Any other app, test, project or entitlement change executes the full selected suites.
    print("Could not verify prior test evidence; executing selected simulator tests:", type(error).__name__)

Path("build").mkdir(exist_ok=True)
Path("build/validation-proof.json").write_text(json.dumps(proof, indent=2) + "\n")
with open(os.environ["GITHUB_OUTPUT"], "a") as output:
    output.write("reuse=" + str(reuse).lower() + "\n")
print("Reusing authenticated passed functional/UI tests: byte-identical inputs" if reuse else "Running unit suite and UI smoke tour.")
