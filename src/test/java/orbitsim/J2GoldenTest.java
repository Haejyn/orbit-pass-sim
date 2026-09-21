package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 섭동 하나(J2 세속 항)를 더하면 유효 범위가 얼마나 늘어나는가, 그리고 창을 하루에서 이레로
 * 늘리면 패스 개수가 언제부터 어긋나는가.
 *
 * <p>대조 상대와 초기값은 {@link GoldenSgp4Test} 와 같다 — 에포크에서 SGP4 가 준 상태를 요소로
 * 바꿔 두 전파기에 똑같이 준다. 패스도 같은 {@link PassPredictor} 코드로 구하므로, 차이는
 * **전파 모델 차이**만 남는다.
 *
 * <p>단언은 합격선이 아니라 **실측값에 여유를 둔 회귀 가드**다. 실측값은 docs/test-report.md §7 에 있다.
 */
class J2GoldenTest {

    private static final GroundStation DAEJEON = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
    private static final double MIN_ELEVATION_DEG = 10.0;
    private static final double SEARCH_STEP_S = 10.0;
    private static final double DAY_S = 86400.0;

    /**
     * 24 시간 시점의 비 (이체 \|dr\|) / (J2 \|dr\|) 실측 밴드. 1 보다 크면 J2 가 SGP4 에 가깝다.
     *
     * <p><b>한 방향으로 좋아진다고 단언하지 않는다.</b> 실측이 궤도마다 갈렸다 — ISS 는 ×1.28 로
     * 가까워지지만 태양동기 궤도는 ×0.36 으로 <b>더 멀어진다</b>. 이 시험은 그 갈림을 그대로 고정한다
     * (근거와 원인 후보는 docs/test-report.md §7.3).
     */
    private static final Map<String, double[]> IMPROVEMENT_24H_BAND = Map.of(
            "ISS (ZARYA)", new double[] {1.10, 1.50},      // 실측 1.28 — 좋아진다
            "SENTINEL-2A", new double[] {0.25, 0.50},      // 실측 0.36 — 나빠진다
            "XMM-NEWTON", new double[] {0.95, 1.30});      // 실측 1.11 — 사실상 차이 없다

    /** 이레 창에서 J2 궤적의 하루별 패스 수가 SGP4 와 어긋나도 되는 최대 일수. */
    private static final int MAX_MISMATCHED_DAYS_J2 = 3;

    private static List<GoldenSgp4Test.Track> longTrack() throws IOException {
        List<GoldenSgp4Test.Track> rows = new ArrayList<>();
        try (InputStream in = J2GoldenTest.class.getResourceAsStream("/golden/sgp4_long_track.csv")) {
            if (in == null) {
                throw new IllegalStateException("/golden/sgp4_long_track.csv is not on the test classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String[] c : reader.lines().skip(1).map(l -> l.split(",")).toList()) {
                rows.add(new GoldenSgp4Test.Track(Double.parseDouble(c[1]),
                        new Vector3(Double.parseDouble(c[2]), Double.parseDouble(c[3]), Double.parseDouble(c[4])),
                        new Vector3(Double.parseDouble(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7]))));
            }
        }
        return rows;
    }

    private static OrbitalElements elementsOf(GoldenSgp4Test.State s) {
        return new OrbitalElements(s.a(), s.e(), Math.toRadians(s.iDeg()), Math.toRadians(s.raanDeg()),
                Math.toRadians(s.argpDeg()), Math.toRadians(s.m0Deg()));
    }

    // ── 1. 섭동 하나를 더하면 얼마나 가까워지나 ─────────────────────────────
    @Test
    @Tag("REQ-PRP-08")
    @DisplayName("J2 세속 항을 더하면 SGP4 와의 위치 차이가 줄어든다")
    void j2SecularNarrowsTheGapToSgp4() throws IOException {
        System.out.println("=== |dr| to SGP4 [km] : two-body vs +J2 (ratio) ===");
        for (Map.Entry<String, List<GoldenSgp4Test.State>> e : GoldenSgp4Test.states().entrySet()) {
            OrbitalElements el = elementsOf(e.getValue().get(0));
            double[] band = IMPROVEMENT_24H_BAND.get(e.getKey());
            assertNotNull(band, "no measured band for " + e.getKey());
            System.out.printf("%s%n", e.getKey());
            for (GoldenSgp4Test.State s : e.getValue()) {
                if (s.t() == 0.0) {
                    continue;
                }
                double twoBody = TwoBodyPropagator.stateAt(el, s.t()).positionKm().minus(s.r()).norm();
                double withJ2 = J2Propagator.stateAt(el, s.t()).positionKm().minus(s.r()).norm();
                double ratio = twoBody / withJ2;
                System.out.printf("  t=%-9.0f two-body %12.3f   +J2 %12.3f   x%.2f%n",
                        s.t(), twoBody, withJ2, ratio);
                if (s.t() == DAY_S) {
                    assertTrue(ratio >= band[0] && ratio <= band[1],
                            () -> e.getKey() + " 24 h ratio x" + ratio
                                    + " outside measured band [" + band[0] + ", " + band[1] + "]");
                }
            }
        }
    }

