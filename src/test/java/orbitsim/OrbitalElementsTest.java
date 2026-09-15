package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class OrbitalElementsTest {

    @Test
    @Tag("REQ-ELM-01")
    @DisplayName("ISS 고도 원궤도 주기는 약 92.8분")
    void issLikePeriod() {
        OrbitalElements el = OrbitalElements.circular(420.0, 51.6, 0.0);
        assertEquals(92.8, el.periodSeconds() / 60.0, 0.2);
    }

    @Test
    @Tag("REQ-ELM-01")
    @DisplayName("정지궤도 반경(42164 km)의 주기는 항성일(86164 s)")
    void geostationaryPeriodIsSiderealDay() {
        OrbitalElements el = new OrbitalElements(42164.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(86164.1, el.periodSeconds(), 1.0);
    }

    @Test
    @Tag("REQ-ELM-01")
    @DisplayName("근지점·원지점 반경")
    void apsisRadii() {
        OrbitalElements el = new OrbitalElements(10000.0, 0.3, 0.0, 0.0, 0.0, 0.0);
        assertEquals(7000.0, el.perigeeRadiusKm(), 1e-9);
        assertEquals(13000.0, el.apogeeRadiusKm(), 1e-9);
    }

    @Test
    @Tag("REQ-ELM-02")
    @DisplayName("근지점이 지표 아래면 거부")
    void rejectsPerigeeBelowSurface() {
        assertThrows(IllegalArgumentException.class,
                () -> new OrbitalElements(7000.0, 0.2, 0.0, 0.0, 0.0, 0.0)); // perigee 5600 km
        assertThrows(IllegalArgumentException.class, () -> OrbitalElements.circular(0.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> OrbitalElements.circular(-100.0, 0.0, 0.0));
    }

    @Test
    @Tag("REQ-ELM-02")
    @DisplayName("이심률·경사각 범위 밖 거부")
    void rejectsOutOfRangeAngles() {
        assertThrows(IllegalArgumentException.class, () -> new OrbitalElements(8000, 1.0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrbitalElements(8000, -0.01, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrbitalElements(8000, 0.0, -0.1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrbitalElements(8000, 0.0, Math.PI + 0.1, 0, 0, 0));
    }

    @Test
    @Tag("REQ-ELM-02")
    @DisplayName("NaN·무한대 요소 거부")
    void rejectsNonFinite() {
        assertThrows(IllegalArgumentException.class, () -> new OrbitalElements(Double.NaN, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OrbitalElements(8000, 0, 0, Double.POSITIVE_INFINITY, 0, 0));
    }

    @Test
    @Tag("REQ-ELM-02")
    @DisplayName("경계값: e=0, i=0, i=π 는 허용")
    void acceptsBoundaryValues() {
        assertDoesNotThrow(() -> new OrbitalElements(8000, 0.0, 0.0, 0, 0, 0));
        assertDoesNotThrow(() -> new OrbitalElements(8000, 0.0, Math.PI, 0, 0, 0));
    }
}
