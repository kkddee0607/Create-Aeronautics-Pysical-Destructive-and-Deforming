#!/usr/bin/env python3
"""Static guard for contradictory, immutable contact-face coverage assertions.

This is NOT a substitute for Java compilation or JUnit execution. Run before
packing the ZIP; the GitHub Actions workflows are deliberately unchanged.
"""
from pathlib import Path
import re
import sys

root = Path(__file__).resolve().parents[1]
tests = sorted((root / "src/test/java").rglob("*.java"))
pattern = re.compile(
    r"\bassert(True|False)\s*\(\s*"
    r"([A-Za-z_]\w*\.covers\((?:[^()]|\([^()]*\))*\))",
    re.DOTALL,
)
contradictions = []
for path in tests:
    # A Result/Footprint's covers method is read-only; this check is limited
    # to that exact contract so stateful assertions (reserve, prepare, etc.)
    # are not incorrectly rejected as contradictions.
    source = path.read_text(encoding="utf-8")
    for section in re.split(r"(?=@Test\b)", source):
        if not section.startswith("@Test"):
            continue
        expectations = {}
        for match in pattern.finditer(section):
            key = re.sub(r"\s+", "", match.group(2))
            expectations.setdefault(key, set()).add(match.group(1))
        for expression, values in expectations.items():
            if len(values) > 1:
                contradictions.append(f"{path.relative_to(root)}: {expression}")

count = sum(len(re.findall(r"@Test\b", p.read_text(encoding="utf-8"))) for p in tests)
if count < 792:
    contradictions.append(f"JUnit @Test declarations unexpectedly fell below duperFix baseline 792: {count}")
if contradictions:
    print("Static assertion contract FAILED:", *contradictions, sep="\n - ", file=sys.stderr)
    sys.exit(1)
print(f"Static contact-face assertion guard PASS: {count} @Test declarations, {len(tests)} test files")
