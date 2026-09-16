# 요구사항 추적 매트릭스 (자동 생성)

> `python tools/trace.py` 가 `docs/requirements.md` · 시험 코드의 `@Tag` · JUnit 결과로 만든다. 손으로 고치지 않는다.

| 요구사항 | 내용 | 시험 | 실행 | 판정 |
|---|---|---|---|---|
| REQ-KEP-01 | 0 ≤ e < 1 인 모든 e 와 임의의 M 에 대해 케플러 방정식 해 E 를 구한다 | `KeplerSolverTest#circularOrbitReturnsMeanAnomaly`<br>`KeplerSolverTest#residualIsTinyAcrossMeanAnomalies`<br>`RobustnessTest#keplerConvergesForHugeMeanAnomaly` | 11/11 | ✅ 통과 |
| REQ-KEP-02 | 해는 독립적인 수치 방법으로 구한 값과 일치한다 | `KeplerSolverTest#matchesKnownValues` | 6/6 | ✅ 통과 |
| REQ-KEP-03 | E ↔ ν 변환은 서로 역변환이다 | `KeplerSolverTest#trueAnomalyRoundTrip`<br>`KeplerSolverTest#apsidesMapToThemselves` | 5/5 | ✅ 통과 |
| REQ-KEP-04 | 범위 밖 이심률·NaN 입력은 거부한다 | `KeplerSolverTest#rejectsEccentricityOutOfRange`<br>`KeplerSolverTest#rejectsNaN` | 5/5 | ✅ 통과 |
| REQ-ELM-01 | 주기 T = 2π√(a³/μ) 를 계산한다 | `OrbitalElementsTest#issLikePeriod`<br>`OrbitalElementsTest#geostationaryPeriodIsSiderealDay`<br>`OrbitalElementsTest#apsisRadii` | 3/3 | ✅ 통과 |
| REQ-ELM-02 | 물리적으로 불가능한 요소(근지점 ≤ 지구 반경, e∉[0,1), i∉[0,π], 비유한값)는 생성 단계에서 거부한다 | `OrbitalElementsTest#rejectsPerigeeBelowSurface`<br>`OrbitalElementsTest#rejectsOutOfRangeAngles`<br>`OrbitalElementsTest#rejectsNonFinite`<br>`OrbitalElementsTest#acceptsBoundaryValues` | 4/4 | ✅ 통과 |
| REQ-PRP-01 | 궤도 요소와 경과 시간으로 ECI 위치·속도를 구한다 | `TwoBodyPropagatorTest#startsAtPerigee`<br>`TwoBodyPropagatorTest#reachesApogeeAfterHalfPeriod`<br>`TwoBodyPropagatorTest#angularMomentumEncodesInclination`<br>`TwoBodyPropagatorTest#equatorialCircular` | 4/4 | ✅ 통과 |
| REQ-PRP-02 | 이체 운동의 보존량을 지킨다 | `TwoBodyPropagatorTest#specificEnergyConserved`<br>`TwoBodyPropagatorTest#angularMomentumConserved` | 7/7 | ✅ 통과 |
| REQ-PRP-03 | 한 주기 뒤 상태가 처음으로 돌아온다 | `TwoBodyPropagatorTest#periodicity`<br>`TwoBodyPropagatorTest#negativeTimeIsSymmetric` | 2/2 | ✅ 통과 |
| REQ-PRP-04 | 해석해 전파는 독립적인 수치 적분(RK4) 결과와 일치한다 | `GoldenRk4Test#analyticMatchesNumericalIntegration`<br>`GoldenRk4Test#goldenFileCoversOrbitRegimes` | 31/31 | ✅ 통과 |
| REQ-PRP-05 | 장기 전파(최대 1000 년)에서도 예외 없이 결과를 낸다 | `RobustnessTest#longTermPropagationIsStable` | 3/3 | ✅ 통과 |
| REQ-PRP-06 | 비유한 시간은 거부한다 | `TwoBodyPropagatorTest#rejectsNonFiniteTime` | 1/1 | ✅ 통과 |
| REQ-PRP-07 | 이체 모델의 유효 범위를 SGP4 기준과 대조해 고정한다 | `GoldenSgp4Test#twoBodyStartsFromTheSameStateAsSgp4`<br>`GoldenSgp4Test#positionDivergenceGrowsAndStaysWithinMeasuredBounds`<br>`GoldenSgp4Test#passTimesDifferWithinMeasuredBounds` | 3/3 | ✅ 통과 |
| REQ-FRM-01 | ECI↔ECEF, 측지↔ECEF 변환은 서로 역변환이다 | `FramesTest#eciEcefRoundTrip`<br>`FramesTest#knownRotations`<br>`FramesTest#geodeticRoundTrip`<br>`FramesTest#equatorPrimeMeridian`<br>`FramesTest#centerHasNoGeodetic` | 5/5 | ✅ 통과 |
| REQ-FRM-02 | 지구 자전각은 항성일마다 한 바퀴 돈다 | `FramesTest#rotationAngleAdvancesOneTurnPerSiderealDay` | 1/1 | ✅ 통과 |
| REQ-FRM-03 | 각도 정규화 결과는 모든 유한 입력에 대해 [0, 2π) 안이다 | `FramesTest#normalizeAngle`<br>`RobustnessTest#normalizeAngleStaysInHalfOpenRange`<br>`RobustnessTest#normalizeAngleRandomized` | 13/13 | ✅ 통과 |
| REQ-GST-01 | 위성의 고도각·방위각·거리를 구한다 | `GroundStationTest#zenithSatellite`<br>`GroundStationTest#azimuthAtEquator`<br>`GroundStationTest#localTangentBasisAtMidLatitude` | 3/3 | ✅ 통과 |
| REQ-GST-02 | 고도각은 [−90°, 90°], 방위각은 [0°, 360°) 안이다 | `GroundStationTest#antipodalSatelliteBelowHorizon`<br>`GroundStationTest#elevationWithinBounds`<br>`RobustnessTest#lookAnglesStayInRange`<br>`RobustnessTest#azimuthJustWestOfNorthIsBelow360` | 4/4 | ✅ 통과 |
| REQ-GST-03 | 잘못된 지상국 좌표·이름은 거부한다 | `GroundStationTest#coincidentSatelliteRejected`<br>`GroundStationTest#validation` | 2/2 | ✅ 통과 |
| REQ-PAS-01 | 시간 창에서 가시 구간(AOS·LOS·최대 고도각)을 찾는다 | `PassPredictorTest#issPassesPerDayAreRealistic`<br>`PassPredictorTest#equatorialLeoRarelyVisibleFromDaejeon`<br>`PassPredictorTest#maxElevationMatchesFineSampling`<br>`PassPredictorTest#windowBoundariesTruncatePasses` | 4/4 | ✅ 통과 |
| REQ-PAS-02 | AOS·LOS 는 최소 고도각 경계에 있다 | `PassPredictorTest#aosLosSitOnTheMask` | 1/1 | ✅ 통과 |
| REQ-PAS-03 | 결과는 탐색 간격에 강건하다 | `PassPredictorTest#stepSizeRobustness` | 1/1 | ✅ 통과 |
| REQ-PAS-04 | 패스는 시간순이고 겹치지 않으며, 마스크를 올리면 패스 수가 늘지 않는다 | `PassPredictorTest#passesAreOrderedAndDisjoint`<br>`PassPredictorTest#higherMaskYieldsFewerOrEqualPasses`<br>`PassPredictorTest#resultIsUnmodifiable` | 3/3 | ✅ 통과 |
| REQ-PAS-05 | 정지궤도처럼 항상 보이는 위성은 창 전체를 한 패스로 낸다 | `PassPredictorTest#geostationaryIsAlwaysVisibleFromMidLatitude` | 1/1 | ✅ 통과 |
| REQ-PAS-06 | 잘못된 창·간격·마스크(NaN 포함)는 거부한다 | `PassPredictorTest#boundaryArgumentsAccepted`<br>`PassPredictorTest#rejectsInvalidArguments` | 2/2 | ✅ 통과 |

**요구사항 25개 중 25개 검증됨.**
