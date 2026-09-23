# orbit-pass-sim

위성 궤도를 전파하고 지상국(대전)에서 위성이 언제 보이는지(패스: AOS·LOS·최대 고도각)를 예측하는 Java 시뮬레이터.
**구현보다 신뢰성 시험 체계가 목적인 프로젝트다** — 요구사항을 판정 가능한 기준으로 쓰고, 기대값을 구현 밖에서 가져오고,
정적·동적 시험과 결함 검출력 측정을 로컬·GitHub Actions·Jenkins 에서 같은 스크립트로 돌린다.

```
$ java -cp build/classes orbitsim.Main          # ISS 급 궤도(420 km, 51.6°), 최소 고도각 10°
orbit: a=6798.1 km  T=93.0 min  station=Daejeon  minElev=10 deg
passes in 24h: 5
  AOS    1752s  LOS    2132s  dur   380s  maxEl  38.4 deg
  AOS    7594s  LOS    7885s  dur   291s  maxEl  18.7 deg
  ...
```

## 결과 (2026-09-15 · SGP4 대조 2026-09-16)

| 항목 | 기준 | 결과 |
|---|---|---|
| 시험 | 실패 0 | **659 통과** (Java) · **564 통과** (C# 이식본) |
| 라인 / 분기 커버리지 | ≥ 90 % / ≥ 85 % | Java **93.9 % / 92.6 %** (JaCoCo) · C# **92.3 % / 93.3 %** (coverlet) |
| 뮤테이션 검출률 (PIT STRONGER) | ≥ 80 % | **95.0 %** (287/302) — 생존 15개 전부 판정 근거 기록 ([보고서 §11](docs/test-report.md)) |
| 요구사항 추적 | 전 REQ 검증 | **29 / 29** |
| **Java ↔ C# 차분 시험** | 불일치 0 | 같은 명세로 만든 두 구현이 사례 **520 건 전부 일치** — 최대 차이 **3.64e-11 km (≈ 4 ulp)**, 거부 사유까지 동일 |
| **이체 모델 유효 범위** (SGP4 대조) | — | ISS 급 위치 차이 **1 h 54 km · 24 h 1,118 km**, 대전 패스 **AOS 최대 103 초 · 최대고도각 최대 14.6°** 차이 |
| **J2 세속 항을 더하면** | — | 궤도마다 갈린다 — ISS **×1.28 개선**(1,118 → 875 km) · 태양동기 **×0.36 악화**(439 → 1,208 km) · 고타원 ×1.11 |
| **이레 창 패스 개수** | — | 이체는 **3 일차**, J2 는 **1 일차**부터 SGP4 와 개수가 어긋난다 — 이틀 넘는 일정에는 못 쓴다 |
| PMD · SpotBugs | 0 | 0 · 0 |
| SonarQube Cloud (품질 게이트) | 통과 | **통과** — 버그 0 · 취약점 0 · 중복 0.0 % · 등급 A · 코드 스멜 6 (데모 출력 3 · NaN 검사 2 · 복잡도 1 — 복잡도는 `predict` 정리로 다음 분석에서 빠질 것으로 본다, §11) ([대시보드](https://sonarcloud.io/dashboard?id=Haejyn_orbit-pass-sim)) |
| 성능 (`PassPredictor.predict`, 10 s 간격, JDK 26) | 표본당 궤적 평가 ≤ 1.10 (회귀 게이트) | 이체 하루 창 **2.3 ms** · 30 일 창 **92 ms** (표본당 약 0.3 µs), J2 는 약 1.4 배. 표본당 궤적 평가 **1.005 회**(`predict` 정리 전 1.02~1.03) ([보고서 §10·§11](docs/test-report.md)) |
| **발견·수정한 제품 결함** | — | **2건** — 장기 전파 시 케플러 비수렴(D-4), 방위각 360.0°(D-5) |

→ [시험 보고서](docs/test-report.md) · [시험 계획서](docs/test-plan.md) · [요구사항](docs/requirements.md) · [추적 매트릭스](docs/traceability.md)

## 찾은 결함

예제 기반 시험 73개가 모두 통과하던 상태에서, 고정 시드 **성질 기반 무작위 시험**을 추가하자 두 결함이 드러났다. 수정 전 실패 로그는 `docs/evidence/` 에 있다.

- **D-4 케플러 풀이 비수렴** — 평범한 궤도(a = 12,233 km, e = 0.24)를 1년만 전파해도 예외. 평균 근점 이각이 커지면 double 간격(ulp)이 수렴 기준 1e-12 보다 커져 뉴턴 보정이 진동한다. 2π 주기성으로 [−π, π] 에서 풀도록 수정.
- **D-5 방위각 360.0°** — `normalizeAngle(-1e-16)` 이 반올림으로 정확히 2π 를 반환. 반열린 구간 [0, 2π) 계약 위반. 기존 시험은 −1e-9 로만 확인해 놓쳤다.

## 시험 전략 — 기대값을 구현 밖에서 가져온다

| 기법 | 기대값 출처 | 예 |
|---|---|---|
| 해석해 | 닫힌 형태의 공식 | e=0 → E=M · 정지궤도 주기 = 항성일 · 근지점 r=a(1−e) |
| 물리 보존량 | 이체 운동의 불변량 | 비에너지 −μ/2a (상대오차 1e-10) · 비각운동량 벡터 |
| 독립 구현 | 다른 알고리즘 | 케플러 해 = Python 이분법 · 전파 결과 = **Python RK4 수치 적분** (궤도 5종, 0.01 km) |
| 독립 기하 | 따로 구성한 기저 | 중위도 지상국의 동·북·천정을 외적으로 만들어 방위각·고도각 대조 |
| 외부 표준 모델 | SGP4 (공개 TLE + Python `sgp4`) — **제품 코드에는 없고 시험의 기준으로만** 쓴다 | 이체 모델의 유효 범위(위치·패스 차이) · J2 세속 항의 궤도별 효과 · 이레 창 패스 개수 |
| 차분 시험 | 같은 명세로 만든 다른 언어 구현 | Java ↔ C# 520 사례 — 최대 차이 3.64e-11 km |
| 경계값 | 명세 범위 끝·바로 밖·NaN·±∞·−0.0 | e=1 거부 · i=π 허용 · −Double.MIN_VALUE 정규화 |
| 성질 기반 무작위 | 고정 시드 불변식 (최대 100,000 건) | \|M\| 10³~10¹⁵ 케플러 · 1,000년 전파 · 관측각 범위 |
| 메타모픽 | 입력·출력 변화의 관계 | 마스크↑ → 패스 수↓ · 탐색 간격 10 s vs 60 s 결과 동일 |
| 뮤테이션 | 코드를 일부러 망가뜨림 | 생존 뮤턴트로 시험 약점 3건 발견·보강 |

## 파이프라인

```
python build.py                   # compile → test → coverage → mutation → pmd → spotbugs → trace
python build.py compile test      # 원하는 단계만
python tools/gen_golden_rk4.py --check
python tools/gen_golden_sgp4.py --check   # 골든 재현성 (sgp4 패키지가 없으면 건너뜀)
python tools/differential.py --check      # Java ↔ C# 차분 시험
```

| 단계 | 도구 | 실패 조건 |
|---|---|---|
| compile | `javac --release 21 -Xlint:all -Werror` | 경고 1개 |
| test | JUnit 5 + JaCoCo agent | 실패 1개 |
| coverage | JaCoCo CLI (HTML·XML·CSV) | 라인 < 90 % · 분기 < 85 % |
| mutation | PIT 1.20 (STRONGER) | 검출률 < 80 % |
| pmd · spotbugs | PMD 7 · SpotBugs 4.9 | 1건 |
| trace | `tools/trace.py` — `@Tag("REQ-…")` ↔ 명세 ↔ JUnit 결과 | 시험 없는 요구사항 · 실패한 요구사항 · 명세에 없는 ID |

| 실행 환경 | 설정 |
|---|---|
| GitHub Actions | `.github/workflows/ci.yml` — ubuntu·windows × JDK 21 매트릭스, 골든 재현성 검사, 잡 요약, 리포트 아티팩트, SonarQube Cloud 잡 |
| Jenkins | `Jenkinsfile` — 같은 단계를 선언형 파이프라인 스테이지로, JUnit 결과 기록·리포트 보관. 로컬 Jenkins LTS 빌드 #2 성공 (7 스테이지, 73 s) |
| 로컬 | 빌드 도구 없이 JDK 21 + Python 3. 도구 jar 는 `tools/fetch.py` 가 고정 버전으로 내려받음 |

## 구성

| 모듈 | 하는 일 |
|---|---|
| `KeplerSolver` | 케플러 방정식 뉴턴-랩슨 풀이, 진근점 이각 변환 |
| `OrbitalElements` | 고전 궤도 요소 + 물리적 유효성 검사, 주기·평균 운동 |
| `TwoBodyPropagator` | 이체 문제 해석해: 궤도 요소 → PQW → ECI |
| `J2Propagator` | 이체 궤적에 **J2 세속 항**(승교점 적경·근지점 인수·평균 근점 이각의 변화율)을 더해 궤도 요소를 밀어 준다 (REQ-PRP-08) |
| `Frames` | ECI↔ECEF(지구 자전각), 측지↔ECEF(구형 지구), 각도 정규화 |
| `GroundStation` | 지상국 기준 ENU 로 고도각·방위각·거리 |
| `PassPredictor` | 시간 창을 훑어 가시 구간을 찾고 AOS·LOS 를 이분법으로 0.5 s 까지 좁힘. 궤적을 주입할 수 있어(`forTrajectory`) J2 궤적이나 외부 골든 궤적도 같은 탐색 코드로 돌린다 |

범위 밖: J2 단주기 항과 평균 요소 변환(세속 항만 넣었다), 대기항력 섭동, 세차·장동, 타원체 지구, 대기 굴절, 제품 코드의 TLE/SGP4 (시험의 기준으로는 쓴다).
