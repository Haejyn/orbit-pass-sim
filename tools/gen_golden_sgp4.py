#!/usr/bin/env python3
"""
독립 기준값 생성기 — 공개 TLE 를 SGP4/SDP4 로 전파해 이체 모델과 대조할 궤적을 만든다.

이 저장소의 전파기는 **섭동 없는 이체 해석해**다. SGP4 는 J2 등 섭동과 (심우주 궤도에서는)
달·태양 섭동까지 담은 표준 모델이므로, 같은 초기 상태에서 두 궤적이 벌어지는 정도가 곧
"이체 모델을 몇 시간까지 믿을 수 있나" 에 대한 답이다.

  python tools/gen_golden_sgp4.py           # → src/test/resources/golden/sgp4_*.csv
  python tools/gen_golden_sgp4.py --check   # 다시 계산해 저장된 파일과 허용 오차 안인지 확인

대조 설계
  - TLE 의 **평균 요소를 그대로 이체 요소로 쓰지 않는다.** 평균→접촉 변환 오차가 결과를 오염시킨다.
  - 에포크에서 SGP4 가 준 **상태 벡터(위치·속도)** 를 고전 궤도 요소로 바꿔 이체 전파의 초기값으로 준다.
    그러면 t=0 에서 두 궤적이 정확히 같고, 이후 벌어지는 차이는 모델 차이만 남는다.
  - 패스 대조용 궤적은 위치와 **속도를 함께** 저장한다. Java 쪽에서 3차 Hermite 로 보간하면
    보간 오차가 무시할 수준이라, 재는 값이 보간 오차와 섞이지 않는다.

SGP4 는 TEME 좌표를 낸다. 이 저장소의 전파기는 관성계를 하나로만 다루므로 프레임 이름은 맞추지 않는다.
두 궤적을 **같은 좌표계로 놓고 상대 차이**만 재고, 패스도 같은 Java 코드로 구하므로 이 근사는 결론에 영향이 없다.

표준 라이브러리 + sgp4 패키지만 쓴다. 패키지가 없으면 사유를 찍고 건너뛴다 (build.py 의 SpotBugs 와 같은 방식).
"""
from __future__ import annotations

import csv
import math
import sys
from pathlib import Path

MU = 398600.4418          # km^3/s^2 — Java Constants.MU_EARTH 와 같은 값
ROOT = Path(__file__).resolve().parent.parent
GOLDEN = ROOT / "src" / "test" / "resources" / "golden"
TLE_FILE = GOLDEN / "tle_fixtures.txt"
OUT_STATES = GOLDEN / "sgp4_states.csv"
OUT_TRACKS = GOLDEN / "sgp4_tracks.csv"
OUT_LONG = GOLDEN / "sgp4_long_track.csv"
LF = chr(10)

# 이체·J2 전파를 얼마나 오래 믿을 수 있나 — 10 분부터 30 일까지
CHECKPOINTS_S = [0.0, 600.0, 1800.0, 3600.0, 10800.0, 21600.0, 43200.0, 86400.0,
                 259200.0, 604800.0, 2592000.0]

# 패스 대조용 조밀 궤적: 지상국 패스가 자주 오는 저궤도 둘만. 60 초 간격 + Hermite 보간.
TRACK_SATS = ("ISS (ZARYA)", "SENTINEL-2A")
TRACK_STEP_S = 60.0
TRACK_SPAN_S = 86400.0

# 장기 창: 패스 개수가 언제부터 어긋나는지 보려면 하루로는 모자란다. 파일 크기 때문에
# 위성 하나(ISS)만, 간격을 120 초로 넓힌다. 보간 오차가 재려는 차이에 섞이지 않는지는
# 아래 hermite_error_km() 가 실제로 재서 찍는다 (가정하지 않는다).
LONG_TRACK_SAT = "ISS (ZARYA)"
LONG_TRACK_STEP_S = 120.0
LONG_TRACK_SPAN_S = 7.0 * 86400.0

POS_TOL_KM = 1e-6
VEL_TOL_KMS = 1e-9
ECC_TOL = 1e-12
ANGLE_TOL_DEG = 1e-9      # 관측된 OS 간 차이는 1e-12 deg — 1,000 배 여유


def read_tles(path: Path) -> list[tuple[str, str, str]]:
    lines = [ln.rstrip() for ln in path.read_text(encoding="utf-8").splitlines()]
    lines = [ln for ln in lines if ln.strip() and not ln.lstrip().startswith("#")]
    if len(lines) % 3 != 0:
        raise SystemExit(f"TLE fixture must be groups of 3 lines, got {len(lines)}")
    return [(lines[i], lines[i + 1], lines[i + 2]) for i in range(0, len(lines), 3)]


