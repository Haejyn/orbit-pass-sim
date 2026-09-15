package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class FramesTest {

    @Test
    @Tag("REQ-FRM-01")
    @DisplayName("ECI→ECEF→ECI 왕복은 항등")
    void eciEcefRoundTrip() {
        Vector3 v = new Vector3(6000, -2000, 3000);
        for (double theta = 0; theta < 7; theta += 0.5) {
            Vector3 back = Frames.ecefToEci(Frames.eciToEcef(v, theta), theta);
            assertEquals(0.0, v.minus(back).norm(), 1e-9);
        }
    }

    @Test
    @Tag("REQ-FRM-01")
    @DisplayName("θ=0 이면 ECI 와 ECEF 가 같고, θ=π/2 면 x 축이 −y 로 간다")
    void knownRotations() {
        Vector3 x = new Vector3(1, 0, 0);
        assertEquals(x, Frames.eciToEcef(x, 0.0));
        Vector3 r = Frames.eciToEcef(x, Math.PI / 2);
        assertEquals(0.0, r.x(), 1e-15);
        assertEquals(-1.0, r.y(), 1e-15);
    }

    @Test
    @Tag("REQ-FRM-02")
    @DisplayName("지구 자전각은 항성일(86164 s)마다 한 바퀴")
    void rotationAngleAdvancesOneTurnPerSiderealDay() {
        double theta0 = 1.234;
        double after = Frames.earthRotationAngle(theta0, 86164.0905);
        assertEquals(theta0, after, 1e-6);
        assertEquals(theta0 + Math.PI, Frames.earthRotationAngle(theta0, 86164.0905 / 2), 1e-6);
    }

    @Test
    @Tag("REQ-FRM-01")
    @DisplayName("측지 ↔ ECEF 왕복 (극·적도·일반점)")
    void geodeticRoundTrip() {
        double[][] pts = {{0, 0, 0}, {Math.PI / 2, 0, 100}, {-Math.PI / 2, 1.0, 0},
                {Math.toRadians(36.35), Math.toRadians(127.38), 0.07}, {0.3, -2.9, 500}};
        for (double[] p : pts) {
            Frames.Geodetic g = new Frames.Geodetic(p[0], p[1], p[2]);
            Frames.Geodetic back = Frames.ecefToGeodetic(Frames.geodeticToEcef(g));
            assertEquals(g.latitude(), back.latitude(), 1e-12);
            assertEquals(g.altitudeKm(), back.altitudeKm(), 1e-9);
            if (Math.abs(Math.cos(g.latitude())) > 1e-9) { // 극에서는 경도가 정의되지 않는다
                assertEquals(g.longitude(), back.longitude(), 1e-12);
            }
        }
    }

    @Test
    @Tag("REQ-FRM-01")
    @DisplayName("적도 위 x 축 방향 점은 위도 0, 경도 0, 고도 = r − R")
    void equatorPrimeMeridian() {
        Frames.Geodetic g = Frames.ecefToGeodetic(new Vector3(Constants.R_EARTH + 400.0, 0, 0));
        assertEquals(0.0, g.latitude(), 1e-15);
        assertEquals(0.0, g.longitude(), 1e-15);
        assertEquals(400.0, g.altitudeKm(), 1e-9);
    }

    @Test
    @Tag("REQ-FRM-01")
    @DisplayName("지구 중심은 측지 좌표가 없다")
    void centerHasNoGeodetic() {
        assertThrows(IllegalArgumentException.class, () -> Frames.ecefToGeodetic(Vector3.ZERO));
    }

    @Test
    @Tag("REQ-FRM-03")
    @DisplayName("각도 정규화는 [0, 2π)")
    void normalizeAngle() {
        assertEquals(0.0, Frames.normalizeAngle(0.0));
        assertEquals(0.0, Frames.normalizeAngle(2 * Math.PI), 1e-15);
        assertEquals(Math.PI, Frames.normalizeAngle(-Math.PI), 1e-15);
        assertEquals(1.0, Frames.normalizeAngle(1.0 + 4 * Math.PI), 1e-12);
        assertTrue(Frames.normalizeAngle(-1e-9) < 2 * Math.PI);
    }
}
