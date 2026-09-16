#!/usr/bin/env python3
"""
Java ↔ C# 차분 시험 — 같은 명세로 만든 두 구현에 같은 입력을 넣고 결과를 대조한다.

  python tools/differential.py            # 두 CLI 를 돌려 대조하고 골든을 새로 쓴다
  python tools/differential.py --check    # 대조 + 저장된 골든과 일치하는지 확인 (CI)
  python tools/differential.py --java-only  # C# 없이 Java 출력만 골든과 대조

무엇을 대조하나
  - 수치: |a − b| <= ATOL + RTOL·|b|  (ATOL=1e-9, RTOL=1e-12)
    두 구현 모두 IEEE754 double 로 같은 식을 계산하므로 차이는 ulp 수준이어야 한다.
    km 규모(1e4)에서 1 ulp ≈ 1.8e-12 km 이므로 상대항이, 0 근처에서는 절대항이 기준이 된다.
  - 판정: ok / reject(인수 오류) / diverge(비수렴) / state(영벡터) 토큰이 정확히 같아야 한다.
    "두 구현이 같은 입력을 같은 이유로 거부하는가" 가 수치만큼 중요하다.
  - 패스 개수는 정수라 완전 일치를 요구한다.

경계·특이값을 일부러 섞는다 — 언어 간 차이는 보통 여기서 난다
  (atan2 의 부호, % 의 음수 처리, −0.0, NaN·±∞ 파싱, 반올림).
"""
from __future__ import annotations

import csv
import math
import os
import random
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "src" / "test" / "resources" / "golden" / "differential_vectors.csv"
WORK = ROOT / "build" / "differential"
JAVA_MAIN = ROOT / "build" / "classes"
JAVA_CLI = ROOT / "build" / "cli-classes"
CS_CLI = ROOT / "csharp" / "OrbitSim.Cli"
SEP = ";" if os.name == "nt" else ":"
LF = chr(10)

ATOL = 1e-9
RTOL = 1e-12
MAX_OUTPUTS = 6

COLUMNS = ["case", "kind", "a_km", "e", "i_rad", "raan_rad", "argp_rad", "m0_rad", "t_s",
           "lat_deg", "lon_deg", "alt_km", "theta0_rad", "min_elev_deg", "start_s", "end_s",
           "step_s", "angle_rad"]
IDX = {name: i for i, name in enumerate(COLUMNS)}

R_EARTH = 6378.137
MU = 398600.4418
TWO_PI = 2 * math.pi

# 다섯 궤도 영역 — golden/rk4_states.csv 와 같은 성격으로 고른다
ORBITS = [
    ("leo", 6798.137, 0.0, 51.6, 30.0, 0.0),
    ("sso", 7078.137, 0.0012, 98.2, 200.0, 90.0),
    ("meo", 26560.0, 0.01, 55.0, 120.0, 45.0),
    ("molniya", 26600.0, 0.72, 63.4, 330.0, 270.0),
    ("gto", 24400.0, 0.73, 7.0, 15.0, 178.0),
]


def row(case: str, kind: str, **kw) -> list[str]:
    cells = [""] * len(COLUMNS)
    cells[IDX["case"]] = case
    cells[IDX["kind"]] = kind
    for key, value in kw.items():
        cells[IDX[key]] = fmt(value)
    return cells


def fmt(v: float) -> str:
    """Java `Double.toString` · C# `ToString("R")` 가 모두 되읽는 표기."""
    if isinstance(v, str):
        return v
    if math.isnan(v):
        return "NaN"
    if math.isinf(v):
        return "Infinity" if v > 0 else "-Infinity"
    return repr(v)


