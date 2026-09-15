#!/usr/bin/env python3
"""
orbit-pass-sim 시험 자동화 스크립트 (빌드 도구 없이 JDK 만으로 돈다).

  python build.py            # compile → test(+JaCoCo) → coverage gate → PMD → SpotBugs(JDK 21 이상 21 이하 런타임에서만)
  python build.py test       # 단계 하나만
  python build.py --min-line 90 --min-branch 80

도구 jar 는 tools/ 아래에 없으면 Maven Central / GitHub 에서 내려받는다 (tools/fetch.py).
"""
from __future__ import annotations

import argparse
import csv
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC_MAIN = ROOT / "src" / "main" / "java"
SRC_TEST = ROOT / "src" / "test" / "java"
OUT = ROOT / "build"
OUT_MAIN = OUT / "classes"
OUT_TEST = OUT / "test-classes"
REPORTS = OUT / "reports"
TOOLS = ROOT / "tools"
JAVA_RELEASE = "21"
SEP = ";" if os.name == "nt" else ":"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    print("$", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], **kw)


def ensure_tools() -> dict[str, Path]:
    sys.path.insert(0, str(TOOLS))
    import fetch  # noqa: E402  (tools/fetch.py)
    return fetch.ensure_all()


def java_sources(root: Path) -> list[Path]:
    return sorted(root.rglob("*.java"))


def step_compile(tools: dict[str, Path]) -> None:
    shutil.rmtree(OUT, ignore_errors=True)
    OUT_MAIN.mkdir(parents=True)
    OUT_TEST.mkdir(parents=True)
    r = run(["javac", "--release", JAVA_RELEASE, "-Xlint:all", "-Werror", "-d", OUT_MAIN, *java_sources(SRC_MAIN)])
    if r.returncode:
        sys.exit("compile(main) failed")
    r = run(["javac", "--release", JAVA_RELEASE, "-Xlint:all", "-Werror",
             "-cp", SEP.join([str(tools["junit"]), str(OUT_MAIN)]), "-d", OUT_TEST, *java_sources(SRC_TEST)])
    if r.returncode:
        sys.exit("compile(test) failed")


def step_test(tools: dict[str, Path]) -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    exec_file = OUT / "jacoco.exec"
    r = run(["java", f"-javaagent:{tools['jacoco_agent']}=destfile={exec_file}",
             "-jar", tools["junit"], "execute",
             "-cp", SEP.join([str(OUT_MAIN), str(OUT_TEST)]),
             "--scan-classpath", OUT_TEST,
             "--details=tree", "--details-theme=ascii",
             "--reports-dir", REPORTS / "junit"])
    if r.returncode:
        sys.exit("tests failed")


def step_coverage(tools: dict[str, Path], min_line: float, min_branch: float) -> None:
    exec_file = OUT / "jacoco.exec"
    html = REPORTS / "jacoco"
    csv_path = REPORTS / "jacoco.csv"
    r = run(["java", "-jar", tools["jacoco_cli"], "report", exec_file,
             "--classfiles", OUT_MAIN, "--sourcefiles", SRC_MAIN,
             "--html", html, "--csv", csv_path, "--name", "orbit-pass-sim"])
    if r.returncode:
        sys.exit("jacoco report failed")
    line_missed = line_covered = br_missed = br_covered = 0
    rows = []
    with open(csv_path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            lm, lc = int(row["LINE_MISSED"]), int(row["LINE_COVERED"])
            bm, bc = int(row["BRANCH_MISSED"]), int(row["BRANCH_COVERED"])
            line_missed += lm; line_covered += lc; br_missed += bm; br_covered += bc
            rows.append((row["CLASS"], pct(lc, lm), pct(bc, bm)))
    line = pct(line_covered, line_missed)
    branch = pct(br_covered, br_missed)
    print(f"\n{'class':<22}{'line%':>8}{'branch%':>9}")
    for name, l, b in rows:
        print(f"{name:<22}{l:>8.1f}{b:>9.1f}")
    print(f"{'TOTAL':<22}{line:>8.1f}{branch:>9.1f}   (gate: line>={min_line} branch>={min_branch})")
    (REPORTS / "coverage-summary.txt").write_text(
        f"line={line:.1f} branch={branch:.1f} lines={line_covered}/{line_covered + line_missed} "
        f"branches={br_covered}/{br_covered + br_missed}\n", encoding="utf-8")
    if line < min_line or branch < min_branch:
        sys.exit(f"coverage gate failed: line {line:.1f} < {min_line} or branch {branch:.1f} < {min_branch}")


def pct(covered: int, missed: int) -> float:
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


def step_pmd(tools: dict[str, Path]) -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    pmd = tools["pmd_bin"]
    report = REPORTS / "pmd.txt"
    r = run([pmd, "check", "-d", SRC_MAIN, "-R", ROOT / "tools" / "pmd-ruleset.xml",
             "-f", "text", "-r", report, "--no-cache", "--no-progress"])
    text = report.read_text(encoding="utf-8") if report.exists() else ""
    print(text or "(PMD: no violations)")
    # PMD 는 위반이 있으면 4 를 돌려준다. 실행 자체 실패(1)와 구분한다.
    if r.returncode not in (0, 4):
        sys.exit(f"pmd failed with {r.returncode}")
    if r.returncode == 4:
        sys.exit("pmd violations found")


def step_spotbugs(tools: dict[str, Path]) -> None:
    version = java_major_version()
    if version > 24:
        print(f"(SpotBugs skipped: running JVM is Java {version}; SpotBugs 4.9 reads class files up to Java 24. CI runs it on JDK 21.)")
        return
    REPORTS.mkdir(parents=True, exist_ok=True)
    report = REPORTS / "spotbugs.txt"
    r = run(["java", "-jar", tools["spotbugs"], "-textui", "-effort:max", "-low",
             "-output", report, OUT_MAIN])
    text = report.read_text(encoding="utf-8") if report.exists() else ""
    print(text or "(SpotBugs: no bugs)")
    if r.returncode not in (0, 1):
        sys.exit(f"spotbugs failed with {r.returncode}")
    if text.strip():
        sys.exit("spotbugs found issues")


def java_major_version() -> int:
    out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
    first = out.splitlines()[0] if out else ""
    token = first.split('"')[1] if '"' in first else "0"
    return int(token.split(".")[0])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("steps", nargs="*", default=["compile", "test", "coverage", "pmd", "spotbugs"])
    ap.add_argument("--min-line", type=float, default=90.0)
    ap.add_argument("--min-branch", type=float, default=80.0)
    args = ap.parse_args()
    tools = ensure_tools()
    if args.steps != ["compile", "test", "coverage", "pmd", "spotbugs"] and "compile" not in args.steps:
        if not OUT_MAIN.exists():
            step_compile(tools)
    for s in args.steps:
        print(f"\n===== {s} =====")
        {"compile": lambda: step_compile(tools),
         "test": lambda: step_test(tools),
         "coverage": lambda: step_coverage(tools, args.min_line, args.min_branch),
         "pmd": lambda: step_pmd(tools),
         "spotbugs": lambda: step_spotbugs(tools)}[s]()
    print("\nALL STEPS PASSED")


if __name__ == "__main__":
    main()