    // ── 2. J2 를 넣어도 에포크 상태는 그대로여야 한다 (대조의 전제) ─────────
    @Test
    @Tag("REQ-PRP-08")
    @DisplayName("J2 전파는 에포크에서 이체 전파와 같은 상태를 낸다")
    void j2MatchesTwoBodyAtEpoch() throws IOException {
        for (List<GoldenSgp4Test.State> rows : GoldenSgp4Test.states().values()) {
            OrbitalElements el = elementsOf(rows.get(0));
            Vector3 twoBody = TwoBodyPropagator.stateAt(el, 0.0).positionKm();
            Vector3 withJ2 = J2Propagator.stateAt(el, 0.0).positionKm();
            assertEquals(0.0, twoBody.minus(withJ2).norm(), 1e-12, "epoch state must not move");
        }
    }

    // ── 2-b. 세차율 자체를 설계 정의와 대조한다 ─────────────────────────────
    @Test
    @Tag("REQ-PRP-08")
    @DisplayName("태양동기 궤도의 승교점 세차율은 1 년에 한 바퀴에 가깝고, ISS 는 반대로 돈다")
    void raanDriftMatchesSunSynchronousDesign() throws IOException {
        Map<String, List<GoldenSgp4Test.State>> states = GoldenSgp4Test.states();
        double sso = degPerDay(states.get("SENTINEL-2A").get(0));
        double iss = degPerDay(states.get("ISS (ZARYA)").get(0));
        // 기대값을 구현 밖에서: 태양동기 궤도의 정의가 "승교점이 1 년에 한 바퀴" 다
        double sunSyncTarget = 360.0 / 365.2422;
        System.out.printf("raan drift [deg/day]  SENTINEL-2A %+.4f (sun-sync %+.4f)  ISS %+.4f%n",
                sso, sunSyncTarget, iss);
        // 세차율의 **크기**를 묶는 유일한 단언이다 — 느슨하면 계수를 바꾼 결함이 그대로 통과한다
        assertEquals(sunSyncTarget, sso, 0.02,
                "sun-synchronous nodal drift should be about one turn per year");
        // cos i 의 부호가 갈린다 — 순행 궤도(ISS)는 서쪽으로 밀린다
        assertTrue(iss < 0.0, () -> "prograde orbit should regress, got " + iss);
    }

    private static double degPerDay(GoldenSgp4Test.State s) {
        return Math.toDegrees(J2Propagator.raanRateRadPerSecond(elementsOf(s))) * DAY_S;
    }

    // ── 2-c. 세차율의 각 항을 물리적 landmark 로 못 박는다 ───────────────────
    @Test
    @Tag("REQ-PRP-08")
    @DisplayName("임계 경사각에서 근지점이 멈추고, 극궤도에서 승교점이 멈춘다")
    void driftRatesMatchKnownInclinationLandmarks() {
        double a = Constants.R_EARTH + 800.0;
        double t = DAY_S;

        // 임계 경사각 63.4349° — 2 − 2.5·sin²i = 0 이라 근지점 인수가 흐르지 않는다.
        // 기대값이 식이 아니라 **궤도역학의 성질**에서 오므로, 항을 하나라도 건드리면 0 이 깨진다.
        OrbitalElements critical = new OrbitalElements(a, 0.001, Math.toRadians(63.434949), 0.3, 0.7, 0.1);
        assertEquals(0.0, J2Propagator.driftedElements(critical, t).argPerigee() - critical.argPerigee(), 1e-9,
                "argument of perigee must not drift at the critical inclination");

        // 극궤도 — cos 90° = 0 이라 승교점이 흐르지 않는다 (부호를 뒤집어도 0 은 0 이므로 아래 저경사와 함께 본다)
        OrbitalElements polar = new OrbitalElements(a, 0.001, Math.toRadians(90.0), 0.3, 0.7, 0.1);
        assertEquals(0.0, J2Propagator.raanRateRadPerSecond(polar), 1e-18, "polar orbit has no nodal drift");
        assertEquals(0.0, J2Propagator.driftedElements(polar, t).raan() - polar.raan(), 1e-12,
                "polar orbit node must stay put");

        // 순행 저경사 — 승교점은 서쪽으로 밀리고(음), 근지점은 동쪽으로 흐른다(양)
        OrbitalElements low = new OrbitalElements(a, 0.001, Math.toRadians(30.0), 0.3, 0.7, 0.1);
        assertTrue(J2Propagator.raanRateRadPerSecond(low) < 0.0,
                "prograde low inclination must regress the node");
        assertTrue(J2Propagator.driftedElements(low, t).argPerigee() > low.argPerigee(),
                "below the critical inclination the perigee advances");

        // 평균 근점 이각의 J2 항은 (1 − 1.5·sin²i) 의 부호를 따른다 — 저경사는 빨라지고 극궤도는 느려진다
        double lowMean = J2Propagator.driftedElements(low, t).meanAnomalyAtEpoch() - low.meanAnomalyAtEpoch();
        assertTrue(lowMean > low.meanMotion() * t, "low inclination: J2 must speed up the mean anomaly");
        double polarMean = J2Propagator.driftedElements(polar, t).meanAnomalyAtEpoch() - polar.meanAnomalyAtEpoch();
        assertTrue(polarMean < polar.meanMotion() * t, "polar: J2 must slow the mean anomaly");
    }

