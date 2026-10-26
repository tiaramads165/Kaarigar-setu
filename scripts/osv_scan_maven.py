#!/usr/bin/env python3
"""Dependency vulnerability scan for the Maven modules, using the public OSV database (no API key, no NVD download).

    python3 scripts/osv_scan_maven.py [module ...]        default: kaarigarsetu-java kaarigarsetu-moderator-console

Resolves every RUNTIME dependency with `mvn dependency:list`, asks api.osv.dev about each exact version, prints the advisory
ids and exits 1 if any dependency has one. A lookup that fails is an ERROR and also fails the scan (a scan that could not
look must never read as a clean one). The Python service is scanned separately with `pip-audit -r requirements.txt`.
"""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULES = sys.argv[1:] or ["kaarigarsetu-java", "kaarigarsetu-moderator-console"]
COORDINATE = re.compile(r"^\s+([\w.\-]+):([\w.\-]+):jar:(?:[\w.\-]+:)?([^:\s]+):\w+")


def dependencies(module: str) -> set[tuple[str, str, str]]:
    with tempfile.NamedTemporaryFile("r", suffix=".txt") as out:
        subprocess.run(["mvn", "-q", "-B", "dependency:list", "-DincludeScope=runtime", f"-DoutputFile={out.name}"],
                       cwd=ROOT / module, check=True, stdout=subprocess.DEVNULL)
        return {m.groups() for line in out for m in [COORDINATE.match(line)] if m}


def advisories(group: str, artifact: str, version: str) -> list[str]:
    body = json.dumps({"package": {"name": f"{group}:{artifact}", "ecosystem": "Maven"}, "version": version})
    # curl (system trust store) rather than urllib: some Python builds ship without CA certificates.
    result = subprocess.run(["curl", "-sS", "--fail", "--max-time", "30", "-X", "POST", "https://api.osv.dev/v1/query",
                             "-H", "Content-Type: application/json", "-d", body], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"curl exit {result.returncode}")
    return sorted({v["id"] for v in json.loads(result.stdout).get("vulns", [])})


def main() -> int:
    problems = checked = 0
    for module in MODULES:
        for group, artifact, version in sorted(dependencies(module)):
            checked += 1
            try:
                found = advisories(group, artifact, version)
            except Exception as error:  # noqa: BLE001 - any failure to look up is a failure of the scan
                problems += 1
                print(f"ERROR {module}: {group}:{artifact}:{version} lookup failed: {error}")
                continue
            if found:
                problems += 1
                print(f"VULN  {module}: {group}:{artifact}:{version} {', '.join(found)}")
    print(f"checked {checked} dependencies, {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