def rv_to_elements(r: tuple, v: tuple) -> dict:
    """상태 벡터 → 고전 궤도 요소 (접촉 요소). 각도는 deg, 길이는 km."""
    rn = math.sqrt(sum(x * x for x in r))
    vn = math.sqrt(sum(x * x for x in v))
    h = (r[1] * v[2] - r[2] * v[1], r[2] * v[0] - r[0] * v[2], r[0] * v[1] - r[1] * v[0])
    hn = math.sqrt(sum(x * x for x in h))
    n = (-h[1], h[0], 0.0)                      # k̂ × h
    nn = math.sqrt(sum(x * x for x in n))
    rdotv = sum(a * b for a, b in zip(r, v))
    e_vec = tuple(((vn * vn - MU / rn) * r[k] - rdotv * v[k]) / MU for k in range(3))
    e = math.sqrt(sum(x * x for x in e_vec))
    energy = vn * vn / 2.0 - MU / rn
    a = -MU / (2.0 * energy)
    i = math.acos(max(-1.0, min(1.0, h[2] / hn)))
    raan = math.acos(max(-1.0, min(1.0, n[0] / nn)))
    if n[1] < 0.0:
        raan = 2.0 * math.pi - raan
    argp = math.acos(max(-1.0, min(1.0, sum(a_ * b for a_, b in zip(n, e_vec)) / (nn * e))))
    if e_vec[2] < 0.0:
        argp = 2.0 * math.pi - argp
    nu = math.acos(max(-1.0, min(1.0, sum(a_ * b for a_, b in zip(e_vec, r)) / (e * rn))))
    if rdotv < 0.0:
        nu = 2.0 * math.pi - nu
    ecc_anom = 2.0 * math.atan2(math.sqrt(1.0 - e) * math.sin(nu / 2.0),
                                math.sqrt(1.0 + e) * math.cos(nu / 2.0))
    m0 = ecc_anom - e * math.sin(ecc_anom)
    return {
        "a_km": a, "e": e,
        "i_deg": math.degrees(i), "raan_deg": math.degrees(raan),
        "argp_deg": math.degrees(argp), "m0_deg": math.degrees(m0) % 360.0,
    }


def gmst_rad(jd: float, fr: float) -> float:
    """에포크의 그리니치 평균 항성시 [rad] (IAU-82 다항식 — SGP4 의 TEME→ECEF 가 쓰는 것과 같은 식).

    Java 쪽 `Frames.earthRotationAngle(theta0, t)` 에 넣어야 패스 시각이 실제 대전 상공 시각이 된다.
    이것을 0 으로 두면 지구 자전 위상이 임의가 되어, 두 전파기 비교는 여전히 공정하지만
    패스 시각 자체는 실제와 무관한 값이 된다.
    """
    tut1 = ((jd - 2451545.0) + fr) / 36525.0
    sec = (67310.54841
           + (876600.0 * 3600.0 + 8640184.812866) * tut1
           + 0.093104 * tut1 * tut1
           - 6.2e-6 * tut1 * tut1 * tut1)
    return math.radians((sec % 86400.0) / 240.0) % (2.0 * math.pi)


def hermite_point(r0, v0, r1, v1, h: float, s: float) -> tuple:
    """두 표본 사이를 3차 Hermite 로 보간한 위치. Java 쪽 GoldenSgp4Test.hermite 와 같은 식이다."""
    s2 = s * s
    s3 = s2 * s
    return tuple(r0[k] * (2 * s3 - 3 * s2 + 1)
                 + v0[k] * ((s3 - 2 * s2 + s) * h)
                 + r1[k] * (-2 * s3 + 3 * s2)
                 + v1[k] * ((s3 - s2) * h)
                 for k in range(3))


def propagate(sat, jd: float, fr: float, seconds: float):
    """에포크에서 seconds 초 뒤의 SGP4 상태. 오류 코드가 있으면 멈춘다."""
    days = seconds / 86400.0
    err, r, v = sat.sgp4(jd, fr + days)
    if err != 0:
        raise SystemExit(f"sgp4 error code {err} at t={seconds}s")
    return r, v