    // ── 2-d. 입력 검증과 흐름 방향 ──────────────────────────────────────────
    @Test
    @Tag("REQ-PRP-08")
    @DisplayName("비유한 시간·널 요소를 거부하고, 요소는 정해진 방향으로 흐른다")
    void rejectsBadInputAndDriftsInTheRightDirection() {
        OrbitalElements low = new OrbitalElements(Constants.R_EARTH + 800.0, 0.001,
                Math.toRadians(30.0), 0.3, 0.7, 0.1);
        assertThrows(IllegalArgumentException.class, () -> J2Propagator.stateAt(low, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> J2Propagator.stateAt(low, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> J2Propagator.trajectory(null));
        assertNotNull(J2Propagator.trajectory(low).positionEciAt(0.0));

        // 흐름의 **부호**를 직접 본다 — 0 을 확인하는 시험만으로는 부호가 뒤집혀도 통과한다
        OrbitalElements after = J2Propagator.driftedElements(low, DAY_S);
        assertTrue(after.raan() < low.raan(), "prograde node must regress over a day");
        assertTrue(after.argPerigee() > low.argPerigee(), "perigee must advance below the critical inclination");

        // 이심률이 큰 궤도에서는 p = a(1−e²) 의 형태가 결과를 크게 가른다 (e² 가 작으면 드러나지 않는다)
        OrbitalElements eccentric = new OrbitalElements(26000.0, 0.7, Math.toRadians(20.0), 0.0, 0.0, 0.0);
        double rate = J2Propagator.raanRateRadPerSecond(eccentric);
        double p = 26000.0 * (1.0 - 0.7 * 0.7);
        double expected = -1.5 * Constants.J2_EARTH * (Constants.R_EARTH / p) * (Constants.R_EARTH / p)
                * eccentric.meanMotion() * Math.cos(Math.toRadians(20.0));
        assertEquals(expected, rate, Math.abs(expected) * 1e-9, "nodal rate must follow a(1-e^2)");
    }

    // ── 2-e. driftedElements 를 이심률이 큰 궤도에서 식 그대로 따로 계산해 대조한다 ──────
    @Test
    @Tag("REQ-PRP-08")
    @DisplayName("이심률이 큰 궤도에서 세 세속 변화율이 식을 그대로 따른다")
    void secularRatesFollowTheClosedFormOnEccentricOrbits() {
        // PIT 생존 J2Propagator L43(p = a(1−e²)) · L51(평균 근점 이각 변화율)을 겨냥한다.
        // 왜 지금까지 살아남았나 — 위의 e = 0.7 단언은 raanRateRadPerSecond 만 부르는데, 그 메서드는 p 를 62 줄에서
        // **따로 다시 계산**하므로 driftedElements 의 43 줄을 한 번도 지나지 않는다. 나머지 시험은 e = 0.001 궤도라
        // e² = 1e-6 이어서 연산자를 바꿔도 결과가 2 ppm 만 달라져 어떤 단언에도 걸리지 않았다.
        // 여기서는 e = 0.7·0.4 (e² = 0.49·0.16) 와 임계값이 아닌 경사각(20°·50°)을 써서, 연산자 하나만 바뀌어도
        // 결과가 수십 % 달라지고 세 항이 모두 0 이 아닌 크기로 드러나게 한다.
        double t = 3.0 * 3600.0;
        double[][] orbits = {{26000.0, 0.7, 20.0}, {15000.0, 0.4, 50.0}};      // a [km], e, i [deg]
        for (double[] orbit : orbits) {
            OrbitalElements el = new OrbitalElements(orbit[0], orbit[1], Math.toRadians(orbit[2]), 0.3, 0.7, 0.1);

            // 기대값 — 제품 코드와 다른 꼴로 다시 쓴다: sin²i 는 1 − cos²i 로, p 는 시험 안에서 따로 곱한다.
            double cosI = Math.cos(el.inclination());
            double sin2 = 1.0 - cosI * cosI;
            double p = el.semiMajorAxisKm() * (1.0 - el.eccentricity() * el.eccentricity());
            double rOverP = Constants.R_EARTH / p;
            double factor = 1.5 * Constants.J2_EARTH * rOverP * rOverP * el.meanMotion();
            double expectedRaanRate = -factor * cosI;
            double expectedArgpRate = factor * (2.0 - 2.5 * sin2);
            double expectedMeanRate = el.meanMotion()
                    + factor * Math.sqrt(1.0 - el.eccentricity() * el.eccentricity()) * (1.0 - 1.5 * sin2);

            OrbitalElements after = J2Propagator.driftedElements(el, t);
            String label = " (a=" + orbit[0] + " e=" + orbit[1] + " i=" + orbit[2] + ")";
            assertEquals(el.raan() + expectedRaanRate * t, after.raan(), 1e-12, "node drift" + label);
            assertEquals(el.argPerigee() + expectedArgpRate * t, after.argPerigee(), 1e-12, "perigee drift" + label);
            assertEquals(el.meanAnomalyAtEpoch() + expectedMeanRate * t, after.meanAnomalyAtEpoch(), 1e-12,
                    "mean anomaly drift" + label);
        }
    }

    // ── 3. 창을 이레로 늘리면 패스 개수가 언제부터 어긋나나 ─────────────────
    @Test
    @Tag("REQ-PRP-09")
    @DisplayName("이레 창에서 하루별 패스 수가 어긋나기 시작하는 지점을 고정한다")
    void longWindowPassCountsDivergeAfterMeasuredDay() throws IOException {
        GoldenSgp4Test.State epoch = GoldenSgp4Test.states().get("ISS (ZARYA)").get(0);
        OrbitalElements el = elementsOf(epoch);
        PassPredictor.Trajectory sgp4Track = GoldenSgp4Test.hermite(longTrack());

        PassPredictor sgp4 = PassPredictor.forTrajectory(sgp4Track, DAEJEON, epoch.theta0(), MIN_ELEVATION_DEG);
        PassPredictor twoBody = new PassPredictor(el, DAEJEON, epoch.theta0(), MIN_ELEVATION_DEG);
        PassPredictor withJ2 = PassPredictor.forTrajectory(J2Propagator.trajectory(el), DAEJEON,
                epoch.theta0(), MIN_ELEVATION_DEG);

        System.out.println("=== ISS Daejeon passes per day (mask " + MIN_ELEVATION_DEG + " deg) ===");
        int firstTwoBodyMismatch = -1;
        int j2Mismatches = 0;
        for (int day = 0; day < 7; day++) {
            double from = day * DAY_S;
            double to = from + DAY_S;
            int refCount = sgp4.predict(from, to, SEARCH_STEP_S).size();
            int twoBodyCount = twoBody.predict(from, to, SEARCH_STEP_S).size();
            int j2Count = withJ2.predict(from, to, SEARCH_STEP_S).size();
            System.out.printf("  day %d  sgp4 %d  two-body %d  +J2 %d%n", day + 1, refCount, twoBodyCount, j2Count);
            if (twoBodyCount != refCount && firstTwoBodyMismatch < 0) {
                firstTwoBodyMismatch = day + 1;
            }
            if (j2Count != refCount) {
                j2Mismatches++;
            }
        }
        final int twoBodyMismatchDay = firstTwoBodyMismatch;
        final int j2MismatchedDays = j2Mismatches;
        System.out.println("  first two-body mismatch: day " + twoBodyMismatchDay
                + " · J2 mismatched days: " + j2MismatchedDays);

        // 하루 창에서는 개수가 맞았다 (§7.2). 이레로 늘리면 언젠가 어긋난다는 것이 이 시험의 요지다.
        assertTrue(twoBodyMismatchDay > 1,
                () -> "two-body already mismatched on day " + twoBodyMismatchDay + " — 24 h result contradicted");
        assertTrue(j2MismatchedDays <= MAX_MISMATCHED_DAYS_J2,
                () -> "J2 mismatched " + j2MismatchedDays + " days, above " + MAX_MISMATCHED_DAYS_J2);
    }
}
