package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 해석해 전파기를 독립 기준(운동방정식 RK4 수치 적분, tools/gen_golden_rk4.py)과 대조한다.
 * 기준 생성기는 케플러 방정식을 쓰지 않으므로 두 결과가 맞으면 서로 다른 방법이 같은 답을 낸 것이다.
 */
class GoldenRk4Test {

    private static final double POSITION_TOLERANCE_KM = 0.01;
    private static final double VELOCITY_TOLERANCE_KMS = 1e-5;

    record Row(String name, double a, double e, double iDeg, double raanDeg, double argpDeg,
               double t, Vector3 r, Vector3 v) {}

    static List<Row> rows() throws IOException {
        try (InputStream in = GoldenRk4Test.class.getResourceAsStream("/golden/rk4_states.csv")) {
            if (in == null) {
                throw new IllegalStateException("golden/rk4_states.csv is not on the test classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return reader.lines().skip(1).map(GoldenRk4Test::parse).toList();
        }
    }

    static Stream<Row> rowStream() throws IOException {
        return rows().stream();
    }

    private static Row parse(String line) {
        String[] c = line.split(",");
        double[] d = new double[c.length];
        for (int k = 1; k < c.length; k++) {
            d[k] = Double.parseDouble(c[k]);
        }
        return new Row(c[0], d[1], d[2], d[3], d[4], d[5], d[6],
                new Vector3(d[7], d[8], d[9]), new Vector3(d[10], d[11], d[12]));
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("rowStream")
    @Tag("REQ-PRP-04")
    @DisplayName("해석해 전파 = 독립 RK4 수치 적분 (위치 0.01 km · 속도 1e-5 km/s) — LEO·SSO·MEO·Molniya·GTO")
    void analyticMatchesNumericalIntegration(Row row) {
        OrbitalElements el = new OrbitalElements(row.a(), row.e(), Math.toRadians(row.iDeg()),
                Math.toRadians(row.raanDeg()), Math.toRadians(row.argpDeg()), 0.0);
        StateVector sv = TwoBodyPropagator.stateAt(el, row.t());
        double dr = sv.positionKm().minus(row.r()).norm();
        double dv = sv.velocityKmS().minus(row.v()).norm();
        assertTrue(dr < POSITION_TOLERANCE_KM, () -> row.name() + " t=" + row.t() + " |Δr|=" + dr + " km");
        assertTrue(dv < VELOCITY_TOLERANCE_KMS, () -> row.name() + " t=" + row.t() + " |Δv|=" + dv + " km/s");
    }

    @Test
    @Tag("REQ-PRP-04")
    @DisplayName("기준 파일은 궤도 5종 × 시점 6개이고 고타원 궤도(e ≥ 0.7)를 포함한다")
    void goldenFileCoversOrbitRegimes() throws IOException {
        List<Row> rows = rows();
        assertEquals(30, rows.size());
        assertEquals(5, rows.stream().map(Row::name).distinct().count());
        assertTrue(rows.stream().anyMatch(r -> r.e() >= 0.7));
        assertTrue(rows.stream().anyMatch(r -> r.e() == 0.0));
    }
}