def boundary_rows() -> list[list[str]]:
    """경계·특이값. 언어 간 차이가 가장 잘 드러나는 자리들."""
    out: list[list[str]] = []

    # 각도 정규화 — −0.0 과 아주 작은 음수가 2π 로 반올림되는 자리 (D-5 가 났던 곳)
    for i, a in enumerate([0.0, -0.0, -1e-16, -1e-9, 1e-16, TWO_PI, -TWO_PI, math.pi, -math.pi,
                           TWO_PI - 1e-16, 2 * TWO_PI + 1e-13, 1e15, -1e15, 1e300, -1e300,
                           float("nan"), float("inf"), float("-inf")]):
        out.append(row(f"norm{i:02d}", "normalize", angle_rad=a))

    # 케플러 — 초기값 분기(e<0.8), 큰 |M|, 거부 경계
    keplers = [(0.0, 0.0), (math.pi, 0.0), (1.0, 0.7999), (1.0, 0.8), (1.0, 0.8001),
               (math.pi, 0.999), (-math.pi, 0.999), (1e3, 0.1), (1e6, 0.1), (1e12, 0.3),
               (1e15, 0.3), (-1e12, 0.3), (0.0, -0.0), (0.5, 1.0), (0.5, -1e-18),
               (float("nan"), 0.1), (0.5, float("nan")), (float("inf"), 0.1)]
    for i, (m, e) in enumerate(keplers):
        out.append(row(f"kep{i:02d}", "kepler", m0_rad=m, e=e))

    # 궤도 요소 — 근지점이 지표에 딱 닿는 경계, i 경계, e 경계
    elems = [(R_EARTH, 0.0, 0.0), (R_EARTH + 1e-9, 0.0, 0.0), (7000.0, 0.0, 0.0),
             (7000.0, 0.0, math.pi), (7000.0, 0.0, math.pi + 1e-16), (7000.0, 0.0, -1e-16),
             (7000.0, 1 - 1e-16, 0.5), (7000.0, 1.0, 0.5), (7000.0, -0.0, 0.5),
             (float("nan"), 0.0, 0.0), (float("inf"), 0.0, 0.0), (26600.0, 0.72, 1.1)]
    for i, (a, e, inc) in enumerate(elems):
        out.append(row(f"elm{i:02d}", "elements", a_km=a, e=e, i_rad=inc, raan_rad=0.3,
                       argp_rad=0.2, m0_rad=0.1))

    # 전파 — 시간 경계와 비유한 시간
    for i, t in enumerate([0.0, -0.0, 600.0, -600.0, 1e7, -1e7, 1e9,
                           float("nan"), float("inf"), float("-inf")]):
        out.append(row(f"stt{i:02d}", "state", a_km=6798.137, e=0.001, i_rad=0.9,
                       raan_rad=0.5, argp_rad=0.25, m0_rad=0.1, t_s=t))

    # 관측각 — 극·적도·날짜변경선, 고도 경계, 정북 근처 방위각
    stations = [(90.0, 0.0, 0.0), (-90.0, 0.0, 0.0), (0.0, 0.0, 0.0), (0.0, 180.0, 0.0),
                (0.0, -180.0, 0.0), (36.35, 127.38, 0.07), (36.35, 127.38, -1.0),
                (36.35, 127.38, -1.0000001), (90.0000001, 0.0, 0.0), (0.0, 180.0000001, 0.0),
                (0.0, 0.0, float("nan"))]
    for i, (lat, lon, alt) in enumerate(stations):
        out.append(row(f"lok{i:02d}", "look", a_km=6798.137, e=0.001, i_rad=0.9, raan_rad=0.5,
                       argp_rad=0.25, m0_rad=0.1, t_s=1234.5, lat_deg=lat, lon_deg=lon,
                       alt_km=alt, theta0_rad=1.7))

    # 패스 — 창·간격·마스크 경계, 정지궤도(항상 보임)
    passes = [
        ("iss_day", 6798.137, 0.001, 0.9, 10.0, 0.0, 86400.0, 30.0),
        ("iss_mask0", 6798.137, 0.001, 0.9, 0.0, 0.0, 43200.0, 30.0),
        ("iss_mask_lo", 6798.137, 0.001, 0.9, -90.0, 0.0, 21600.0, 60.0),
        ("iss_mask_hi", 6798.137, 0.001, 0.9, 90.0, 0.0, 21600.0, 60.0),
        ("iss_mask_near", 6798.137, 0.001, 0.9, 89.999, 0.0, 21600.0, 60.0),
        ("geo_always", 42164.0, 0.0, 0.0, 5.0, 0.0, 43200.0, 300.0),
        ("win_zero", 6798.137, 0.001, 0.9, 10.0, 0.0, 0.0, 10.0),
        ("win_neg", 6798.137, 0.001, 0.9, 10.0, 100.0, 0.0, 10.0),
        ("step_big", 6798.137, 0.001, 0.9, 10.0, 0.0, 3600.0, 3600.1),
        ("step_exact", 6798.137, 0.001, 0.9, 10.0, 0.0, 3600.0, 3600.0),
        ("step_zero", 6798.137, 0.001, 0.9, 10.0, 0.0, 3600.0, 0.0),
        ("step_nan", 6798.137, 0.001, 0.9, 10.0, 0.0, 3600.0, float("nan")),
        ("end_nan", 6798.137, 0.001, 0.9, 10.0, 0.0, float("nan"), 10.0),
    ]
    for name, a, e, inc, mask, start, end, step in passes:
        out.append(row(f"pas_{name}", "pass", a_km=a, e=e, i_rad=inc, raan_rad=0.5, argp_rad=0.0,
                       m0_rad=0.0, lat_deg=36.35, lon_deg=127.38, alt_km=0.07, theta0_rad=1.7,
                       min_elev_deg=mask, start_s=start, end_s=end, step_s=step))
    return out


