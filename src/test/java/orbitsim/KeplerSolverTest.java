package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class KeplerSolverTest {

    @Test
    @DisplayName("원궤도(e=0)에서는 E = M")
    void circularOrbitReturnsMeanAnomaly() {
        for (double m = -10; m <= 10; m += 0.37) {
            assertEquals(m, KeplerSolver.solveEccentricAnomaly(m, 0.0), 1e-12);
        }
    }

    @ParameterizedTest(name = "e={0}")
    @ValueSource(doubles = {0.0, 0.1, 0.3, 0.5, 0.7, 0.8, 0.9, 0.95, 0.99})
    @DisplayName("전 범위 M 에 대해 잔차 |E − e·sinE − M| < 1e-11")
    void residualIsTinyAcrossMeanAnomalies(double e) {
        for (double m = -2 * Math.PI; m <= 4 * Math.PI; m += 0.05) {
            double eAnom = KeplerSolver.solveEccentricAnomaly(m, e);
            double residual = eAnom - e * Math.sin(eAnom) - m;
            assertEquals(0.0, residual, 1e-11, "M=" + m + " e=" + e);
        }
    }

    @ParameterizedTest(name = "M={0}, e={1} → E={2}")
    @CsvSource({
        "0.0, 0.5, 0.0",
        "3.141592653589793, 0.5, 3.141592653589793",
        "1.0, 0.1, 1.0885977523978934",
        "2.0, 0.9, 2.5223654340002444",
        "0.5, 0.5, 0.8878622115708661",
        "5.0, 0.7, 4.3463686514876425",
    })
    @DisplayName("독립 구현과 대조 (M=0·π 는 해석해, 나머지는 Python 이분법으로 1e-15 까지 계산한 값)")
    void matchesKnownValues(double m, double e, double expectedE) {
        assertEquals(expectedE, KeplerSolver.solveEccentricAnomaly(m, e), 1e-9);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.0, 1.5, Double.POSITIVE_INFINITY})
    @DisplayName("이심률 범위 밖은 IllegalArgumentException")
    void rejectsEccentricityOutOfRange(double e) {
        assertThrows(IllegalArgumentException.class, () -> KeplerSolver.solveEccentricAnomaly(1.0, e));
    }

    @Test
    @DisplayName("NaN 입력은 IllegalArgumentException")
    void rejectsNaN() {
        assertThrows(IllegalArgumentException.class, () -> KeplerSolver.solveEccentricAnomaly(Double.NaN, 0.1));
        assertThrows(IllegalArgumentException.class, () -> KeplerSolver.solveEccentricAnomaly(1.0, Double.NaN));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.2, 0.6, 0.95})
    @DisplayName("진근점 이각 ↔ 이심 근점 이각 왕복 변환")
    void trueAnomalyRoundTrip(double e) {
        for (double eAnom = -3.0; eAnom <= 3.0; eAnom += 0.25) {
            double nu = KeplerSolver.trueAnomaly(eAnom, e);
            assertEquals(eAnom, KeplerSolver.eccentricFromTrue(nu, e), 1e-12);
        }
    }

    @Test
    @DisplayName("근지점(E=0)·원지점(E=π)에서 ν 도 0·π")
    void apsidesMapToThemselves() {
        assertEquals(0.0, KeplerSolver.trueAnomaly(0.0, 0.7), 1e-15);
        assertEquals(Math.PI, Math.abs(KeplerSolver.trueAnomaly(Math.PI, 0.7)), 1e-12);
    }
}
