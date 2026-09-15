#!/usr/bin/env python3
"""
독립 기준값 생성기 — 이체 운동방정식 r'' = -μ r / |r|³ 를 고전 RK4 로 수치 적분한다.

Java 전파기는 케플러 방정식을 푸는 **해석해**다. 여기서는 케플러 방정식을 전혀 쓰지 않고
초기 상태에서 미분방정식을 직접 적분하므로, 두 결과가 맞으면 서로 다른 방법이 같은 답을 낸 것이다.

  python tools/gen_golden_rk4.py           # → src/test/resources/golden/rk4_states.csv
  python tools/gen_golden_rk4.py --check   # 다시 계산해 저장된 파일과 허용 오차(1e-6 km, 1e-9 km/s) 안인지 확인

표준 라이브러리만 쓴다. OS 마다 libm 의 sin·cos 마지막 자리가 다를 수 있어 재현성은 문자열이 아니라 수치로 비교한다.
"""
from __future__ import annotations

import csv
import math
import sys
from pathlib import Path

MU = 398600.4418          # km^3/s^2 — Java Constants.MU_EARTH 와 같은 값
DT = 0.5                  # s — 근지점 부근 고타원 궤도에서도 0.01 km 기준을 여유 있게 지키는 간격
OUT = Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "golden" / "rk4_states.csv"
LF = chr(10)

# name, a[km], e, i[deg], raan[deg], argp[deg]  — 에포크는 근지점(M0 = 0)
ORBITS = [
    ("LEO_ISS",      6798.137, 0.0,    51.6,  30.0,   0.0),
    ("SSO",          7078.137, 0.0012, 98.2, 200.0,  90.0),
    ("GPS_MEO",     26560.0,   0.01,   55.0, 120.0,  45.0),
    ("MOLNIYA",     26600.0,   0.72,   63.4, 330.0, 270.0),
    ("GTO",         24400.0,   0.73,    7.0,  15.0, 178.0),
]


def rot_z(v, ang):
    c, s = math.cos(ang), math.sin(ang)
    return (c * v[0] - s * v[1], s * v[0] + c * v[1], v[2])


def rot_x(v, ang):
    c, s = math.cos(ang), math.sin(ang)
    return (v[0], c * v[1] - s * v[2], s * v[1] + c * v[2])


def initial_state(a, e, i, raan, argp):
    """근지점 상태: 위치는 근지점 방향, 속도는 그에 수직 (vis-viva)."""
    rp = a * (1 - e)
    vp = math.sqrt(MU * (1 + e) / rp)
    r = (rp, 0.0, 0.0)
    v = (0.0, vp, 0.0)
    for f, ang in ((rot_z, argp), (rot_x, i), (rot_z, raan)):
        r, v = f(r, ang), f(v, ang)
    return r, v


def accel(r):
    d = math.sqrt(r[0] ** 2 + r[1] ** 2 + r[2] ** 2)
    k = -MU / d ** 3
    return (k * r[0], k * r[1], k * r[2])


def add(a, b, s):
    return (a[0] + s * b[0], a[1] + s * b[1], a[2] + s * b[2])


def rk4_step(r, v, h):
    a1 = accel(r)
    r2, v2 = add(r, v, h / 2), add(v, a1, h / 2)
    a2 = accel(r2)
    r3, v3 = add(r, v2, h / 2), add(v, a2, h / 2)
    a3 = accel(r3)
    r4, v4 = add(r, v3, h), add(v, a3, h)
    a4 = accel(r4)
    r_next = tuple(r[k] + h / 6 * (v[k] + 2 * v2[k] + 2 * v3[k] + v4[k]) for k in range(3))
    v_next = tuple(v[k] + h / 6 * (a1[k] + 2 * a2[k] + 2 * a3[k] + a4[k]) for k in range(3))
    return r_next, v_next


def generate() -> list[list]:
    rows = []
    for name, a, e, i_deg, raan_deg, argp_deg in ORBITS:
        period = 2 * math.pi * math.sqrt(a ** 3 / MU)
        checkpoints = sorted({0.0, 600.0, 1800.0, round(period / 4, 1), round(period / 2, 1), round(period, 1)})
        r, v = initial_state(a, e, math.radians(i_deg), math.radians(raan_deg), math.radians(argp_deg))
        t = 0.0
        for target in checkpoints:
            while t < target - 1e-9:
                h = min(DT, target - t)
                r, v = rk4_step(r, v, h)
                t += h
            rows.append([name, a, e, i_deg, raan_deg, argp_deg, f"{target:.1f}",
                         *(f"{x:.9f}" for x in r), *(f"{x:.12f}" for x in v)])
        print(f"{name:<9} T={period:9.1f}s  checkpoints={len(checkpoints)}")
    return rows


def check(rows: list[list]) -> int:
    with open(OUT, encoding="utf-8", newline="") as f:
        stored = list(csv.reader(f))[1:]
    if len(stored) != len(rows):
        print(f"row count differs: stored {len(stored)} vs regenerated {len(rows)}")
        return 1
    worst_r = worst_v = 0.0
    for old, new in zip(stored, rows):
        if old[:7] != [str(x) for x in new[:7]]:
            print(f"key columns differ: {old[:7]} vs {new[:7]}")
            return 1
        worst_r = max(worst_r, max(abs(float(old[k]) - float(new[k])) for k in range(7, 10)))
        worst_v = max(worst_v, max(abs(float(old[k]) - float(new[k])) for k in range(10, 13)))
    ok = worst_r <= 1e-6 and worst_v <= 1e-9
    print(f"golden check: max |dr|={worst_r:.3e} km, max |dv|={worst_v:.3e} km/s -> {'OK' if ok else 'MISMATCH'}")
    return 0 if ok else 1


def main() -> None:
    rows = generate()
    if "--check" in sys.argv:
        sys.exit(check(rows))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUT, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f, lineterminator=LF)
        w.writerow(["name", "a_km", "e", "i_deg", "raan_deg", "argp_deg", "t_s",
                    "x_km", "y_km", "z_km", "vx_kms", "vy_kms", "vz_kms"])
        w.writerows(rows)
    print(f"wrote {len(rows)} rows -> {OUT}")


if __name__ == "__main__":
    main()
