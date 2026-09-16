package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 이체 모델의 유효 범위를 SGP4 기준과 대조해 수치로 고정한다 (tools/gen_golden_sgp4.py).
 *
 * <p>대조 방법 — 에포크에서 SGP4 가 준 상태 벡터를 고전 요소로 바꿔 이체 전파의 초기값으로 준다.
 * t=0 에서 두 궤적이 같으므로 이후 벌어지는 차이는 **모델 차이**만 남는다 (TLE 평균 요소를 그대로
 * 쓰면 평균→접촉 변환 오차가 섞인다).
 *
 * <p>패스 비교는 두 궤적을 **같은 PassPredictor·GroundStation 코드**에 넣어 구한다. 탐색·이분법이
 * 같으므로 시각 차이는 전파 차이만으로 설명된다.
 *
 * <p>단언 값은 합격선이 아니라 **실측값에 여유를 둔 회귀 가드**다. 목적은 "이체 모델을 몇 시간까지
 * 쓸 수 있나" 를 붙잡아 두는 것이다. 실측값은 docs/test-report.md §7 에 있다.
 */
class GoldenSgp4Test {

    private static final GroundStation DAEJEON = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
    private static final double MIN_ELEVATION_DEG = 10.0;
    private static final double WINDOW_S = 86400.0;
    private static final double SEARCH_STEP_S = 10.0;

    /** 위치 차이 상한 [km] — 2026-09-16 실측값에 1.4~1.7 배 여유. */
    private static final Map<String, double[]> DIVERGENCE_CAP_KM = Map.of(
            "ISS (ZARYA)", new double[] {100.0, 1600.0},     // 실측 1 h 53.6 · 24 h 1118.4
            "SENTINEL-2A", new double[] {60.0, 700.0},       // 실측 1 h 24.0 · 24 h 438.7
            "XMM-NEWTON", new double[] {5.0, 40.0});         // 실측 1 h 1.2 · 24 h 23.0

    /** 패스 차이 상한 {AOS [s], LOS [s], 최대고도각 [deg]} — 실측값에 1.5 배 안팎 여유. */
    private static final Map<String, double[]> PASS_CAP = Map.of(
            "ISS (ZARYA)", new double[] {180.0, 240.0, 20.0},   // 실측 103.1 · 158.1 · 14.62
            "SENTINEL-2A", new double[] {90.0, 90.0, 8.0});     // 실측 54.7 · 34.7 · 3.60

    record State(String name, double a, double e, double iDeg, double raanDeg, double argpDeg,
                 double m0Deg, double theta0, double t, Vector3 r) {}

    record Track(double t, Vector3 r, Vector3 v) {}

    // ── 자료 읽기 ────────────────────────────────────────────────────────────
    private static List<String[]> csv(String resource) throws IOException {
        try (InputStream in = GoldenSgp4Test.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is not on the test classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines().skip(1).map(l -> l.split(",")).toList();
        }
    }

    static Map<String, List<State>> states() throws IOException {
        Map<String, List<State>> byName = new LinkedHashMap<>();
        for (String[] c : csv("/golden/sgp4_states.csv")) {
            State s = new State(c[0], d(c[1]), d(c[2]), d(c[3]), d(c[4]), d(c[5]), d(c[6]), d(c[7]), d(c[8]),
                    new Vector3(d(c[9]), d(c[10]), d(c[11])));
            byName.computeIfAbsent(s.name(), k -> new ArrayList<>()).add(s);
        }
        return byName;
    }

    static Map<String, List<Track>> tracks() throws IOException {
        Map<String, List<Track>> byName = new LinkedHashMap<>();
        for (String[] c : csv("/golden/sgp4_tracks.csv")) {
            byName.computeIfAbsent(c[0], k -> new ArrayList<>())
                    .add(new Track(d(c[1]), new Vector3(d(c[2]), d(c[3]), d(c[4])),
                            new Vector3(d(c[5]), d(c[6]), d(c[7]))));
        }
        return byName;
    }

    private static double d(String s) { return Double.parseDouble(s); }

    private static OrbitalElements elementsOf(State s) {
        return new OrbitalElements(s.a(), s.e(), Math.toRadians(s.iDeg()), Math.toRadians(s.raanDeg()),
                Math.toRadians(s.argpDeg()), Math.toRadians(s.m0Deg()));
    }

    /**
     * 3차 Hermite 보간 궤적 — 저장된 위치와 **속도**를 함께 쓴다. 60 초 간격에서도 보간 오차가
     * 재려는 차이에 섞이지 않는다 (위치만 선형 보간하면 저궤도에서 0.8 km 쯤 틀어진다).
     */
    static PassPredictor.Trajectory hermite(List<Track> rows) {
        double t0 = rows.get(0).t();
        double step = rows.get(1).t() - t0;
        double last = rows.get(rows.size() - 1).t();
        return t -> {
            double x = Math.min(Math.max(t, t0), last);
            int k = Math.min((int) ((x - t0) / step), rows.size() - 2);
            Track p0 = rows.get(k);
            Track p1 = rows.get(k + 1);
            double h = p1.t() - p0.t();
            double s = (x - p0.t()) / h;
            double s2 = s * s;
            double s3 = s2 * s;
            return p0.r().scale(2 * s3 - 3 * s2 + 1)
                    .plus(p0.v().scale((s3 - 2 * s2 + s) * h))
                    .plus(p1.r().scale(-2 * s3 + 3 * s2))
                    .plus(p1.v().scale((s3 - s2) * h));
        };
    }