def random_rows(seed: int) -> list[list[str]]:
    rnd = random.Random(seed)
    out: list[list[str]] = []

    for i in range(120):
        e = rnd.choice([0.0, rnd.uniform(0, 0.3), rnd.uniform(0.3, 0.9), rnd.uniform(0.9, 0.999)])
        m = rnd.choice([rnd.uniform(-math.pi, math.pi), rnd.uniform(-1e6, 1e6), rnd.uniform(-1e12, 1e12)])
        out.append(row(f"rkep{i:03d}", "kepler", m0_rad=m, e=e))

    for i in range(120):
        name, a, e, inc, raan, argp = ORBITS[i % len(ORBITS)]
        period = TWO_PI * math.sqrt(a ** 3 / MU)
        t = rnd.choice([rnd.uniform(0, period), rnd.uniform(-period, 0),
                        rnd.uniform(-1e6, 1e6), rnd.uniform(0, 3.15e7)])
        out.append(row(f"rstt{i:03d}_{name}", "state", a_km=a, e=e, i_rad=math.radians(inc),
                       raan_rad=math.radians(raan), argp_rad=math.radians(argp),
                       m0_rad=rnd.uniform(-math.pi, math.pi), t_s=t))

    for i in range(120):
        name, a, e, inc, raan, argp = ORBITS[i % len(ORBITS)]
        out.append(row(f"rlok{i:03d}_{name}", "look", a_km=a, e=e, i_rad=math.radians(inc),
                       raan_rad=math.radians(raan), argp_rad=math.radians(argp),
                       m0_rad=rnd.uniform(-math.pi, math.pi), t_s=rnd.uniform(-1e5, 1e5),
                       lat_deg=rnd.uniform(-90, 90), lon_deg=rnd.uniform(-180, 180),
                       alt_km=rnd.uniform(-0.4, 4.0), theta0_rad=rnd.uniform(0, TWO_PI)))

    for i in range(60):
        out.append(row(f"rnrm{i:03d}", "normalize",
                       angle_rad=rnd.choice([rnd.uniform(-TWO_PI, TWO_PI), rnd.uniform(-1e6, 1e6),
                                             rnd.uniform(-1e15, 1e15)])))

    # 패스는 한 건마다 창 전체를 훑어 비싸다 — 창을 짧게 잡고 건수를 줄인다
    for i in range(18):
        name, a, e, inc, raan, argp = ORBITS[i % len(ORBITS)]
        out.append(row(f"rpas{i:03d}_{name}", "pass", a_km=a, e=e, i_rad=math.radians(inc),
                       raan_rad=math.radians(raan), argp_rad=math.radians(argp),
                       m0_rad=rnd.uniform(-math.pi, math.pi),
                       lat_deg=rnd.uniform(-80, 80), lon_deg=rnd.uniform(-180, 180),
                       alt_km=rnd.uniform(0, 2.0), theta0_rad=rnd.uniform(0, TWO_PI),
                       min_elev_deg=rnd.choice([0.0, 5.0, 10.0, 25.0]),
                       start_s=0.0, end_s=21600.0, step_s=rnd.choice([30.0, 60.0])))
    return out


def build_inputs() -> list[list[str]]:
    return boundary_rows() + random_rows(20260916)


