#!/usr/bin/env python3
"""
orbit-pass-sim 신뢰성 시험 파이프라인 (빌드 도구 없이 JDK + Python 만으로 돈다).

  python build.py                    # 전 단계: compile → test → coverage → mutation → pmd → spotbugs → trace
  python build.py compile test       # 원하는 단계만
  python build.py --skip mutation    # 특정 단계 빼기

단계와 실패 기준은 docs/test-plan.md §5 와 같다. 로컬·GitHub Actions·Jenkins 가 이 스크립트 하나를 공유한다.
도구 jar 는 tools/fetch.py 가 고정 버전으로 내려받는다.
"""
from __future__ import annotations

import argparse
import csv
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC_MAIN = ROOT / "src" / "main" / "java"
SRC_TEST = ROOT / "src" / "test" / "java"
SRC_CLI = ROOT / "src" / "cli" / "java"
TEST_RESOURCES = ROOT / "src" / "test" / "resources"
OUT = ROOT / "build"
OUT_MAIN = OUT / "classes"
OUT_TEST = OUT / "test-classes"
OUT_CLI = OUT / "cli-classes"
REPORTS = OUT / "reports"
TOOLS = ROOT / "tools"
JAVA_RELEASE = "21"
SEP = ";" if os.name == "nt" else ":"
ALL_STEPS = ["compile", "test", "coverage", "mutation", "pmd", "spotbugs", "trace", "bench"]


def run(cmd: list, **kw) -> subprocess.CompletedProcess:
    print("$", " ".join(str(c) for c in cmd), flush=True)
    return subprocess.run([str(c) for c in cmd], **kw)


def ensure_tools() -> dict[str, Path]:
    sys.path.insert(0, str(TOOLS))
    import fetch  # noqa: E402  (tools/fetch.py)
    return fetch.ensure_all()


def java_sources(root: Path) -> list[Path]:
    return sorted(root.rglob("*.java"))


def fail(msg: str) -> None:
    print(f"\nFAILED: {msg}", flush=True)
    sys.exit(1)


# ---------------------------------------------------------------- compile
def step_compile(tools: dict[str, Path], _args) -> None:
    shutil.rmtree(OUT, ignore_errors=True)
    OUT_MAIN.mkdir(parents=True)
    OUT_TEST.mkdir(parents=True)
    if run(["javac", "--release", JAVA_RELEASE, "-Xlint:all", "-Werror", "-d", OUT_MAIN,
            *java_sources(SRC_MAIN)]).returncode:
        fail("compile(main)")
    if run(["javac", "--release", JAVA_RELEASE, "-Xlint:all", "-Werror",
            "-cp", SEP.join([str(tools["junit"]), str(OUT_MAIN)]), "-d", OUT_TEST,
            *java_sources(SRC_TEST)]).returncode:
        fail("compile(test)")
    if TEST_RESOURCES.exists():
        shutil.copytree(TEST_RESOURCES, OUT_TEST, dirs_exist_ok=True)
    # 차분 시험용 CLI. 제품 코드가 아니라 시험 장비이므로 따로 컴파일해
    # 커버리지(JaCoCo)·뮤테이션(PIT)·정적분석(PMD·SpotBugs) 대상에서 뺀다 — 그것들은 OUT_MAIN·SRC_MAIN 만 본다.
    if SRC_CLI.exists():
        OUT_CLI.mkdir(parents=True, exist_ok=True)
        if run(["javac", "--release", JAVA_RELEASE, "-Xlint:all", "-Werror",
                "-cp", str(OUT_MAIN), "-d", OUT_CLI, *java_sources(SRC_CLI)]).returncode:
            fail("compile(cli)")


# ---------------------------------------------------------------- test (+JaCoCo agent)
def step_test(tools: dict[str, Path], _args) -> None:
    REPORTS.mkdir(parents=True, exist_ok=True)
    exec_file = OUT / "jacoco.exec"
    exec_file.unlink(missing_ok=True)
    r = run(["java", f"-javaagent:{tools['jacoco_agent']}=destfile={exec_file}",
             "-jar", tools["junit"], "execute",
             "-cp", SEP.join([str(OUT_MAIN), str(OUT_TEST)]),
             "--scan-classpath", OUT_TEST,
             "--details=summary", "--details-theme=ascii",
             "--reports-dir", REPORTS / "junit"])
    summary = junit_summary()
    (REPORTS / "test-summary.txt").write_text(summary + "\n", encoding="utf-8")
    print(summary)
    if r.returncode:
        fail("tests")


