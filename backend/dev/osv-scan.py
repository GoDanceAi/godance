#!/usr/bin/env python3
"""Check the backend's resolved Maven dependencies against OSV (osv.dev).

The query from the appendix of docs/dependency-triage-2026-09.md, which CI
runs after `mvn verify` (from backend/):

    mvn -B -q dependency:list -DoutputFile=target/deps.txt -DoutputAbsoluteArtifactFilename=false
    python3 dev/osv-scan.py target/deps.txt

The resolved list includes BOM-managed and transitive versions, which static
pom.xml parsing can't see. Every advisory is listed with its severity and
fixed versions. Exits 1 if any is HIGH or CRITICAL, or has no severity rating
(so it can't be shown to be lower), like `npm audit --audit-level=high` in the
frontend jobs. Standard library only.
"""
import json
import re
import sys
import time
import urllib.request

API = "https://api.osv.dev/v1"
FAILING = {"HIGH", "CRITICAL", "UNRATED"}
# "   group:artifact:type[:classifier]:version:scope[ -- module ...]"
LINE = re.compile(r"^\s+([\w.\-]+):([\w.\-]+):[\w\-]+(?::[\w\-]+)?:([\w.\-]+):(\w+)")


def call(path, body=None):
    """GET (or POST, with a body) against the OSV API, retrying transient failures."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, data=data, headers={"Content-Type": "application/json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                return json.load(res)
        except OSError:  # URLError, HTTPError, timeouts
            if attempt == 3:
                raise
            time.sleep(2 ** attempt)


_vulns = {}


def vuln(vid):
    if vid not in _vulns:
        _vulns[vid] = call(f"/vulns/{vid}")
    return _vulns[vid]


def severity(v):
    """GitHub's rating (LOW, MODERATE, HIGH, CRITICAL), via a GHSA alias if needed."""
    for entry in [v] + [vuln(a) for a in v.get("aliases", []) if a.startswith("GHSA-")]:
        rating = (entry.get("database_specific") or {}).get("severity")
        if rating:
            return rating.upper()
    return "UNRATED"


def fixed_in(v, name):
    return sorted({event["fixed"]
                   for affected in v.get("affected", []) if affected.get("package", {}).get("name") == name
                   for rng in affected.get("ranges", [])
                   for event in rng.get("events", []) if "fixed" in event})


def main(path):
    deps = {}
    with open(path) as f:
        for line in f:
            m = LINE.match(line)
            if m:
                deps[f"{m[1]}:{m[2]}"] = (m[3], m[4])
    if not deps:
        sys.exit(f"no dependencies parsed from {path}")

    items = sorted(deps.items())
    results = call("/querybatch", {"queries": [
        {"package": {"name": name, "ecosystem": "Maven"}, "version": version}
        for name, (version, _) in items]})["results"]

    found = failing = 0
    for (name, (version, scope)), result in zip(items, results):
        for vid in sorted({x["id"] for x in result.get("vulns", [])}):
            v = vuln(vid)
            if v.get("withdrawn"):
                continue
            rating = severity(v)
            found += 1
            failing += rating in FAILING
            cves = ", ".join(a for a in v.get("aliases", []) if a.startswith("CVE-"))
            summary = v.get("summary") or (v.get("details") or "").split("\n")[0]
            print(f"{rating:<8} {name}@{version} [{scope}] {vid} {cves}".rstrip())
            print(f"         {summary[:110]}")
            print(f"         fixed in: {', '.join(fixed_in(v, name)) or 'no fix listed'}")

    print(f"{len(items)} artifacts checked: {found} advisories, {failing} high, critical or unrated")
    return 1 if failing else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: osv-scan.py <output of mvn dependency:list -DoutputFile=...>")
    sys.exit(main(sys.argv[1]))
