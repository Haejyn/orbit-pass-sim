#!/usr/bin/env python3
"""README 그림을 만든다 — docs/img/*.png.

- sky_passes.png    데모 궤도(ISS 급 420 km · 51.6°)가 대전 하늘을 지나는 하루 패스 — SkyTrackCli(이 저장소의 PassPredictor)가 낸 방위각·고도각
- model_error.png   이체 · J2 궤적이 SGP4 와 벌어지는 정도 — 시험 보고서 §7.1 · §7.3 의 표
- differential.png  같은 명세로 만든 Java · C# 구현의 출력 차이 분포 — tools/differential.py 가 낸 두 CSV

사용: python build.py compile && python tools/make_readme_figures.py   (matplotlib · numpy, C# 차분 결과가 없으면 differential.py 를 먼저 돌린다)
"""
import csv
import io
import os
import subprocess
import sys
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib import font_manager

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "img"
INK, MUTED, ACCENT, GOOD, WARN, BAD = "#1f2937", "#9ca3af", "#2563eb", "#16a34a", "#f59e0b", "#ef4444"
PASS_COLOURS = ["#2563eb", "#16a34a", "#f59e0b", "#db2777", "#7c3aed", "#0891b2"]


def setup_fonts() -> None:
    for name in ("Malgun Gothic", "Noto Sans CJK KR", "NanumGothic", "AppleGothic"):
        if any(f.name == name for f in font_manager.fontManager.ttflist):
            plt.rcParams["font.family"] = name
            break
    plt.rcParams.update({"axes.spines.top": False, "axes.spines.right": False, "axes.edgecolor": MUTED,
                         "axes.labelcolor": INK, "xtick.color": INK, "ytick.color": INK, "figure.dpi": 150,
                         "axes.unicode_minus": False})


def sky_passes() -> None:
    classpath = os.pathsep.join([str(ROOT / "build" / "classes"), str(ROOT / "build" / "cli-classes")])
    out = subprocess.run(["java", "-cp", classpath, "orbitsim.cli.SkyTrackCli"], check=True, capture_output=True, text=True).stdout
    rows = list(csv.DictReader(io.StringIO(out)))
    fig = plt.figure(figsize=(5.2, 5.2))
    ax = fig.add_subplot(projection="polar")
    ax.set_theta_zero_location("N")
    ax.set_theta_direction(-1)
    ax.set_rlim(0, 90)
    ax.set_rticks([30, 60, 80])
    ax.set_yticklabels(["60°", "30°", "10°"], color=MUTED, fontsize=8)
    ax.set_xticks(np.radians([0, 90, 180, 270]))
    ax.set_xticklabels(["북", "동", "남", "서"], fontsize=10)
    ax.fill_between(np.linspace(0, 2 * np.pi, 200), 80, 90, color=MUTED, alpha=0.15)   # 마스크 10° 아래
    for number in sorted({int(r["pass"]) for r in rows}):
        track = [r for r in rows if int(r["pass"]) == number]
        az = np.radians([float(r["azimuth_deg"]) for r in track])
        radius = [90 - float(r["elevation_deg"]) for r in track]
        colour = PASS_COLOURS[(number - 1) % len(PASS_COLOURS)]
        ax.plot(az, radius, color=colour, linewidth=2.2, label=f"패스 {number} · 최대 {float(track[0]['max_elevation_deg']):.0f}°")
        ax.plot(az[0], radius[0], "o", color=colour, markersize=5)
        ax.annotate("", xy=(az[-1], radius[-1]), xytext=(az[-3], radius[-3]),
                    arrowprops={"arrowstyle": "-|>", "color": colour, "lw": 1.6})
    ax.set_title("대전 하늘 · 하루 패스 (ISS 급 궤도)", fontsize=11, color=INK, pad=16)
    ax.legend(loc="lower center", bbox_to_anchor=(0.5, -0.22), ncol=2, frameon=False, fontsize=8.5)
    fig.tight_layout()
    fig.savefig(OUT / "sky_passes.png", facecolor="white")
    plt.close(fig)