def junit_summary() -> str:
    xml = REPORTS / "junit" / "TEST-junit-jupiter.xml"
    if not xml.exists():
        return "tests: no report"
    root = ET.parse(xml).getroot()
    suite = root if root.tag == "testsuite" else root.find("testsuite")
    return (f"tests={suite.get('tests')} failures={suite.get('failures')} "
            f"errors={suite.get('errors')} skipped={suite.get('skipped')} time={suite.get('time')}s")


# ---------------------------------------------------------------- coverage gate
def step_coverage(tools: dict[str, Path], args) -> None:
    csv_path = REPORTS / "jacoco.csv"
    if run(["java", "-jar", tools["jacoco_cli"], "report", OUT / "jacoco.exec",
            "--classfiles", OUT_MAIN, "--sourcefiles", SRC_MAIN,
            "--html", REPORTS / "jacoco", "--xml", REPORTS / "jacoco.xml", "--csv", csv_path,
            "--name", "orbit-pass-sim"]).returncode:
        fail("jacoco report")
    totals = {"LINE": [0, 0], "BRANCH": [0, 0]}
    print(f"\n{'class':<26}{'line%':>8}{'branch%':>9}")
    with open(csv_path, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            for kind in totals:
                totals[kind][0] += int(row[f"{kind}_COVERED"])
                totals[kind][1] += int(row[f"{kind}_MISSED"])
            print(f"{row['CLASS']:<26}{pct(int(row['LINE_COVERED']), int(row['LINE_MISSED'])):>8.1f}"
                  f"{pct(int(row['BRANCH_COVERED']), int(row['BRANCH_MISSED'])):>9.1f}")
    line = pct(*totals["LINE"])
    branch = pct(*totals["BRANCH"])
    text = (f"line={line:.1f}% ({totals['LINE'][0]}/{sum(totals['LINE'])}) "
            f"branch={branch:.1f}% ({totals['BRANCH'][0]}/{sum(totals['BRANCH'])}) "
            f"gate: line>={args.min_line} branch>={args.min_branch}")
    print(text)
    (REPORTS / "coverage-summary.txt").write_text(text + "\n", encoding="utf-8")
    if line < args.min_line or branch < args.min_branch:
        fail(f"coverage gate: {text}")


def pct(covered: int, missed: int) -> float:
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


# ---------------------------------------------------------------- mutation (PIT)
def step_mutation(tools: dict[str, Path], args) -> None:
    pit_dir = REPORTS / "pit"
    shutil.rmtree(pit_dir, ignore_errors=True)
    cp = SEP.join(str(tools[k]) for k in
                  ("pit_cli", "pit_core", "pit_entry", "pit_junit5", "commons_text", "commons_lang3", "junit"))
    r = run(["java", "-cp", cp, "org.pitest.mutationtest.commandline.MutationCoverageReport",
             "--reportDir", pit_dir,
             "--targetClasses", "orbitsim.*",
             "--excludedClasses", "orbitsim.Main",
             "--targetTests", "orbitsim.*Test",
             "--sourceDirs", SRC_MAIN,
             "--classPath", ",".join(str(p) for p in (OUT_MAIN, OUT_TEST, tools["junit"])),
             "--mutators", "STRONGER",
             "--outputFormats", "XML,HTML,CSV",
             "--timestampedReports=false",
             "--threads", str(max(1, (os.cpu_count() or 2) // 2)),
             "--mutationThreshold", str(int(args.min_mutation))])
    killed, total, survivors = pit_results(pit_dir / "mutations.xml")
    score = 100.0 * killed / total if total else 0.0
    text = f"mutation score={score:.1f}% ({killed}/{total} detected) gate>={args.min_mutation}"
    lines = [text, "", "not detected:"] + survivors
    (REPORTS / "mutation-summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(text)
    if r.returncode or score < args.min_mutation:
        fail(text)


def pit_results(xml: Path) -> tuple[int, int, list[str]]:
    if not xml.exists():
        return 0, 0, ["(no mutations.xml)"]
    killed = total = 0
    survivors = []
    for m in ET.parse(xml).getroot().iter("mutation"):
        total += 1
        if m.get("detected") == "true":
            killed += 1
        else:
            survivors.append(f"  {m.findtext('sourceFile')}:{m.findtext('lineNumber')} "
                             f"{m.get('status')} {m.findtext('mutator', '').rsplit('.', 1)[-1]} "
                             f"— {m.findtext('description')}")
    return killed, total, survivors


# ---------------------------------------------------------------- static analysis
def step_pmd(tools: dict[str, Path], _args) -> None:
    report = REPORTS / "pmd.txt"
    REPORTS.mkdir(parents=True, exist_ok=True)
    r = run([tools["pmd_bin"], "check", "-d", SRC_MAIN, "-R", TOOLS / "pmd-ruleset.xml",
             "-f", "text", "-r", report, "--no-cache", "--no-progress"])
    text = report.read_text(encoding="utf-8").strip() if report.exists() else ""
    print(text or "PMD: 0 violations")
    if r.returncode == 4:
        fail("PMD violations")
    if r.returncode != 0:
        fail(f"PMD exited {r.returncode}")


def step_spotbugs(tools: dict[str, Path], _args) -> None:
    version = java_major_version()
    if version > 24:
        print(f"SpotBugs skipped: JVM is Java {version}, SpotBugs {tools['spotbugs'].parent.parent.name} "
              f"reads class files up to Java 24. Run with JDK 21 (CI does).")
        return
    report = REPORTS / "spotbugs.txt"
    r = run(["java", "-jar", tools["spotbugs"], "-textui", "-effort:max", "-low",
             "-output", report, OUT_MAIN])
    text = report.read_text(encoding="utf-8").strip() if report.exists() else ""
    print(text or "SpotBugs: 0 bugs")
    if r.returncode not in (0, 1) or text:
        fail("SpotBugs")


def java_major_version() -> int:
    out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
    token = out.split('"')[1] if '"' in out else "0"
    return int(token.split(".")[0])


# ---------------------------------------------------------------- traceability
def step_trace(_tools, _args) -> None:
    if run([sys.executable, TOOLS / "trace.py"]).returncode:
        fail("requirements traceability")


# ---------------------------------------------------------------- bench
def step_bench(_tools: dict[str, Path], args) -> None:
    """PassPredictor.predict 의 창 길이별 비용을 재고, **표본당 궤적 평가 횟수**로만 게이트를 건다.

    시간은 러너 성능에 흔들리고 할당은 JIT 가 없애 주는 정도(JDK 버전마다 다르다 — 같은 코드가 창 길이에 따라 41~107 B/표본)에
    흔들린다. 궤적 평가 횟수는 결정적이라 둘 다 영향을 받지 않는다. 시간·할당은 보고서에 **보고만** 하고 문턱을 두지 않는다.
    """
    cp = SEP.join([str(OUT_MAIN), str(OUT_CLI)])
    r = run(["java", "-cp", cp, "orbitsim.cli.BenchCli", "--check", str(args.max_evals_per_sample)],
            capture_output=True, text=True, encoding="utf-8")
    print(r.stdout)
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "bench.txt").write_text(r.stdout, encoding="utf-8")
    if r.returncode:
        fail(f"bench: {r.stderr.strip() or 'exit ' + str(r.returncode)}")


STEPS = {
    "compile": step_compile, "test": step_test, "coverage": step_coverage, "mutation": step_mutation,
    "pmd": step_pmd, "spotbugs": step_spotbugs, "trace": step_trace, "bench": step_bench,
}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("steps", nargs="*", help=f"any of: {' '.join(ALL_STEPS)} (default: all)")
    ap.add_argument("--skip", nargs="*", default=[])
    ap.add_argument("--min-line", type=float, default=90.0)
    ap.add_argument("--min-branch", type=float, default=85.0)
    ap.add_argument("--min-mutation", type=float, default=80.0)
    ap.add_argument("--max-evals-per-sample", type=float, default=1.10)
    args = ap.parse_args()
    unknown = [s for s in args.steps + args.skip if s not in ALL_STEPS]
    if unknown:
        ap.error(f"unknown step(s): {unknown}; choose from {ALL_STEPS}")
    steps = [s for s in (args.steps or ALL_STEPS) if s not in args.skip]
    tools = ensure_tools()
    if "compile" not in steps and not OUT_MAIN.exists():
        steps.insert(0, "compile")
    for s in steps:
        print(f"\n===== {s} =====", flush=True)
        STEPS[s](tools, args)
    print(f"\nALL STEPS PASSED: {', '.join(steps)}")


if __name__ == "__main__":
    main()
