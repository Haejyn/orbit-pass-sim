package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 성질 기반 강건성 시험. 고정 시드 난수로 넓은 입력 공간을 훑는다 — 실패하면 같은 시드로 재현된다.
 * 이 클래스의 첫 두 시험이 결함 D-4(케플러 비수렴)·D-5(각도 정규화 2π)를 찾았다. docs/test-report.md 참고.
 */
class RobustnessTest {

    private static final long SEED = 20260915L;
    private static final double TWO_PI = 2.0 * Math.PI;

    @Test
    @Tag("REQ-KEP-01")
    @DisplayName("[D-4] |M| 이 1e15 까지 커져도 케플러 풀이는 예외 없이 잔차 조건을 만족한다")
    void keplerConvergesForHugeMeanAnomaly() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        for (double magnitude : new double[] {1e3, 1e6, 1e9, 1e12, 1e15}) {
            for (int k = 0; k < 2_000; k++) {
                double m = magnitude * (0.5 + rnd.nextDouble()) * (rnd.nextBoolean() ? 1 : -1);
                double e = 0.999 * rnd.nextDouble();
                double eAnom = assertDoesNotThrow(() -> KeplerSolver.solveEccentricAnomaly(m, e),
                        () -> "M=" + m + " e=" + e);
                // 잔차는 2π 주기로 본다: |M| 이 크면 M 자체의 부동소수점 간격(ulp)보다 정밀할 수 없다
                double residual = Math.IEEEremainder(eAnom - e * Math.sin(eAnom) - m, TWO_PI);
                double allowed = Math.max(1e-11, 4 * Math.ulp(m));
                assertTrue(Math.abs(residual) <= allowed, "M=" + m + " e=" + e + " residual=" + residual);
            }
        }
    }

    @ParameterizedTest(name = "θ={0}")
    // 어노테이션 값은 컴파일 타임 상수여야 한다: 8.881784197001252E-16 = ulp(2π), 6.283185307179586 = 2π
    @ValueSource(doubles = {-1e-16, -1e-17, -Double.MIN_VALUE, -8.881784197001252E-16, -0.0, 0.0,
            6.283185307179586, -6.283185307179586, 1e300, -1e300, 6.283185307179585})
    @Tag("REQ-FRM-03")
    @DisplayName("[D-5] 각도 정규화는 적대적 경계 입력에서도 [0, 2π) 이다")
    void normalizeAngleStaysInHalfOpenRange(double theta) {
        double r = Frames.normalizeAngle(theta);
        assertTrue(r >= 0.0 && r < TWO_PI, "normalizeAngle(" + theta + ") = " + r);
    }

    @Test
    @Tag("REQ-FRM-03")
    @DisplayName("각도 정규화: 무작위 100,000 건이 [0, 2π) 이고 원래 각과 2π 배수만큼 차이난다")
    void normalizeAngleRandomized() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        for (int k = 0; k < 100_000; k++) {
            double theta = (rnd.nextDouble() - 0.5) * Math.pow(10, rnd.nextInt(-20, 6));
            double r = Frames.normalizeAngle(theta);
            assertTrue(r >= 0.0 && r < TWO_PI, "theta=" + theta + " r=" + r);
            assertEquals(0.0, Math.sin(r - theta), 1e-9 * Math.max(1.0, Math.abs(theta)));
        }
    }

    @Test
    @Tag("REQ-GST-02")
    @DisplayName("관측각: 무작위 위성·지상국 10,000 건에서 고도각 [−90°, 90°], 방위각 [0°, 360°)")
    void lookAnglesStayInRange() {
        SplittableRandom rnd = new SplittableRandom(SEED);
        for (int k = 0; k < 10_000; k++) {
            GroundStation gs = new GroundStation("r" + k, rnd.nextDouble(-90, 90), rnd.nextDouble(-180, 180),
                    rnd.nextDouble(0, 3));
            Vector3 dir = new Vector3(rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1), rnd.nextDouble(-1, 1));
            if (dir.norm() < 1e-6) {
                continue;
            }
            Vector3 sat = dir.normalized().scale(rnd.nextDouble(6_600, 45_000));
            GroundStation.LookAngles la = gs.lookAngles(sat);
            assertTrue(la.elevationDeg() >= -90.0 && la.elevationDeg() <= 90.0, "el=" + la.elevationDeg());
            assertTrue(la.azimuthDeg() >= 0.0 && la.azimuthDeg() < 360.0, "az=" + la.azimuthDeg());
            assertTrue(la.rangeKm() > 0.0);
        }
    }

    @Test
    @Tag("REQ-GST-02")
    @DisplayName("[D-5] 정북에서 아주 조금 서쪽인 위성의 방위각은 360° 가 아니다")
    void azimuthJustWestOfNorthIsBelow360() {
        GroundStation eq = new GroundStation("Eq", 0.0, 0.0, 0.0);
        Vector3 sat = eq.positionEcef().plus(new Vector3(0, -1e-20, 1000.0));
        double az = eq.lookAngles(sat).azimuthDeg();
        assertTrue(az >= 0.0 && az < 360.0, "az=" + az);
    }

    @ParameterizedTest(name = "{0} 년")
    @ValueSource(doubles = {1, 30, 1_000})
    @Tag("REQ-PRP-05")
    @DisplayName("장기 전파: 고타원·중간·저이심 궤도 무작위 500 건이 예외 없이 비에너지를 보존한다")
    void longTermPropagationIsStable(double years) {
        SplittableRandom rnd = new SplittableRandom(SEED);
        double t = years * 365.25 * 86_400.0;
        for (int k = 0; k < 500; k++) {
            double e = rnd.nextDouble(0.0, 0.85);
            double perigee = rnd.nextDouble(6_600, 20_000);
            double a = perigee / (1 - e);
            OrbitalElements el = new OrbitalElements(a, e, rnd.nextDouble(0, Math.PI),
                    rnd.nextDouble(0, TWO_PI), rnd.nextDouble(0, TWO_PI), rnd.nextDouble(0, TWO_PI));
            double expected = -Constants.MU_EARTH / (2 * a);
            StateVector sv = assertDoesNotThrow(() -> TwoBodyPropagator.stateAt(el, t), () -> el.toString());
            assertEquals(expected, sv.specificEnergy(), Math.abs(expected) * 1e-9, el::toString);
        }
    }
}
