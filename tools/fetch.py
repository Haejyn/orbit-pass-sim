"""도구 jar 내려받기. 저장소에는 jar 를 넣지 않고 버전만 고정한다."""
from __future__ import annotations

import os
import urllib.request
import zipfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
JUNIT_VERSION = "1.13.4"
JACOCO_VERSION = "0.8.13"
SPOTBUGS_VERSION = "4.9.3"
PMD_VERSION = "7.14.0"

JUNIT_URL = (f"https://repo1.maven.org/maven2/org/junit/platform/junit-platform-console-standalone/"
             f"{JUNIT_VERSION}/junit-platform-console-standalone-{JUNIT_VERSION}.jar")
JACOCO_URL = f"https://repo1.maven.org/maven2/org/jacoco/jacoco/{JACOCO_VERSION}/jacoco-{JACOCO_VERSION}.zip"
SPOTBUGS_URL = f"https://github.com/spotbugs/spotbugs/releases/download/{SPOTBUGS_VERSION}/spotbugs-{SPOTBUGS_VERSION}.zip"
PMD_URL = f"https://github.com/pmd/pmd/releases/download/pmd_releases%2F{PMD_VERSION}/pmd-dist-{PMD_VERSION}-bin.zip"


def download(url: str, dest: Path) -> Path:
    if not dest.exists():
        print(f"downloading {url}")
        dest.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(url, dest)
    return dest


def ensure_all() -> dict[str, Path]:
    junit = download(JUNIT_URL, TOOLS / f"junit-platform-console-standalone-{JUNIT_VERSION}.jar")

    jacoco_dir = TOOLS / f"jacoco-{JACOCO_VERSION}"
    if not (jacoco_dir / "lib" / "jacocoagent.jar").exists():
        z = download(JACOCO_URL, TOOLS / f"jacoco-{JACOCO_VERSION}.zip")
        with zipfile.ZipFile(z) as zf:
            zf.extract("lib/jacocoagent.jar", jacoco_dir)
            zf.extract("lib/jacococli.jar", jacoco_dir)

    spotbugs_dir = TOOLS / f"spotbugs-{SPOTBUGS_VERSION}"
    if not (spotbugs_dir / "lib" / "spotbugs.jar").exists():
        z = download(SPOTBUGS_URL, TOOLS / f"spotbugs-{SPOTBUGS_VERSION}.zip")
        with zipfile.ZipFile(z) as zf:
            zf.extractall(TOOLS)

    pmd_dir = TOOLS / f"pmd-bin-{PMD_VERSION}"
    pmd_bin = pmd_dir / "bin" / ("pmd.bat" if os.name == "nt" else "pmd")
    if not pmd_bin.exists():
        z = download(PMD_URL, TOOLS / f"pmd-dist-{PMD_VERSION}-bin.zip")
        with zipfile.ZipFile(z) as zf:
            zf.extractall(TOOLS)
        if os.name != "nt":
            pmd_bin.chmod(0o755)

    return {
        "junit": junit,
        "jacoco_agent": jacoco_dir / "lib" / "jacocoagent.jar",
        "jacoco_cli": jacoco_dir / "lib" / "jacococli.jar",
        "spotbugs": spotbugs_dir / "lib" / "spotbugs.jar",
        "pmd_bin": pmd_bin,
    }


if __name__ == "__main__":
    for k, v in ensure_all().items():
        print(f"{k}: {v}")