def write_csv(path: Path, header: list[str], rows: list[list[str]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator=LF)
        w.writerow(header)
        w.writerows(rows)


def read_csv(path: Path) -> tuple[list[str], list[list[str]]]:
    with open(path, encoding="utf-8", newline="") as f:
        rows = list(csv.reader(f))
    return rows[0], rows[1:]


def run_java(inp: Path, out: Path) -> None:
    if not JAVA_CLI.exists():
        sys.exit(f"{JAVA_CLI} 가 없다 — `python build.py compile` 을 먼저 돌린다")
    cmd = ["java", "-cp", SEP.join([str(JAVA_MAIN), str(JAVA_CLI)]), "orbitsim.cli.DiffCli", str(inp), str(out)]
    print("$", " ".join(cmd), flush=True)
    if subprocess.run(cmd, check=False).returncode:
        sys.exit("Java CLI 실패")


def run_csharp(inp: Path, out: Path) -> None:
    build = ["dotnet", "build", str(CS_CLI), "-c", "Release", "--nologo", "-v", "quiet"]
    print("$", " ".join(build), flush=True)
    if subprocess.run(build, check=False).returncode:
        sys.exit("C# 빌드 실패")
    dll = CS_CLI / "bin" / "Release" / "net10.0" / "OrbitSim.Cli.dll"
    cmd = ["dotnet", str(dll), str(inp), str(out)]
    print("$", " ".join(cmd), flush=True)
    if subprocess.run(cmd, check=False).returncode:
        sys.exit("C# CLI 실패")


def close(a: str, b: str) -> tuple[bool, float]:
    """빈 칸·NaN·±∞ 는 표기가 정확히 같아야 하고, 유한값은 허용 오차로 본다."""
    if a == b:
        return True, 0.0
    if a == "" or b == "":
        return False, math.inf
    x, y = float(a), float(b)
    if math.isnan(x) or math.isnan(y):
        return math.isnan(x) and math.isnan(y), math.inf
    if math.isinf(x) or math.isinf(y):
        return x == y, math.inf
    diff = abs(x - y)
    return diff <= ATOL + RTOL * abs(y), diff


def compare(label: str, left: list[list[str]], right: list[list[str]], kinds: dict[str, str]) -> tuple[int, float]:
    """left(기준) 와 right 를 대조해 불일치 수와 최대 차이를 돌려준다."""
    if len(left) != len(right):
        print(f"{label}: 행 수가 다르다 {len(left)} vs {len(right)}")
        return 1, math.inf
    mismatches = 0
    worst = 0.0
    for a, b in zip(left, right):
        case = a[0]
        if a[1] != b[1]:
            print(f"  ✗ {case} [{kinds.get(case, '?')}] 판정이 다르다: {a[1]} vs {b[1]}")
            mismatches += 1
            continue
        for k in range(2, 2 + MAX_OUTPUTS):
            ok, diff = close(a[k], b[k])
            if math.isfinite(diff):
                worst = max(worst, diff)
            if not ok:
                print(f"  ✗ {case} [{kinds.get(case, '?')}] o{k - 1}: {a[k]} vs {b[k]} (차이 {diff:.3e})")
                mismatches += 1
    return mismatches, worst


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    check = "--check" in sys.argv
    java_only = "--java-only" in sys.argv

    inputs = build_inputs()
    kinds = {r[0]: r[1] for r in inputs}
    WORK.mkdir(parents=True, exist_ok=True)
    input_path = WORK / "input.csv"
    write_csv(input_path, COLUMNS, inputs)
    print(f"사례 {len(inputs)} 건 ({input_path})")

    java_out = WORK / "java.csv"
    run_java(input_path, java_out)
    _, java_rows = read_csv(java_out)

    failures = 0
    if not java_only:
        cs_out = WORK / "csharp.csv"
        run_csharp(input_path, cs_out)
        _, cs_rows = read_csv(cs_out)
        print("\n=== Java vs C# ===")
        mismatches, worst = compare("java-vs-csharp", java_rows, cs_rows, kinds)
        print(f"불일치 {mismatches} 건 · 최대 차이 {worst:.3e} (허용 {ATOL:g} + {RTOL:g}·|b|)")
        failures += mismatches

    golden_rows = [inp + jrow[1:] for inp, jrow in zip(inputs, java_rows)]
    golden_header = COLUMNS + ["status"] + [f"o{k}" for k in range(1, MAX_OUTPUTS + 1)]
    if check:
        if not GOLDEN.exists():
            print(f"골든이 없다: {GOLDEN}")
            return 1
        _, stored = read_csv(GOLDEN)
        # 골든은 입력 열(18)까지 들고 있다. CLI 출력 배치(case, status, o1..o6)로 잘라서 견준다.
        stored_cmp = [[r[0], *r[len(COLUMNS):len(COLUMNS) + 1 + MAX_OUTPUTS]] for r in stored]
        print("\n=== 저장된 골든 vs 지금 Java 출력 ===")
        mismatches, worst = compare("golden", stored_cmp, java_rows, kinds)
        print(f"불일치 {mismatches} 건 · 최대 차이 {worst:.3e}")
        failures += mismatches
    else:
        write_csv(GOLDEN, golden_header, golden_rows)
        print(f"\n골든 {len(golden_rows)} 행 -> {GOLDEN}")

    report = ROOT / "build" / "reports" / "differential.txt"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(f"cases={len(inputs)} mismatches={failures}\n", encoding="utf-8")
    print(f"\n{'OK' if failures == 0 else 'MISMATCH'}: 사례 {len(inputs)} 건, 불일치 {failures} 건")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
