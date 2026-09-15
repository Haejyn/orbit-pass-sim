package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PassPredictorTest {

    private static final GroundStation DAEJEON = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
    private static final OrbitalElements ISS_LIKE = OrbitalElements.circular(420.0, 51.6, 0.0);

    @Test
    @Tag("REQ-PAS-01")
    @DisplayName("ISS 궤도는 대전에서 하루 3~8번 보이고, 각 패스는 12분 이내")
    void issPassesPerDayAreRealistic() {
        List<PassPredictor.Pass> passes = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 0.0).predict(0, 86400, 10);
        assertTrue(passes.size() >= 3 && passes.size() <= 8, "passes=" + passes.size());
        for (PassPredictor.Pass p : passes) {
            assertTrue(p.durationSeconds() > 0 && p.durationSeconds() <= 12 * 60, "dur=" + p.durationSeconds());
            assertTrue(p.maxElevationDeg() > 0 && p.maxElevationDeg() <= 90);
            assertTrue(p.timeOfMaxSeconds() >= p.aosSeconds() - 10 && p.timeOfMaxSeconds() <= p.losSeconds() + 10);
        }
    }

    @Test
    @Tag("REQ-PAS-04")
    @DisplayName("패스는 시간순이고 서로 겹치지 않는다")
    void passesAreOrderedAndDisjoint() {
        List<PassPredictor.Pass> passes = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 5.0).predict(0, 2 * 86400, 10);
        for (int i = 1; i < passes.size(); i++) {
            assertTrue(passes.get(i).aosSeconds() > passes.get(i - 1).losSeconds());
        }
    }

    @Test
    @Tag("REQ-PAS-04")
    @DisplayName("최소 고도각을 올리면 패스 수가 줄거나 같다")
    void higherMaskYieldsFewerOrEqualPasses() {
        int at0 = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 0.0).predict(0, 86400, 10).size();
        int at10 = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 10.0).predict(0, 86400, 10).size();
        int at30 = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 30.0).predict(0, 86400, 10).size();
        assertTrue(at0 >= at10 && at10 >= at30, at0 + " " + at10 + " " + at30);
    }

    @Test
    @Tag("REQ-PAS-02")
    @DisplayName("AOS·LOS 시각에서 고도각이 최소 고도각 근처(±0.5°)이다")
    void aosLosSitOnTheMask() {
        double mask = 10.0;
        PassPredictor pp = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, mask);
        for (PassPredictor.Pass p : pp.predict(0, 86400, 10)) {
            assertEquals(mask, Math.toDegrees(pp.elevationAt(p.aosSeconds())), 0.5);
            assertEquals(mask, Math.toDegrees(pp.elevationAt(p.losSeconds())), 0.5);
        }
    }

    @Test
    @Tag("REQ-PAS-03")
    @DisplayName("스텝을 촘촘히 해도 패스 수와 AOS 가 크게 달라지지 않는다 (10 s vs 60 s)")
    void stepSizeRobustness() {
        PassPredictor pp = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 10.0);
        List<PassPredictor.Pass> fine = pp.predict(0, 86400, 10);
        List<PassPredictor.Pass> coarse = pp.predict(0, 86400, 60);
        assertEquals(fine.size(), coarse.size());
        for (int i = 0; i < fine.size(); i++) {
            assertEquals(fine.get(i).aosSeconds(), coarse.get(i).aosSeconds(), 2.0);
            assertEquals(fine.get(i).losSeconds(), coarse.get(i).losSeconds(), 2.0);
        }
    }

    @Test
    @Tag("REQ-PAS-05")
    @DisplayName("정지궤도 위성은 고도각이 하루 종일 거의 일정하고 패스가 하나로 이어진다")
    void geostationaryIsAlwaysVisibleFromMidLatitude() {
        // 대전 경도 위 정지궤도: θ0=0 이면 경도 = M0 − θ 이므로 M0 를 경도로 둔다
        OrbitalElements geo = new OrbitalElements(42164.0, 0.0, 0.0, 0.0, 0.0, Math.toRadians(127.38));
        PassPredictor pp = new PassPredictor(geo, DAEJEON, 0.0, 10.0);
        double e0 = Math.toDegrees(pp.elevationAt(0));
        assertTrue(e0 > 40.0 && e0 < 55.0, "elev=" + e0);
        for (double t = 0; t <= 86400; t += 3600) {
            assertEquals(e0, Math.toDegrees(pp.elevationAt(t)), 0.05);
        }
        List<PassPredictor.Pass> passes = pp.predict(0, 86400, 60);
        assertEquals(1, passes.size());
        assertEquals(0.0, passes.get(0).aosSeconds());
        assertEquals(86400.0, passes.get(0).losSeconds());
        assertEquals(e0, passes.get(0).maxElevationDeg(), 0.05);
    }

    @Test
    @Tag("REQ-PAS-01")
    @DisplayName("경사각 0° 적도 저궤도는 위도 36° 대전에서 10° 마스크로는 거의 안 보인다")
    void equatorialLeoRarelyVisibleFromDaejeon() {
        OrbitalElements eq = OrbitalElements.circular(400.0, 0.0, 0.0);
        List<PassPredictor.Pass> passes = new PassPredictor(eq, DAEJEON, 0.0, 10.0).predict(0, 86400, 10);
        assertEquals(0, passes.size());
    }

    @Test
    @Tag("REQ-PAS-01")
    @DisplayName("패스의 최대 고도각·그 시각은 1 s 간격 정밀 탐색과 일치한다 (±0.05°, ±10 s)")
    void maxElevationMatchesFineSampling() {
        PassPredictor pp = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 10.0);
        List<PassPredictor.Pass> passes = pp.predict(0, 86400, 10);
        assertFalse(passes.isEmpty());
        for (PassPredictor.Pass p : passes) {
            double best = Double.NEGATIVE_INFINITY;
            double tBest = Double.NaN;
            for (double t = p.aosSeconds(); t <= p.losSeconds(); t += 1.0) {
                double el = Math.toDegrees(pp.elevationAt(t));
                if (el > best) {
                    best = el;
                    tBest = t;
                }
            }
            assertEquals(best, p.maxElevationDeg(), 0.05, "pass at " + p.aosSeconds());
            assertEquals(tBest, p.timeOfMaxSeconds(), 10.0, "pass at " + p.aosSeconds());
        }
    }

    @Test
    @Tag("REQ-PAS-01")
    @DisplayName("창 경계가 패스 중간이면: 끝은 LOS=창 끝, 시작은 AOS=창 시작이고 최대 고도각은 시작 시점 이상")
    void windowBoundariesTruncatePasses() {
        PassPredictor pp = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 10.0);
        PassPredictor.Pass full = pp.predict(0, 86400, 10).get(0);
        double mid = (full.aosSeconds() + full.losSeconds()) / 2.0;

        List<PassPredictor.Pass> head = pp.predict(0, mid, 10);
        PassPredictor.Pass cut = head.get(head.size() - 1);
        assertEquals(mid, cut.losSeconds(), 1e-9);
        assertEquals(full.aosSeconds(), cut.aosSeconds(), 2.0);

        PassPredictor.Pass tail = pp.predict(mid, 86400, 10).get(0);
        assertEquals(mid, tail.aosSeconds(), 1e-9);
        assertEquals(full.losSeconds(), tail.losSeconds(), 2.0);
        assertTrue(tail.maxElevationDeg() >= Math.toDegrees(pp.elevationAt(mid)) - 1e-9);
    }

    @Test
    @Tag("REQ-PAS-06")
    @DisplayName("경계값 허용: 마스크 −90° 면 창 전체가 한 패스, 간격 = 창 길이도 허용")
    void boundaryArgumentsAccepted() {
        List<PassPredictor.Pass> all = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, -90.0).predict(0, 3600, 60);
        assertEquals(1, all.size());
        assertEquals(0.0, all.get(0).aosSeconds());
        assertEquals(3600.0, all.get(0).losSeconds());
        PassPredictor pp = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 10.0);
        assertDoesNotThrow(() -> pp.predict(0, 100, 100));
    }

    @Test
    @Tag("REQ-PAS-06")
    @DisplayName("잘못된 창·스텝·마스크는 거부")
    void rejectsInvalidArguments() {
        PassPredictor pp = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 10.0);
        assertThrows(IllegalArgumentException.class, () -> pp.predict(100, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> pp.predict(200, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> pp.predict(0, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> pp.predict(0, 100, 101));
        // 창 시작이 0 이 아닐 때 창 길이는 end − start 다 (PIT: end + start 로 바꾼 뮤턴트가 살아남았다)
        assertThrows(IllegalArgumentException.class, () -> pp.predict(1000, 1100, 150));
        assertThrows(IllegalArgumentException.class, () -> new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 90.0));
        assertThrows(IllegalArgumentException.class, () -> new PassPredictor(ISS_LIKE, DAEJEON, 0.0, -90.1));
        assertThrows(IllegalArgumentException.class, () -> new PassPredictor(ISS_LIKE, DAEJEON, 0.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> pp.predict(0, Double.NaN, 10));
        assertThrows(IllegalArgumentException.class, () -> pp.predict(0, 100, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new PassPredictor(null, DAEJEON, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new PassPredictor(ISS_LIKE, null, 0.0, 0.0));
    }

    @Test
    @Tag("REQ-PAS-04")
    @DisplayName("결과 목록은 수정 불가")
    void resultIsUnmodifiable() {
        List<PassPredictor.Pass> passes = new PassPredictor(ISS_LIKE, DAEJEON, 0.0, 0.0).predict(0, 86400, 30);
        assertThrows(UnsupportedOperationException.class, passes::clear);
    }
}
