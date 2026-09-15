package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class GroundStationTest {

    private static final GroundStation DAEJEON = new GroundStation("Daejeon", 36.35, 127.38, 0.07);

    @Test
    @Tag("REQ-GST-01")
    @DisplayName("지상국 바로 위 400 km 위성은 고도각 90°, 거리 400 km")
    void zenithSatellite() {
        Vector3 up = DAEJEON.positionEcef().normalized();
        Vector3 sat = DAEJEON.positionEcef().plus(up.scale(400.0));
        GroundStation.LookAngles la = DAEJEON.lookAngles(sat);
        assertEquals(90.0, la.elevationDeg(), 1e-9);
        assertEquals(400.0, la.rangeKm(), 1e-9);
    }

    @Test
    @Tag("REQ-GST-02")
    @DisplayName("지구 반대편 위성은 고도각이 음수")
    void antipodalSatelliteBelowHorizon() {
        Vector3 sat = DAEJEON.positionEcef().scale(-1.0).scale(1.1);
        assertTrue(DAEJEON.lookAngles(sat).elevationDeg() < -80.0);
    }

    @Test
    @Tag("REQ-GST-01")
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
    @Tag("REQ-GST-01")
    @DisplayName("중위도(대전)에서 독립적으로 만든 국지 기저(ẑ×û, û×ê)와 대조: 동 90°·북 0°·북동 45°/고도 30°")
    void localTangentBasisAtMidLatitude() {
        // 적도·경도 0° 지상국은 sin 항이 0 이라 ENU 행렬의 부호·항 실수를 못 잡는다 (PIT 생존 뮤턴트)
        Vector3 here = DAEJEON.positionEcef();
        Vector3 up = here.normalized();
        Vector3 east = new Vector3(0, 0, 1).cross(up).normalized();
        Vector3 north = up.cross(east);

        GroundStation.LookAngles e = DAEJEON.lookAngles(here.plus(east.scale(500.0)));
        assertEquals(90.0, e.azimuthDeg(), 1e-9);
        assertEquals(0.0, e.elevationDeg(), 1e-9);

        GroundStation.LookAngles n = DAEJEON.lookAngles(here.plus(north.scale(500.0)));
        assertEquals(0.0, Math.sin(Math.toRadians(n.azimuthDeg())), 1e-11);
        assertTrue(Math.cos(Math.toRadians(n.azimuthDeg())) > 0.0);
        assertEquals(0.0, n.elevationDeg(), 1e-9);

        double el = Math.toRadians(30.0);
        double az = Math.toRadians(45.0);
        Vector3 dir = north.scale(Math.cos(el) * Math.cos(az))
                .plus(east.scale(Math.cos(el) * Math.sin(az)))
                .plus(up.scale(Math.sin(el)));
        GroundStation.LookAngles ne = DAEJEON.lookAngles(here.plus(dir.scale(800.0)));
        assertEquals(45.0, ne.azimuthDeg(), 1e-9);
        assertEquals(30.0, ne.elevationDeg(), 1e-9);
        assertEquals(800.0, ne.rangeKm(), 1e-9);
    }

    @Test
    @Tag("REQ-GST-02")
    @DisplayName("고도각은 항상 [-90°, 90°]")
    void elevationWithinBounds() {
        for (int k = 0; k < 360; k += 15) {
            Vector3 sat = new Vector3(8000, 0, 0).rotateZ(Math.toRadians(k));
            double el = DAEJEON.lookAngles(sat).elevationDeg();
            assertTrue(el >= -90.0 && el <= 90.0, "k=" + k + " el=" + el);
        }
    }

    @Test
    @Tag("REQ-GST-03")
    @DisplayName("위성이 지상국과 겹치면 거부")
    void coincidentSatelliteRejected() {
        assertThrows(IllegalArgumentException.class, () -> DAEJEON.lookAngles(DAEJEON.positionEcef()));
    }

    @Test
    @Tag("REQ-GST-03")
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
