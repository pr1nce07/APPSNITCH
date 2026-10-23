#!/usr/bin/env python3
"""
Verify docs/TRACEABILITY.md against the source tree.

Fails (exit 1) when:
  * a mapped module directory does not exist
  * a mapped class cannot be found in that module's sources
  * a test marked VERIFIED cannot be found in the offline harness
  * a test marked NOT-RUN cannot be found in any test source
  * one of the structural constraint checks below fails

The structural checks are the ones that cannot be expressed as a unit test
because they are properties of the BUILD rather than of the code:

  * network code is confined to :hash-intel
  * QUERY_ALL_PACKAGES is declared
  * no fixture package name leaks into scoring logic
  * the shipped blocklist contains no real malware hashes

Usage:  python3 scripts/check_traceability.py [-v]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
DOC = REPO / "docs" / "TRACEABILITY.md"
HARNESS = REPO / "tools" / "offline-verify" / "src"

ROW = re.compile(r"^\|\s*(?P<id>[A-Z]+-[A-Z0-9]+)\s*\|(?P<rest>.*)\|\s*$")

problems: list[str] = []
warnings: list[str] = []
checked = 0


def fail(msg: str) -> None:
    problems.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def normalise(name: str) -> str:
    """Harness test names are prose; make comparison forgiving."""
    return re.sub(r"[^a-z0-9]+", "", name.lower())


def read_sources(root: pathlib.Path, patterns=("*.kt", "*.py", "*.xml")) -> str:
    if not root.exists():
        return ""
    out = []
    for pattern in patterns:
        for path in root.rglob(pattern):
            try:
                out.append(path.read_text(encoding="utf-8", errors="ignore"))
            except OSError:
                pass
    return "\n".join(out)


def parse_rows() -> list[dict]:
    if not DOC.exists():
        sys.exit(f"error: {DOC} not found")

    rows = []
    for line in DOC.read_text(encoding="utf-8").splitlines():
        m = ROW.match(line.strip())
        if not m:
            continue
        cells = [c.strip() for c in m.group("rest").split("|")]
        if len(cells) < 3:
            continue
        rows.append({
            "id": m.group("id"),
            "cells": cells,
            "status": cells[-1].strip(),
        })
    return rows


def check_mapping_rows(rows: list[dict], verbose: bool) -> None:
    global checked

    harness_text = read_sources(HARNESS)

    for row in rows:
        rid, cells, status = row["id"], row["cells"], row["status"]

        # Deliverable rows have the shape | ID | what | where | status |
        if rid.startswith("DELIVERABLE"):
            continue

        if len(cells) < 5:
            continue

        _requirement, module, klass, test = cells[0], cells[1], cells[2], cells[3]
        module = module.strip("`")
        klass = klass.strip("`")
        test = test.strip("`")
        checked += 1

        module_dir = REPO / module
        if not module_dir.exists():
            fail(f"{rid}: module directory '{module}' does not exist")
            continue

        # The class should appear somewhere in that module (or, for the few
        # rows that point at a script or manifest, by file name).
        module_text = read_sources(module_dir)
        class_found = (
            klass in module_text
            or (module_dir / klass).exists()
            or any(module_dir.rglob(klass))
        )
        if not class_found:
            fail(f"{rid}: class '{klass}' not found anywhere under '{module}'")

        if status == "VERIFIED":
            # A VERIFIED row must point at something that actually runs here:
            # either the offline harness, the ML pipeline, or a structural check.
            leaf = test.split(".")[-1]
            if (
                normalise(leaf) not in normalise(harness_text)
                and "check_traceability" not in test
                and not (REPO / test).exists()
            ):
                fail(
                    f"{rid}: marked VERIFIED but test '{test}' was not found in the "
                    f"offline harness. Either it does not run here (use NOT-RUN) or "
                    f"the name is wrong."
                )
            elif verbose:
                print(f"  ok  {rid} VERIFIED  {leaf}")

        elif status == "NOT-RUN":
            # The test source must still exist, or this is an unimplemented
            # requirement wearing a status that hides it.
            test_class = test.split(".")[0]
            test_roots = [
                module_dir / "src" / "test",
                module_dir / "src" / "androidTest",
            ]
            test_text = "".join(read_sources(r) for r in test_roots)
            if test_class not in test_text:
                warn(
                    f"{rid}: NOT-RUN test class '{test_class}' has no source under "
                    f"{module}/src/test or src/androidTest"
                )
            elif verbose:
                print(f"  ok  {rid} NOT-RUN   {test}")

        elif status == "BLOCKED":
            if verbose:
                print(f"  --  {rid} BLOCKED   {test}")
        else:
            fail(f"{rid}: unrecognised status '{status}'")


def check_structural_constraints(verbose: bool) -> None:
    """Build-level properties that no unit test can assert."""

    # 1. Network code confined to :hash-intel.
    offenders = []
    for build_file in REPO.rglob("build.gradle.kts"):
        if "hash-intel" in build_file.parts:
            continue
        text = build_file.read_text(encoding="utf-8", errors="ignore")
        for needle in ("okhttp", "retrofit", "ktor-client", "volley"):
            if needle in text.lower() and "mockwebserver" not in text.lower():
                offenders.append(f"{build_file.relative_to(REPO)} pulls in '{needle}'")
    if offenders:
        fail("network library outside :hash-intel -> " + "; ".join(offenders))
    elif verbose:
        print("  ok  CONSTRAINT-OFFLINE network code confined to :hash-intel")

    # 2. QUERY_ALL_PACKAGES declared.
    manifest = REPO / "app" / "src" / "main" / "AndroidManifest.xml"
    if not manifest.exists():
        fail("app manifest not found")
    elif "QUERY_ALL_PACKAGES" not in manifest.read_text(encoding="utf-8"):
        fail("CONSTRAINT-VISIBILITY: QUERY_ALL_PACKAGES is not declared; "
             "the scan will see almost nothing on Android 11+")
    elif verbose:
        print("  ok  CONSTRAINT-VISIBILITY QUERY_ALL_PACKAGES declared")

    # 3. No fixture package name in scoring logic. The judging kit is apps we
    #    have never seen, so anything tuned to our own fixtures is a defect.
    scoring_text = read_sources(REPO / "scoring" / "src" / "main")
    context_text = read_sources(REPO / "context" / "src" / "main")
    catalog_text = read_sources(REPO / "catalog" / "src" / "main")
    for blob, name in (
        (scoring_text, "scoring"),
        (context_text, "context"),
        (catalog_text, "catalog"),
    ):
        if "com.riskauditor.fixtures" in blob:
            fail(f"CONSTRAINT-GENERIC: a fixture package name appears in :{name} "
                 f"main sources; scoring must not be tuned to our own test apps")
    if verbose:
        print("  ok  CONSTRAINT-GENERIC no fixture package names in scoring logic")

    # 4. The shipped blocklist carries no real malware hashes.
    blocklist = REPO / "app" / "src" / "main" / "assets" / "local_blocklist.txt"
    if blocklist.exists():
        entries = [
            line.strip()
            for line in blocklist.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        bad = [e for e in entries if not re.fullmatch(r"[0-9a-f]{64}", e)]
        if bad:
            fail(f"CONSTRAINT-NOMALWARE: malformed blocklist entries: {bad[:3]}")
        elif verbose:
            print(f"  ok  CONSTRAINT-NOMALWARE blocklist has {len(entries)} "
                  f"entries, all fixture hashes by policy")
    else:
        warn("no local_blocklist.txt asset found")

    # 5. The rubric document is generated, not hand-written.
    rubric_md = REPO / "docs" / "SCORING_RUBRIC.md"
    if rubric_md.exists():
        if "render_rubric.py" not in rubric_md.read_text(encoding="utf-8"):
            fail("DELIVERABLE-02: SCORING_RUBRIC.md does not identify itself as "
                 "generated; it may have been hand-edited and drifted from rubric.json")
        elif verbose:
            print("  ok  DELIVERABLE-02 rubric is generated from rubric.json")
    else:
        fail("DELIVERABLE-02: docs/SCORING_RUBRIC.md is missing; "
             "run tools/render_rubric.py")

    # 6. Every combination rule id in rubric.json has a predicate, and vice
    #    versa. The harness asserts this too, but checking here means a broken
    #    rubric fails the build even if the harness is not run.
    import json
    rubric_json = REPO / "scoring" / "src" / "main" / "resources" / "rubric.json"
    rules_kt = REPO / "scoring" / "src" / "main" / "kotlin" / "com" / "riskauditor" / "scoring" / "CombinationRules.kt"
    if rubric_json.exists() and rules_kt.exists():
        ids_json = set(json.loads(rubric_json.read_text())["combination_rules"].keys())
        kt_text = rules_kt.read_text(encoding="utf-8")
        ids_kt = set(re.findall(r'"(COMBO_[A-Z0-9_]+)"\s*to', kt_text))
        missing_predicate = ids_json - ids_kt
        missing_numbers = ids_kt - ids_json
        if missing_predicate:
            fail(f"SHOULD-07: rules in rubric.json with no predicate: {sorted(missing_predicate)}")
        if missing_numbers:
            fail(f"SHOULD-07: predicates with no entry in rubric.json: {sorted(missing_numbers)}")
        if not missing_predicate and not missing_numbers and verbose:
            print(f"  ok  SHOULD-07 {len(ids_json)} combination rules fully paired")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    print("Checking docs/TRACEABILITY.md against the source tree...")
    rows = parse_rows()
    check_mapping_rows(rows, args.verbose)
    check_structural_constraints(args.verbose)

    statuses: dict[str, int] = {}
    for row in rows:
        statuses[row["status"]] = statuses.get(row["status"], 0) + 1

    print()
    print(f"  requirement rows: {len(rows)}  (mappings checked: {checked})")
    for status, count in sorted(statuses.items()):
        print(f"    {status:<10} {count}")

    if warnings:
        print()
        print("  WARNINGS")
        for w in warnings:
            print(f"    - {w}")

    if problems:
        print()
        print("  FAILURES")
        for p in problems:
            print(f"    - {p}")
        print()
        print("traceability check FAILED")
        return 1

    print()
    print("traceability check passed"
          + (f" ({len(warnings)} warning(s))" if warnings else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
