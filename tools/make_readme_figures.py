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
import urllib.request
import zipfile
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib import font_manager

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "img"
PRETENDARD_URL = "https://github.com/orioncactus/pretendard/releases/download/v1.3.9/Pretendard-1.3.9.zip"
BLACK, GRAY, LIGHT, BLUE, RED = "#111111", "#6b6b6b", "#d9d9d9", "#1f4e79", "#b03a2e"
PASS_STYLES = [("-", "o"), ("--", "s"), (":", "^"), ("-.", "D"), ((0, (5, 1.5, 1, 1.5)), "v")]


def setup_style() -> None:
    fonts = ROOT / "build" / "fonts"
    candidates = [Path(os.environ["PRETENDARD_DIR"])] if "PRETENDARD_DIR" in os.environ else []
    candidates.append(fonts)
    files = [f for d in candidates if d.exists() for f in d.glob("Pretendard-*.ttf")]
    if not files:
        fonts.mkdir(parents=True, exist_ok=True)
        archive = fonts / "Pretendard.zip"
        urllib.request.urlretrieve(PRETENDARD_URL, archive)
        with zipfile.ZipFile(archive) as z:
            for name in z.namelist():
                if name.startswith("public/static/alternative/") and name.endswith(".ttf"):
                    (fonts / Path(name).name).write_bytes(z.read(name))
        files = list(fonts.glob("Pretendard-*.ttf"))
    for f in files:
        font_manager.fontManager.addfont(str(f))
    plt.rcParams.update({
        "font.family": "Pretendard", "font.size": 9, "axes.unicode_minus": False,
        "axes.linewidth": 0.8, "axes.edgecolor": BLACK, "axes.labelcolor": BLACK,
        "xtick.direction": "in", "ytick.direction": "in", "xtick.top": True, "ytick.right": True,
        "xtick.major.size": 3.5, "ytick.major.size": 3.5, "xtick.minor.size": 2, "ytick.minor.size": 2,
        "xtick.color": BLACK, "ytick.color": BLACK,
        "legend.frameon": False, "legend.fontsize": 8.5, "figure.dpi": 200, "savefig.bbox": "tight",
        "savefig.pad_inches": 0.05, "lines.linewidth": 1.0,
    })


def panel(ax, letter: str) -> None:
    ax.text(-0.02, 1.02, f"({letter})", transform=ax.transAxes, ha="right", va="bottom", fontsize=10, fontweight="semibold")


def sky_passes() -> None:
    classpath = os.pathsep.join([str(ROOT / "build" / "classes"), str(ROOT / "build" / "cli-classes")])
    out = subprocess.run(["java", "-cp", classpath, "orbitsim.cli.SkyTrackCli"], check=True, capture_output=True, text=True).stdout
    rows = list(csv.DictReader(io.StringIO(out)))
    fig = plt.figure(figsize=(3.6, 3.9))
    ax = fig.add_subplot(projection="polar")
    ax.set_theta_zero_location("N")
    ax.set_theta_direction(-1)
    ax.set_rlim(0, 90)
    ax.set_rticks([30, 60, 80])
    ax.set_yticklabels(["60°", "30°", "10°"], color=GRAY, fontsize=7)
    ax.set_rlabel_position(22)
    ax.set_xticks(np.radians([0, 90, 180, 270]))
    ax.set_xticklabels(["N", "E", "S", "W"])
    ax.grid(color=LIGHT, lw=0.6)
    ax.spines["polar"].set_linewidth(0.8)
    ax.fill_between(np.linspace(0, 2 * np.pi, 200), 80, 90, color=LIGHT, alpha=0.5, lw=0)
    for number in sorted({int(r["pass"]) for r in rows}):
        track = [r for r in rows if int(r["pass"]) == number]
        az = np.radians([float(r["azimuth_deg"]) for r in track])
        radius = [90 - float(r["elevation_deg"]) for r in track]
        line, mark = PASS_STYLES[(number - 1) % len(PASS_STYLES)]
        ax.plot(az, radius, color=BLACK, ls=line, lw=1.0,
                label=f"P{number}  최대 {float(track[0]['max_elevation_deg']):.1f}°")
        ax.plot(az[0], radius[0], marker=mark, color=BLACK, mfc="white", ms=4, ls="none")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.06), ncol=3, fontsize=7.5, handlelength=2.2, columnspacing=1.0)
    fig.savefig(OUT / "sky_passes.png", facecolor="white")
    plt.close(fig)


