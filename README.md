# orbit-pass-sim

위성 궤도를 전파하고 지상국(대전)에서 위성이 언제 보이는지(패스: AOS·LOS·최대 고도각)를 예측하는 Java 시뮬레이터.
**구현보다 신뢰성 시험 체계가 목적인 프로젝트다** — 요구사항을 시험 가능한 성질로 바꾸고, 정적·동적 시험을 CI 에서 자동으로 돌린다.

```
$ java -cp build/classes orbitsim.Main          # ISS 급 궤도(420 km, 51.6°), 최소 고도각 10°
orbit: a=6798.1 km  T=93.0 min  station=Daejeon  minElev=10 deg
passes in 24h: 5
  AOS    1752s  LOS    2132s  dur   380s  maxEl  38.4 deg
  AOS    7594s  LOS    7885s  dur   291s  maxEl  18.7 deg
  ...
```

## 구성

| 모듈 | 하는 일 |
|---|---|
| `KeplerSolver` | 케플러 방정식 M = E − e·sinE 를 뉴턴-랩슨으로 풀이, 진근점 이각 변환 |
| `OrbitalElements` | 고전 궤도 요소 + 유효성 검사(이심률·경사각 범위, 근지점이 지표 위) · 주기 · 평균 운동 |
| `TwoBodyPropagator` | 이체 문제 해석해: 궤도 요소 → PQW → ECI 위치·속도 |
| `Frames` | ECI↔ECEF(지구 자전각), 측지↔ECEF(구형 지구) |
| `GroundStation` | 지상국 기준 ENU 로 고도각·방위각·거리 |
| `PassPredictor` | 시간 창을 훑어 가시 구간을 찾고 AOS·LOS 를 이분법으로 0.5 s 까지 좁힘 |

범위 밖(의도적으로 다루지 않음): J2·대기항력 섭동, 세차·장동, 타원체 지구, TLE/SGP4. 가시성 예측의 목표 정밀도는 분 단위다.

## 시험 전략

요구사항마다 **틀리면 반드시 깨지는 성질**을 먼저 정하고, 정답값을 코드 밖에서 가져온다.

| 층 | 무엇으로 검증하나 | 예 |
|---|---|---|
| 해석해·물리 법칙 | 정답이 공식으로 알려진 경우 | e=0 이면 E=M · 정지궤도 주기 = 항성일 86164 s · 에포크에 근지점 r=a(1−e) · 반주기 뒤 원지점 |
| 보존량 (성질 기반) | 전 궤도에 걸쳐 불변이어야 하는 값 | 비에너지 −μ/2a 상대오차 1e-10 · 비각운동량 벡터 · h_z/|h| = cos i |
| 독립 구현 대조 | 같은 문제를 다른 알고리즘으로 푼 값 | 케플러 해를 Python 이분법으로 계산한 값과 1e-9 일치 |
| 왕복·대칭 | 변환 쌍이 항등인지 | ECI→ECEF→ECI · 측지→ECEF→측지 · ν↔E · 음수 시간 = 주기 대칭 |
| 경계값·결함 입력 | 범위 끝과 바로 밖, NaN·∞ | e=0 허용·e=1 거부 · i=π 허용 · 위도 ±90.1 · 경도 ±180.1 · NaN 시간 · 근지점이 지표 아래 |
| 시나리오·강건성 | 시스템 수준 기대 동작 | ISS 궤도는 대전에서 하루 3~8 패스·각 12분 이내 · 정지궤도는 고도각 일정(±0.05°)·패스 1개 · 적도 LEO 는 0 패스 · 스텝 10 s vs 60 s 결과 차이 ≤ 2 s · 마스크를 올리면 패스 수 단조 감소 · AOS/LOS 시각의 고도각이 마스크 ±0.5° |

## 파이프라인

빌드 도구 없이 JDK + Python 만으로 돈다. 로컬과 CI 가 같은 스크립트를 쓴다.

```
python build.py                         # compile → test → coverage gate → PMD → SpotBugs
python build.py --min-line 90 --min-branch 80
```

| 단계 | 도구 | 실패 조건 |
|---|---|---|
| compile | `javac --release 21 -Xlint:all -Werror` | 경고 1개도 실패 |
| test | JUnit 5 (console launcher) + JaCoCo agent | 실패 1개 |
| coverage | JaCoCo CLI → HTML·CSV, 스크립트가 게이트 판정 | 라인 < 90% 또는 분기 < 80% |
| 정적 분석 | PMD 7 (quickstart 규칙) | 위반 1건 |
| 정적 분석 | SpotBugs 4.9 (`-effort:max -low`) | 버그 1건 |

GitHub Actions(`.github/workflows/ci.yml`)가 push·PR 마다 JDK 21 에서 전 단계를 돌리고 `build/reports`(JUnit XML·JaCoCo HTML·PMD·SpotBugs)를 아티팩트로 올린다.

## 결과 (2026-09-15)

| 항목 | 값 |
|---|---|
| 테스트 | **73개 통과 / 0 실패** (7개 클래스) |
| 라인 커버리지 | **92.2%** (189/205) — 데모 `Main` 을 빼면 189/190 |
| 분기 커버리지 | **91.0%** (91/100) — `Main` 을 빼면 91/92 |
| PMD | 0 건 |
| SpotBugs | 0 건 |

시험 과정에서 찾은 것과 판단은 [`docs/test-report.md`](docs/test-report.md).
