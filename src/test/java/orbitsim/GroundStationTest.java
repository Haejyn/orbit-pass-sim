package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GroundStationTest {

    private static final GroundStation DAEJEON = new GroundStation("Daejeon", 36.35, 127.38, 0.07);

    @Test
    @DisplayName("지상국 바로 위 400 km 위성은 고도각 90°, 거리 400 km")
    void zenithSatellite() {
        Vector3 up = DAEJEON.positionEcef().normalized();
        Vector3 sat = DAEJEON.positionEcef().plus(up.scale(400.0));
        GroundStation.LookAngles la = DAEJEON.lookAngles(sat);
        assertEquals(90.0, la.elevationDeg(), 1e-9);
        assertEquals(400.0, la.rangeKm(), 1e-9);
    }

    @Test
    @DisplayName("지구 반대편 위성은 고도각이 음수")
    void antipodalSatelliteBelowHorizon() {
        Vector3 sat = DAEJEON.positionEcef().scale(-1.0).scale(1.1);
        assertTrue(DAEJEON.lookAngles(sat).elevationDeg() < -80.0);
    }

    @Test
    @DisplayName("적도 지상국에서 정북·정동 방향 방위각")
    void azimuthAtEquator() {
        GroundStation eq = new GroundStation("Eq", 0.0, 0.0, 0.0);
        Vector3 here = eq.positionEcef();
        Vector3 north = here.plus(new Vector3(0, 0, 1000.0));   // +z 는 북
        Vector3 east = here.plus(new Vector3(0, 1000.0, 0));    // 경도 0 에서 +y 는 동
        assertEquals(0.0, eq.lookAngles(north).azimuthDeg(), 1e-9);
        assertEquals(90.0, eq.lookAngles(east).azimuthDeg(), 1e-9);
        Vector3 west = here.plus(new Vector3(0, -1000.0, 0));
        assertEquals(270.0, eq.lookAngles(west).azimuthDeg(), 1e-9);
    }

    @Test
    @DisplayName("고도각은 항상 [-90°, 90°]")
    void elevationWithinBounds() {
        for (int k = 0; k < 360; k += 15) {
            Vector3 sat = new Vector3(8000, 0, 0).rotateZ(Math.toRadians(k));
            double el = DAEJEON.lookAngles(sat).elevationDeg();
            assertTrue(el >= -90.0 && el <= 90.0, "k=" + k + " el=" + el);
        }
    }

    @Test
    @DisplayName("위성이 지상국과 겹치면 거부")
    void coincidentSatelliteRejected() {
        assertThrows(IllegalArgumentException.class, () -> DAEJEON.lookAngles(DAEJEON.positionEcef()));
    }

    @Test
    @DisplayName("위도·경도·고도·이름 유효성 검사")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", 90.1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", 0, 180.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", -90.1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", 0, -180.1, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", 0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", 0, 0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", 0, 0, -5));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation("x", Double.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation(" ", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GroundStation(null, 0, 0, 0));
        assertDoesNotThrow(() -> new GroundStation("pole", 90.0, -180.0, 0));
    }
}
