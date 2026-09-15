package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TwoBodyPropagatorTest {

    private static final OrbitalElements ECCENTRIC =
            new OrbitalElements(12000.0, 0.4, Math.toRadians(63.4), Math.toRadians(40), Math.toRadians(270), 0.0);

    @Test
    @Tag("REQ-PRP-01")
    @DisplayName("에포크(M0=0)에서 위성은 근지점에 있고 r = a(1−e)")
    void startsAtPerigee() {
        StateVector sv = TwoBodyPropagator.stateAt(ECCENTRIC, 0.0);
        assertEquals(ECCENTRIC.perigeeRadiusKm(), sv.positionKm().norm(), 1e-9);
    }

    @Test
    @Tag("REQ-PRP-01")
    @DisplayName("반주기 뒤에는 원지점, r = a(1+e)")
    void reachesApogeeAfterHalfPeriod() {
        StateVector sv = TwoBodyPropagator.stateAt(ECCENTRIC, ECCENTRIC.periodSeconds() / 2.0);
        assertEquals(ECCENTRIC.apogeeRadiusKm(), sv.positionKm().norm(), 1e-6);
    }

    @Test
    @Tag("REQ-PRP-03")
    @DisplayName("한 주기 뒤 위치·속도가 처음으로 돌아온다")
    void periodicity() {
        StateVector a = TwoBodyPropagator.stateAt(ECCENTRIC, 0.0);
        StateVector b = TwoBodyPropagator.stateAt(ECCENTRIC, ECCENTRIC.periodSeconds());
        assertEquals(0.0, a.positionKm().minus(b.positionKm()).norm(), 1e-6);
        assertEquals(0.0, a.velocityKmS().minus(b.velocityKmS()).norm(), 1e-9);
    }

    @ParameterizedTest(name = "e={0}")
    @Tag("REQ-PRP-02")
    @ValueSource(doubles = {0.0, 0.1, 0.5, 0.9})
    @DisplayName("비에너지가 궤도 내내 보존된다 (상대 오차 1e-10)")
    void specificEnergyConserved(double e) {
        OrbitalElements el = new OrbitalElements(80000.0, e, 1.0, 0.5, 0.3, 0.0);
        double expected = -Constants.MU_EARTH / (2.0 * el.semiMajorAxisKm());
        double period = el.periodSeconds();
        for (int k = 0; k <= 200; k++) {
            double t = period * k / 200.0;
            double energy = TwoBodyPropagator.stateAt(el, t).specificEnergy();
            assertEquals(expected, energy, Math.abs(expected) * 1e-10, "t=" + t);
        }
    }

    @ParameterizedTest(name = "e={0}")
    @Tag("REQ-PRP-02")
    @ValueSource(doubles = {0.0, 0.3, 0.8})
    @DisplayName("비각운동량 벡터가 보존되고 |h| = sqrt(μ·p)")
    void angularMomentumConserved(double e) {
        OrbitalElements el = new OrbitalElements(40000.0, e, 0.7, 1.2, 2.5, 0.0);
        double p = el.semiMajorAxisKm() * (1 - e * e);
        double expectedNorm = Math.sqrt(Constants.MU_EARTH * p);
        Vector3 h0 = TwoBodyPropagator.stateAt(el, 0.0).specificAngularMomentum();
        assertEquals(expectedNorm, h0.norm(), expectedNorm * 1e-12);
        for (int k = 1; k <= 50; k++) {
            Vector3 h = TwoBodyPropagator.stateAt(el, el.periodSeconds() * k / 50.0).specificAngularMomentum();
            assertEquals(0.0, h.minus(h0).norm(), expectedNorm * 1e-10);
        }
    }

    @Test
    @Tag("REQ-PRP-01")
    @DisplayName("각운동량 방향이 경사각 i 를 재현한다 (h_z = |h|·cos i)")
    void angularMomentumEncodesInclination() {
        Vector3 h = TwoBodyPropagator.stateAt(ECCENTRIC, 100.0).specificAngularMomentum();
        assertEquals(Math.cos(ECCENTRIC.inclination()), h.z() / h.norm(), 1e-12);
    }

    @Test
    @Tag("REQ-PRP-01")
    @DisplayName("적도 원궤도: 위성은 항상 z=0 평면, 속도 크기 sqrt(μ/a)")
    void equatorialCircular() {
        OrbitalElements el = new OrbitalElements(7000.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        double vExpected = Math.sqrt(Constants.MU_EARTH / 7000.0);
        for (double t = 0; t < el.periodSeconds(); t += 300) {
            StateVector sv = TwoBodyPropagator.stateAt(el, t);
            assertEquals(0.0, sv.positionKm().z(), 1e-9);
            assertEquals(7000.0, sv.positionKm().norm(), 1e-9);
            assertEquals(vExpected, sv.velocityKmS().norm(), 1e-12);
        }
    }

    @Test
    @Tag("REQ-PRP-03")
    @DisplayName("음수 시간도 허용되고 주기 대칭이다")
    void negativeTimeIsSymmetric() {
        StateVector back = TwoBodyPropagator.stateAt(ECCENTRIC, -1000.0);
        StateVector fwd = TwoBodyPropagator.stateAt(ECCENTRIC, ECCENTRIC.periodSeconds() - 1000.0);
        assertEquals(0.0, back.positionKm().minus(fwd.positionKm()).norm(), 1e-6);
    }

    @Test
    @Tag("REQ-PRP-06")
    @DisplayName("무한대·NaN 시간은 거부")
    void rejectsNonFiniteTime() {
        assertThrows(IllegalArgumentException.class, () -> TwoBodyPropagator.stateAt(ECCENTRIC, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TwoBodyPropagator.stateAt(ECCENTRIC, Double.POSITIVE_INFINITY));
    }
}