def model_error() -> None:
    # 시험 보고서 §7.1 (이체 대 SGP4 위치 차이 km) · §7.3 (24 시간 이체 대 J2)
    hours = [1 / 6, 0.5, 1, 3, 6, 12, 24]
    two_body = {"ISS · 저궤도": [2.1, 16.8, 53.6, 142.6, 275.5, 521.0, 1118.4],
                "SENTINEL-2A · 태양동기": [1.6, 7.7, 24.0, 61.7, 117.5, 220.4, 438.7],
                "XMM-NEWTON · 고타원": [0.2, 0.7, 1.2, 2.8, 6.2, 15.6, 23.0]}
    j2_24h = {"ISS · 저궤도": 875, "SENTINEL-2A · 태양동기": 1208, "XMM-NEWTON · 고타원": 20.6}
    colours = [ACCENT, WARN, GOOD]
    fig, (ax, bars) = plt.subplots(1, 2, figsize=(10, 3.4), gridspec_kw={"width_ratios": [1.3, 1]})
    for (name, values), colour in zip(two_body.items(), colours):
        ax.plot(hours, values, "o-", color=colour, linewidth=2, markersize=4, label=name)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(hours)
    ax.set_xticklabels(["10분", "30분", "1h", "3h", "6h", "12h", "24h"])
    ax.set_ylabel("SGP4 와의 위치 차이 (km)")
    ax.annotate("24 h · 1,118 km", xy=(24, 1118.4), xytext=(3.2, 900), color=ACCENT, fontsize=9,
                arrowprops={"arrowstyle": "->", "color": ACCENT})
    ax.legend(frameon=False, fontsize=8.5, loc="upper left")
    ax.set_title("이체 모델 오차 증가 (SGP4 대조)", loc="left", fontsize=11, color=INK)

    names = list(two_body)
    x = np.arange(len(names))
    bars.bar(x - 0.18, [two_body[n][-1] for n in names], width=0.36, color=MUTED, label="이체")
    bars.bar(x + 0.18, [j2_24h[n] for n in names], width=0.36, color=[GOOD, BAD, GOOD], label="이체 + J2")
    ratios = {"ISS · 저궤도": 1.28, "SENTINEL-2A · 태양동기": 0.36, "XMM-NEWTON · 고타원": 1.11}   # 보고서 §7.3 의 비
    for i, n in enumerate(names):
        ratio = ratios[n]
        bars.text(i + 0.18, j2_24h[n] * 1.15, f"×{ratio:.2f}", ha="center", fontsize=9,
                  color=GOOD if ratio > 1 else BAD, fontweight="bold")
    bars.set_yscale("log")
    bars.set_xticks(x)
    bars.set_xticklabels([n.split(" · ")[0] for n in names], fontsize=9)
    bars.set_ylabel("24 h 위치 차이 (km)")
    bars.legend(frameon=False, fontsize=8.5, loc="upper right")
    bars.set_title("J2 를 더하면 · 궤도마다 갈림", loc="left", fontsize=11, color=INK)
    fig.tight_layout()
    fig.savefig(OUT / "model_error.png", facecolor="white")
    plt.close(fig)


def differential() -> None:
    work = ROOT / "build" / "differential"
    if not (work / "java.csv").exists() or not (work / "csharp.csv").exists():
        subprocess.run([sys.executable, str(ROOT / "tools" / "differential.py"), "--check"], check=True)
    java = {r["case"]: r for r in csv.DictReader(open(work / "java.csv", encoding="utf-8"))}
    sharp = {r["case"]: r for r in csv.DictReader(open(work / "csharp.csv", encoding="utf-8"))}
    diffs, exact, rejected = [], 0, 0
    for case, a in java.items():
        b = sharp[case]
        if a["status"] != "ok":
            rejected += a["status"] == b["status"]
            continue
        for key in ("o1", "o2", "o3", "o4", "o5", "o6"):
            if a[key] == "" or b[key] == "":
                continue
            d = abs(float(a[key]) - float(b[key]))
            if not np.isfinite(d):
                continue
            if d == 0:
                exact += 1
            else:
                diffs.append(d)
    fig, ax = plt.subplots(figsize=(6.4, 3.0))
    bins = np.logspace(-19, -9, 41)
    ax.hist(diffs, bins=bins, color=ACCENT)
    ax.set_xscale("log")
    ax.set_xticks([1e-18, 1e-16, 1e-14, 1e-12, 1e-10])
    ax.set_xticklabels(["1e-18", "1e-16", "1e-14", "1e-12", "1e-10"])
    ax.minorticks_off()
    ax.axvline(max(diffs), color=WARN, linestyle="--", linewidth=1.2)
    ax.text(max(diffs), ax.get_ylim()[1] * 0.92, f" 최대 {max(diffs):.1e}", color=WARN, fontsize=9, va="top")
    ax.set_xlabel("Java 와 C# 출력의 차이 (절댓값)")
    ax.set_ylabel("출력 값 수")
    ax.set_title(f"Java ↔ C# 차분 · 사례 {len(java)} · 완전히 같은 값 {exact} · 거부 사유 일치 {rejected}",
                 loc="left", fontsize=10, color=INK)
    fig.tight_layout()
    fig.savefig(OUT / "differential.png", facecolor="white")
    plt.close(fig)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    OUT.mkdir(parents=True, exist_ok=True)
    setup_fonts()
    sky_passes()
    model_error()
    differential()
    for path in sorted(OUT.glob("*.png")):
        print(f"썼다: {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