def generate() -> tuple[list[list], list[list], list[list]]:
    from sgp4.api import Satrec

    states, tracks, long_rows = [], [], []
    for name, l1, l2 in read_tles(TLE_FILE):
        sat = Satrec.twoline2rv(l1, l2)
        jd, fr = sat.jdsatepoch, sat.jdsatepochF
        r0, v0 = propagate(sat, jd, fr, 0.0)
        el = rv_to_elements(r0, v0)
        period_min = 2.0 * math.pi * math.sqrt(el["a_km"] ** 3 / MU) / 60.0

        theta0 = gmst_rad(jd, fr)
        for t in CHECKPOINTS_S:
            r, _ = propagate(sat, jd, fr, t)
            # 요소는 자릿수를 넉넉히 적는다 — 각도를 1e-6 deg 로 끊으면 6800 km 궤도에서
            # 약 0.1 km 의 반올림 오차가 생겨, 재려는 모델 차이에 섞인다.
            states.append([name,
                           f"{el['a_km']:.9f}", f"{el['e']:.12f}", f"{el['i_deg']:.12f}",
                           f"{el['raan_deg']:.12f}", f"{el['argp_deg']:.12f}", f"{el['m0_deg']:.12f}",
                           f"{theta0:.12f}", f"{t:.1f}", *(f"{x:.9f}" for x in r)])

        if name in TRACK_SATS:
            steps = int(round(TRACK_SPAN_S / TRACK_STEP_S)) + 1
            for k in range(steps):
                t = k * TRACK_STEP_S
                r, v = propagate(sat, jd, fr, t)
                tracks.append([name, f"{t:.1f}", *(f"{x:.9f}" for x in r), *(f"{x:.12f}" for x in v)])

        if name == LONG_TRACK_SAT:
            steps = int(round(LONG_TRACK_SPAN_S / LONG_TRACK_STEP_S)) + 1
            worst_interp = 0.0
            prev = None
            for k in range(steps):
                t = k * LONG_TRACK_STEP_S
                r, v = propagate(sat, jd, fr, t)
                long_rows.append([name, f"{t:.1f}", *(f"{x:.9f}" for x in r), *(f"{x:.12f}" for x in v)])
                if prev is not None:
                    # 표본 사이 한가운데를 보간값과 참값으로 비교한다 — 간격을 넓힌 대가를 가정하지 않고 잰다
                    mid = hermite_point(prev[0], prev[1], r, v, LONG_TRACK_STEP_S, 0.5)
                    truth, _ = propagate(sat, jd, fr, t - LONG_TRACK_STEP_S / 2.0)
                    worst_interp = max(worst_interp, math.dist(mid, truth))
                prev = (r, v)
            print(f"{name:<14} long track {steps} rows @ {LONG_TRACK_STEP_S:.0f}s"
                  f"  hermite midpoint error max {worst_interp * 1000.0:.1f} m")

        print(f"{name:<14} a={el['a_km']:10.2f} km  e={el['e']:.6f}  i={el['i_deg']:6.2f}deg  T={period_min:8.2f} min")
    return states, tracks, long_rows


def compare(path: Path, rows: list[list], key_idx: list[int],
            tol: list[tuple[str, int, int, float]]) -> int:
    """저장본과 다시 계산한 값을 대조한다.

    **계산으로 얻은 열은 문자열로 비교하지 않는다.** OS 마다 libm 의 마지막 자리가 달라
    같은 입력에서도 끝자리가 흔들린다 (실제로 XMM 의 평균근점이각이 Windows 와 Linux 에서
    1e-12 deg 차이가 났다). 이름·시각처럼 우리가 적어 넣은 값만 문자열로 맞춘다.
    """
    with open(path, encoding="utf-8", newline="") as f:
        stored = list(csv.reader(f))[1:]
    if len(stored) != len(rows):
        print(f"{path.name}: row count differs: stored {len(stored)} vs regenerated {len(rows)}")
        return 1
    worst = [0.0] * len(tol)
    for old, new in zip(stored, rows):
        for k in key_idx:
            if old[k] != str(new[k]):
                print(f"{path.name}: key column {k} differs: {old[k]} vs {new[k]}")
                return 1
        for idx, (_, lo, hi, _t) in enumerate(tol):
            worst[idx] = max(worst[idx], max(abs(float(old[k]) - float(new[k])) for k in range(lo, hi)))
    ok = all(w <= t[3] for w, t in zip(worst, tol))
    detail = "  ".join(f"{t[0]} max|d|={w:.3e} (tol {t[3]:.0e})" for w, t in zip(worst, tol))
    print(f"{path.name}: {detail} -> {'OK' if ok else 'MISMATCH'}")
    return 0 if ok else 1


def write(path: Path, header: list[str], rows: list[list]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator=LF)
        w.writerow(header)
        w.writerows(rows)
    print(f"wrote {len(rows)} rows -> {path}")


def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    try:
        import sgp4  # noqa: F401
    except ImportError:
        print("sgp4 package not installed — skipping SGP4 golden generation (pip install sgp4)")
        return
    states, tracks, long_rows = generate()
    track_header = ["name", "t_s", "x_km", "y_km", "z_km", "vx_kms", "vy_kms", "vz_kms"]
    if "--check" in sys.argv:
        # 이름(0)·시각(8)만 문자열로 맞추고, 요소와 위치는 수치로 비교한다
        rc = compare(OUT_STATES, states, [0, 8], [
            ("a", 1, 2, POS_TOL_KM), ("e", 2, 3, ECC_TOL),
            ("angles", 3, 8, ANGLE_TOL_DEG), ("r", 9, 12, POS_TOL_KM)])
        rc |= compare(OUT_TRACKS, tracks, [0, 1], [
            ("r", 2, 5, POS_TOL_KM), ("v", 5, 8, VEL_TOL_KMS)])
        rc |= compare(OUT_LONG, long_rows, [0, 1], [
            ("r", 2, 5, POS_TOL_KM), ("v", 5, 8, VEL_TOL_KMS)])
        sys.exit(rc)
    write(OUT_STATES, ["name", "a_km", "e", "i_deg", "raan_deg", "argp_deg", "m0_deg", "theta0_rad",
                       "t_s", "x_km", "y_km", "z_km"], states)
    write(OUT_TRACKS, track_header, tracks)
    write(OUT_LONG, track_header, long_rows)


if __name__ == "__main__":
    main()