    // ── 1. 초기 상태가 같은가 (대조의 전제) ──────────────────────────────────
    @Test
    @Tag("REQ-PRP-07")
    @DisplayName("에포크에서 이체 전파는 SGP4 상태와 일치한다 — 이후 차이는 모델 차이뿐")
    void twoBodyStartsFromTheSameStateAsSgp4() throws IOException {
        for (List<State> rows : states().values()) {
            State epoch = rows.get(0);
            assertEquals(0.0, epoch.t(), "first row must be the epoch");
            Vector3 twoBody = TwoBodyPropagator.stateAt(elementsOf(epoch), 0.0).positionKm();
            double dr = twoBody.minus(epoch.r()).norm();
            // 남는 것은 골든 파일의 반올림뿐 (요소를 1e-12 deg 로 적는다) — 1 cm 로 잡는다
            assertTrue(dr < 1e-5, () -> epoch.name() + " epoch |dr|=" + dr + " km");
        }
    }

    // ── 2. 위치 차이가 시간에 따라 얼마나 벌어지나 ──────────────────────────
    @Test
    @Tag("REQ-PRP-07")
    @DisplayName("이체 전파와 SGP4 의 위치 차이는 단조 증가하고 궤도별 상한 안에 있다")
    void positionDivergenceGrowsAndStaysWithinMeasuredBounds() throws IOException {
        System.out.println("=== two-body vs SGP4 position difference [km] ===");
        for (Map.Entry<String, List<State>> e : states().entrySet()) {
            OrbitalElements el = elementsOf(e.getValue().get(0));
            double[] cap = DIVERGENCE_CAP_KM.get(e.getKey());
            assertNotNull(cap, "no measured bound for " + e.getKey());
            StringBuilder line = new StringBuilder(String.format("%-14s", e.getKey()));
            double previous = -1.0;
            for (State s : e.getValue()) {
                double dr = TwoBodyPropagator.stateAt(el, s.t()).positionKm().minus(s.r()).norm();
                line.append(String.format("  t=%-6.0f %10.3f", s.t(), dr));
                // 섭동을 빼먹은 오차는 쌓이기만 한다 — 줄어들면 대조 설계가 깨진 것이다
                assertTrue(dr >= previous - 1e-9,
                        () -> e.getKey() + " t=" + s.t() + " divergence shrank to " + dr);
                previous = dr;
                if (s.t() == 3600.0) {
                    assertTrue(dr < cap[0], () -> e.getKey() + " 1 h |dr|=" + dr + " km exceeds " + cap[0]);
                }
                if (s.t() == 86400.0) {
                    assertTrue(dr < cap[1], () -> e.getKey() + " 24 h |dr|=" + dr + " km exceeds " + cap[1]);
                }
            }
            System.out.println(line);
        }
    }

    // ── 3. 지상국 패스 시각이 얼마나 어긋나나 ───────────────────────────────
    @Test
    @Tag("REQ-PRP-07")
    @DisplayName("대전 패스의 AOS·LOS·최대고도각 차이는 실측 상한 안에 있고 패스 수는 같다")
    void passTimesDifferWithinMeasuredBounds() throws IOException {
        Map<String, List<State>> states = states();
        System.out.println("=== Daejeon passes (mask " + MIN_ELEVATION_DEG + " deg, 24 h) ===");
        for (Map.Entry<String, List<Track>> e : tracks().entrySet()) {
            State epoch = states.get(e.getKey()).get(0);
            double[] cap = PASS_CAP.get(e.getKey());
            assertNotNull(cap, "no measured bound for " + e.getKey());

            List<PassPredictor.Pass> twoBody = new PassPredictor(elementsOf(epoch), DAEJEON, epoch.theta0(),
                    MIN_ELEVATION_DEG).predict(0, WINDOW_S, SEARCH_STEP_S);
            List<PassPredictor.Pass> sgp4 = PassPredictor.forTrajectory(hermite(e.getValue()), DAEJEON,
                    epoch.theta0(), MIN_ELEVATION_DEG).predict(0, WINDOW_S, SEARCH_STEP_S);

            System.out.printf("%-14s passes: two-body %d, sgp4 %d%n", e.getKey(), twoBody.size(), sgp4.size());
            assertFalse(sgp4.isEmpty(), e.getKey() + " should have passes over Daejeon in 24 h");
            assertEquals(sgp4.size(), twoBody.size(), e.getKey() + " pass count differs");

            for (PassPredictor.Pass ref : sgp4) {
                PassPredictor.Pass near = null;
                double best = Double.MAX_VALUE;
                for (PassPredictor.Pass p : twoBody) {
                    double gap = Math.abs(p.aosSeconds() - ref.aosSeconds());
                    if (gap < best) { best = gap; near = p; }
                }
                assertNotNull(near);
                double dAos = near.aosSeconds() - ref.aosSeconds();
                double dLos = near.losSeconds() - ref.losSeconds();
                double dElev = near.maxElevationDeg() - ref.maxElevationDeg();
                System.out.printf("  sgp4 AOS %8.1f  dAOS %8.1f  dLOS %8.1f  dMaxEl %6.2f%n",
                        ref.aosSeconds(), dAos, dLos, dElev);
                assertTrue(Math.abs(dAos) <= cap[0], () -> e.getKey() + " dAOS=" + dAos + " s exceeds " + cap[0]);
                assertTrue(Math.abs(dLos) <= cap[1], () -> e.getKey() + " dLOS=" + dLos + " s exceeds " + cap[1]);
                assertTrue(Math.abs(dElev) <= cap[2], () -> e.getKey() + " dMaxEl=" + dElev + " deg exceeds " + cap[2]);
            }
        }
    }
}