def model_error() -> None:
    # 시험 보고서 §7.1 (이체 대 SGP4 위치 차이 km) · §7.3 (24 시간 이체 대 J2)
    hours = [1 / 6, 0.5, 1, 3, 6, 12, 24]
    two_body = {"ISS": [2.1, 16.8, 53.6, 142.6, 275.5, 521.0, 1118.4],
                "SENTINEL-2A": [1.6, 7.7, 24.0, 61.7, 117.5, 220.4, 438.7],
                "XMM-NEWTON": [0.2, 0.7, 1.2, 2.8, 6.2, 15.6, 23.0]}
    j2_24h = [875, 1208, 20.6]
    ratios = [1.28, 0.36, 1.11]   # 보고서 §7.3 의 비 (이체 ÷ J2)
    styles = [("o", "-"), ("s", "--"), ("^", ":")]
    fig, (ax, bars) = plt.subplots(1, 2, figsize=(7.2, 2.6), gridspec_kw={"width_ratios": [1.15, 1.1], "wspace": 0.35})
    for (name, values), (mark, line) in zip(two_body.items(), styles):
        ax.plot(hours, values, color=BLACK, ls=line, marker=mark, ms=3.5, mfc="white", label=name)
    ax.set_xscale("log")
    ax.set_yscale("log")
    ax.set_xticks(hours)
    ax.set_xticklabels(["10분", "30분", "1 h", "3 h", "6 h", "12 h", "24 h"])
    ax.minorticks_off()
    ax.set_xlabel("에포크 후 경과 시간")
    ax.set_ylabel("SGP4 와의 위치 차이 (km)")
    ax.legend(loc="lower left", bbox_to_anchor=(0.02, 1.0), ncol=3, fontsize=7, handlelength=1.8, columnspacing=0.8)
    panel(ax, "a")

    x = np.arange(3)
    bars.bar(x - 0.18, [v[-1] for v in two_body.values()], width=0.36, color="white", edgecolor=BLACK, lw=0.7, label="이체")
    bars.bar(x + 0.18, j2_24h, width=0.36, color=GRAY, edgecolor=BLACK, lw=0.7, label="이체 + J2")
    for i, r in enumerate(ratios):
        bars.text(i, max(two_body[list(two_body)[i]][-1], j2_24h[i]) * 1.25, f"×{r:.2f}", ha="center", fontsize=8,
                  color=BLACK if r >= 1 else RED)
    bars.set_yscale("log")
    bars.set_ylim(10, 5000)
    bars.set_xticks(x)
    bars.set_xticklabels(["ISS", "SENTINEL-2A", "XMM-NEWTON"], fontsize=7.5)
    bars.tick_params(axis="x", top=False)
    bars.set_ylabel("24 h 위치 차이 (km)")
    bars.legend(loc="upper right")
    panel(bars, "b")
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
    fig, ax = plt.subplots(figsize=(4.6, 2.4))
    bins = np.logspace(-18, -10, 33)
    ax.hist(diffs, bins=bins, histtype="stepfilled", color=LIGHT, edgecolor=BLACK, lw=0.8)
    ax.set_xscale("log")
    ax.set_xticks([1e-18, 1e-16, 1e-14, 1e-12, 1e-10])
    ax.set_xticklabels(["10⁻¹⁸", "10⁻¹⁶", "10⁻¹⁴", "10⁻¹²", "10⁻¹⁰"])
    ax.minorticks_off()
    ax.axvline(max(diffs), color=RED, ls="--", lw=0.8)
    ax.text(max(diffs) * 0.8, ax.get_ylim()[1] * 0.95, f"최대 {max(diffs):.2e}", color=RED, fontsize=7.5, ha="right", va="top")
    ax.text(0.02, 0.95, f"사례 {len(java)}\n차이 0: {exact} 값\n거부 사유 일치: {rejected}", transform=ax.transAxes,
            va="top", fontsize=7.5, color=GRAY)
    ax.set_xlabel("|Java − C#| (출력 값의 절대 차)")
    ax.set_ylabel("출력 값 수")
    fig.savefig(OUT / "differential.png", facecolor="white")
    plt.close(fig)


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    OUT.mkdir(parents=True, exist_ok=True)
    setup_style()
    sky_passes()
    model_error()
    differential()
    for path in sorted(OUT.glob("*.png")):
        print(f"썼다: {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
