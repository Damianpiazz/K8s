#!/usr/bin/env python3
"""
test-yaml-parse.py — walks the repository and parses every .yaml/.yml file
with PyYAML, reporting invalid files and duplicate mapping keys. Exits non-zero
on any error. Runs on Windows without extra dependencies beyond pyyaml.

Usage:
    python tests/manifests/test-yaml-parse.py [<repo-root>]

    <repo-root> defaults to the repository root (two levels above this file).
    If pyyaml is missing, the script prints an install hint and exits 1.
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Directories that are never YAML (or whose YAML is not ours to validate).
SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "venv", "target", "build", ".atl", ".opencode"}


def find_yaml_files(root: Path) -> list[Path]:
    files = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.suffix.lower() in (".yaml", ".yml"):
            files.append(path)
    return sorted(files)


def has_duplicate_keys(doc) -> list[str]:
    """Returns the list of duplicated scalar keys found in one mapping doc."""
    import yaml  # local import: only needed when we actually have files

    duplicates: list[str] = []

    def walk(node):
        if isinstance(node, yaml.MappingNode):
            seen = {}
            for key_node, _value_node in node.value:
                if isinstance(key_node, yaml.ScalarNode):
                    key = key_node.value
                    if key in seen:
                        duplicates.append(f"{key_node.start_mark.line + 1}:{key}")
                    seen[key] = True
            for _key_node, value_node in node.value:
                walk(value_node)
        elif isinstance(node, yaml.SequenceNode):
            for item in node.value:
                walk(item)

    for node in doc:
        walk(node)
    return duplicates


def main() -> int:
    try:
        import yaml
    except ImportError:
        print("ERROR: PyYAML is required. Install it with:  python -m pip install pyyaml")
        return 1

    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else REPO_ROOT
    files = find_yaml_files(root)

    invalid = []
    error_by_file = {}
    dup_by_file = {}

    for f in files:
        rel = f.relative_to(root)
        issues = []
        try:
            # 1. Syntax: every document must parse.
            with f.open("r", encoding="utf-8") as fh:
                docs = list(yaml.safe_load_all(fh))
            # 2. Duplicate mapping keys (a common, silent Kubernetes mistake).
            with f.open("r", encoding="utf-8") as fh:
                composed = list(yaml.compose_all(fh))
            dups = has_duplicate_keys(composed)
            if dups:
                dup_by_file[str(rel)] = dups
                issues.append(f"duplicate key(s): {', '.join(dups)}")
        except Exception as exc:  # YAMLError or others — report the message
            error_by_file[str(rel)] = str(exc).splitlines()[0]
            issues.append(f"invalid YAML: {exc}")

        if issues:
            invalid.append(rel)

    # ── Report ──────────────────────────────────────────────────────────────
    print(f"Scanning {root}")
    print(f"YAML files found: {len(files)}")
    for rel in invalid:
        if str(rel) in error_by_file:
            print(f"  ERROR  {rel}: {error_by_file[str(rel)]}")
        else:
            print(f"  DUPKEY {rel}: {', '.join(dup_by_file[str(rel)])}")

    ok = len(files) - len(invalid)
    print(f"OK: {ok} file(s) valid, {len(invalid)} file(s) with issues")
    if invalid:
        print("YAML PARSE CHECK FAILED")
        return 1
    print("YAML PARSE CHECK PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())