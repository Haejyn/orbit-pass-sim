#!/usr/bin/env python3
"""
요구사항 추적성 검사 — docs/requirements.md 의 REQ-* 와 시험 코드의 @Tag("REQ-*"), JUnit 결과를 대조한다.

  python tools/trace.py      # → docs/traceability.md, 문제가 있으면 exit 1

실패 조건
  - 명세에 있는데 시험이 하나도 없는 요구사항
  - 시험 코드가 가리키는데 명세에 없는 요구사항 ID (오타·삭제된 요구사항)
  - 연결된 시험 중 실패가 있는 요구사항
"""
from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / "docs" / "requirements.md"
TESTS = ROOT / "src" / "test" / "java"
JUNIT_XML = ROOT / "build" / "reports" / "junit" / "TEST-junit-jupiter.xml"
OUT = ROOT / "docs" / "traceability.md"

REQ_ROW = re.compile(r"^\|\s*(REQ-[A-Z]+-\d+)\s*\|\s*(.+?)\s*\|")
TAG = re.compile(r'@Tag\("(REQ-[A-Z]+-\d+)"\)')
METHOD = re.compile(r"^\s*(?:public\s+|private\s+|protected\s+)?void\s+(\w+)\s*\(")
CLASS = re.compile(r"^\s*(?:public\s+)?(?:final\s+)?class\s+(\w+)")


def read_spec() -> dict[str, str]:
    reqs = {}
    for line in SPEC.read_text(encoding="utf-8").splitlines():
        m = REQ_ROW.match(line)
        if m:
            reqs[m.group(1)] = re.sub(r"\*\*", "", m.group(2))
    return reqs


def read_tags() -> dict[str, list[tuple[str, str]]]:
    """REQ → [(class, method)]. @Tag 는 바로 뒤에 오는 메서드에 붙는다."""
    links: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for path in sorted(TESTS.rglob("*Test.java")):
        cls = path.stem
        pending: list[str] = []
        for line in path.read_text(encoding="utf-8").splitlines():
            c = CLASS.match(line)
            if c:
                cls = c.group(1)
            pending += TAG.findall(line)
            m = METHOD.match(line)
            if m and pending:
                for req in pending:
                    links[req].append((cls, m.group(1)))
                pending = []
    return links


def read_results() -> dict[tuple[str, str], list[bool]]:
    """(class, method) → 실행 결과 목록 (파라미터화 시험은 여러 개)."""
    results: dict[tuple[str, str], list[bool]] = defaultdict(list)
    if not JUNIT_XML.exists():
        sys.exit(f"JUnit report not found: {JUNIT_XML} — run `python build.py test` first")
    for case in ET.parse(JUNIT_XML).getroot().iter("testcase"):
        cls = case.get("classname", "").split(".")[-1]
        method = case.get("name", "").split("(")[0]
        failed = case.find("failure") is not None or case.find("error") is not None
        results[(cls, method)].append(not failed)
    return results


def main() -> int:
    # Windows 콘솔 기본 인코딩(cp1252)은 한글·화살표를 못 찍어 UnicodeEncodeError 로 빌드가 깨졌다 (CI windows-latest)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    spec = read_spec()
    links = read_tags()
    results = read_results()
    problems: list[str] = []

    unknown = sorted(set(links) - set(spec))
    for req in unknown:
        problems.append(f"{req}: 시험이 가리키지만 명세에 없음 ({', '.join(f'{c}#{m}' for c, m in links[req])})")

    lines = [
        "# 요구사항 추적 매트릭스 (자동 생성)",
        "",
        "> `python tools/trace.py` 가 `docs/requirements.md` · 시험 코드의 `@Tag` · JUnit 결과로 만든다. 손으로 고치지 않는다.",
        "",
        "| 요구사항 | 내용 | 시험 | 실행 | 판정 |",
        "|---|---|---|---|---|",
    ]
    verified = 0
    for req, text in spec.items():
        tests = links.get(req, [])
        runs = [ok for t in tests for ok in results.get(t, [])]
        if not tests:
            verdict = "❌ 시험 없음"
            problems.append(f"{req}: 연결된 시험 없음")
        elif not runs:
            verdict = "❌ 실행 기록 없음"
            problems.append(f"{req}: 시험이 실행되지 않음")
        elif all(runs):
            verdict = "✅ 통과"
            verified += 1
        else:
            verdict = f"❌ 실패 {runs.count(False)}"
            problems.append(f"{req}: 실패 {runs.count(False)} / {len(runs)}")
        names = "<br>".join(f"`{c}#{m}`" for c, m in tests) or "—"
        lines.append(f"| {req} | {text} | {names} | {sum(runs)}/{len(runs)} | {verdict} |")

    lines += ["", f"**요구사항 {len(spec)}개 중 {verified}개 검증됨.**", ""]
    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"traceability: {verified}/{len(spec)} requirements verified → {OUT.relative_to(ROOT)}")
    for p in problems:
        print("  ✗", p)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
