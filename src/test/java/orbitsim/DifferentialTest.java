package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Java ↔ C# 차분 시험의 Java 쪽 고정 장치 (REQ-DIF-01 · 02).
 *
 * <p>골든 {@code differential_vectors.csv} 는 {@code tools/differential.py} 가 Java CLI 로 만들고,
 * 같은 파일을 C# 시험도 읽는다. 여기서는 CLI 를 거치지 않고 **라이브러리로 다시 계산해** 대조하므로,
 * CLI 가 값을 옮기다 틀리는 경우도 드러난다.
 */
class DifferentialTest {

    private static final int INPUT_COLUMNS = 18;
    private static final int MAX_OUTPUTS = 6;
    /** tools/differential.py · C# Golden.cs 와 같은 허용 오차. */
    private static final double ATOL = 1e-9;
    private static final double RTOL = 1e-12;

    record Row(String[] cells) {
        String id() { return cells[0]; }

        String kind() { return cells[1]; }

        String status() { return cells[INPUT_COLUMNS]; }

        double in(int index) { return num(cells[index]); }

        String out(int index) { return cells[INPUT_COLUMNS + 1 + index]; }
    }

    static List<Row> rows() throws IOException {
        try (InputStream in = DifferentialTest.class.getResourceAsStream("/golden/differential_vectors.csv")) {
            if (in == null) {
                throw new IllegalStateException("golden/differential_vectors.csv is not on the test classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines().skip(1).filter(l -> !l.isEmpty())
                    .map(l -> new Row(l.split(",", -1))).toList();
        }
    }

    static Stream<Row> rowStream() throws IOException {
        return rows().stream();
    }

    private static double num(String s) {
        return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }

    private static boolean close(double expected, double actual) {
        if (Double.isNaN(expected) || Double.isNaN(actual)) {
            return Double.isNaN(expected) && Double.isNaN(actual);
        }
        if (Double.isInfinite(expected) || Double.isInfinite(actual)) {
            return expected == actual;
        }
        return Math.abs(expected - actual) <= ATOL + RTOL * Math.abs(expected);
    }

    /** 골든의 한 줄을 라이브러리로 다시 계산한다 — CLI 와 독립된 경로. */
    private static String[] recompute(Row r) {
        try {
            double[] v = switch (r.kind()) {
                case "normalize" -> new double[] {Frames.normalizeAngle(r.in(17))};
                case "kepler" -> kepler(r);
                case "elements" -> elements(r);
                case "state" -> state(r);
                case "look" -> look(r);
                case "pass" -> pass(r);
                default -> throw new IllegalArgumentException("unknown kind: " + r.kind());
            };
            String[] out = new String[v.length + 1];
            out[0] = "ok";
            for (int k = 0; k < v.length; k++) {
                out[k + 1] = Double.toString(v[k]);
            }
            return out;
        } catch (IllegalArgumentException e) {
            return new String[] {"reject"};
        } catch (ArithmeticException e) {
            return new String[] {"diverge"};
        } catch (IllegalStateException e) {
            return new String[] {"state"};
        }
    }

    private static double[] kepler(Row r) {
        double e = r.in(3);
        double eAnom = KeplerSolver.solveEccentricAnomaly(r.in(7), e);
        double nu = KeplerSolver.trueAnomaly(eAnom, e);
        return new double[] {eAnom, nu, KeplerSolver.eccentricFromTrue(nu, e)};
    }

    private static double[] elements(Row r) {
        OrbitalElements el = elems(r);
        return new double[] {el.periodSeconds(), el.meanMotion(), el.perigeeRadiusKm(), el.apogeeRadiusKm()};
    }

    private static double[] state(Row r) {
        StateVector s = TwoBodyPropagator.stateAt(elems(r), r.in(8));
        return new double[] {
            s.positionKm().x(), s.positionKm().y(), s.positionKm().z(),
            s.velocityKmS().x(), s.velocityKmS().y(), s.velocityKmS().z(),
        };
    }

    private static double[] look(Row r) {
        double t = r.in(8);
        Vector3 ecef = Frames.eciToEcef(TwoBodyPropagator.stateAt(elems(r), t).positionKm(),
                Frames.earthRotationAngle(r.in(12), t));
        GroundStation.LookAngles look = station(r).lookAngles(ecef);
        return new double[] {look.elevationDeg(), look.azimuthDeg(), look.rangeKm()};
    }

    private static double[] pass(Row r) {
        PassPredictor predictor = new PassPredictor(elems(r), station(r), r.in(12), r.in(13));
        List<PassPredictor.Pass> passes = predictor.predict(r.in(14), r.in(15), r.in(16));
        if (passes.isEmpty()) {
            return new double[] {0, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
        PassPredictor.Pass p = passes.get(0);
        return new double[] {
            passes.size(), p.aosSeconds(), p.losSeconds(), p.maxElevationDeg(), p.timeOfMaxSeconds(),
        };
    }

    private static OrbitalElements elems(Row r) {
        return new OrbitalElements(r.in(2), r.in(3), r.in(4), r.in(5), r.in(6), r.in(7));
    }

    private static GroundStation station(Row r) {
        return new GroundStation("station", r.in(9), r.in(10), r.in(11));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("rowStream")
    @Tag("REQ-DIF-01")
    @DisplayName("골든의 모든 사례를 라이브러리로 다시 계산해도 같은 판정·같은 값이 나온다")
    void javaReproducesTheGolden(Row r) {
        String[] actual = recompute(r);
        assertEquals(r.status(), actual[0], () -> r.id() + " [" + r.kind() + "] 판정이 다르다");
        if (!"ok".equals(r.status())) {
            return;
        }
        for (int k = 0; k < MAX_OUTPUTS; k++) {
            String cell = r.out(k);
            if (cell.isEmpty()) {
                assertTrue(k + 1 >= actual.length, () -> r.id() + ": 출력이 더 많다");
                continue;
            }
            assertTrue(k + 1 < actual.length, () -> r.id() + ": 출력이 모자라다");
            double expected = Double.parseDouble(cell);
            double got = Double.parseDouble(actual[k + 1]);
            final int index = k;
            assertTrue(close(expected, got),
                    () -> r.id() + " [" + r.kind() + "] o" + (index + 1) + ": " + expected + " vs " + got);
        }
    }

    @Test
    @Tag("REQ-DIF-02")
    @DisplayName("골든이 경계·특이값과 거부 경로를 실제로 담고 있다")
    void goldenCoversBoundariesAndRejections() throws IOException {
        List<Row> rows = rows();
        Set<String> kinds = rows.stream().map(Row::kind).collect(Collectors.toSet());
        Set<String> statuses = rows.stream().map(Row::status).collect(Collectors.toSet());

        assertEquals(Set.of("normalize", "kepler", "elements", "state", "look", "pass"), kinds);
        assertTrue(statuses.contains("ok"), "정상 경로가 없다");
        assertTrue(statuses.contains("reject"), "거부 경로가 없다 — 판정 비교가 의미를 잃는다");
        assertTrue(rows.size() > 400, () -> "사례가 너무 적다: " + rows.size());

        // NaN·±무한대·−0.0 이 입력으로 실제로 들어간다 (언어 간 파싱·부호 차이가 드러나는 자리)
        String all = rows.stream().map(r -> String.join(",", r.cells())).collect(Collectors.joining("\n"));
        assertTrue(all.contains("NaN"), "NaN 입력이 없다");
        assertTrue(all.contains("Infinity"), "무한대 입력이 없다");
        assertTrue(all.contains("-0.0"), "−0.0 입력이 없다");
    }
}
