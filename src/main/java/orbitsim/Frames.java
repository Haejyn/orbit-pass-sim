package orbitsim;

/**
 * 좌표계 변환. 지구는 구형으로 근사하고, ECI→ECEF 는 지구 자전각(그리니치 항성시)만 반영한다.
 * 세차·장동·극운동은 다루지 않는다 — 가시성 예측 정밀도 목표는 분 단위다.
 */
public final class Frames {

    private Frames() {}

    /** 에포크 항성시 θ0 [rad] 에서 t 초 후의 지구 자전각. */
    public static double earthRotationAngle(double thetaAtEpoch, double secondsSinceEpoch) {
        double theta = thetaAtEpoch + Constants.OMEGA_EARTH * secondsSinceEpoch;
        return normalizeAngle(theta);
    }

    /** ECI → ECEF: z 축으로 −θ 회전. */
    public static Vector3 eciToEcef(Vector3 eci, double earthRotationAngle) {
        return eci.rotateZ(-earthRotationAngle);
    }

    /** ECEF → ECI: z 축으로 +θ 회전. */
    public static Vector3 ecefToEci(Vector3 ecef, double earthRotationAngle) {
        return ecef.rotateZ(earthRotationAngle);
    }

    /** 측지 좌표 (구형 지구): 위도·경도 [rad], 고도 [km]. */
    public record Geodetic(double latitude, double longitude, double altitudeKm) {}

    public static Geodetic ecefToGeodetic(Vector3 ecef) {
        double r = ecef.norm();
        if (r == 0.0) {
            throw new IllegalArgumentException("position at Earth center has no geodetic coordinates");
        }
        double lat = Math.asin(ecef.z() / r);
        double lon = Math.atan2(ecef.y(), ecef.x());
        return new Geodetic(lat, lon, r - Constants.R_EARTH);
    }

    public static Vector3 geodeticToEcef(Geodetic g) {
        double r = Constants.R_EARTH + g.altitudeKm();
        double cl = Math.cos(g.latitude());
        return new Vector3(
                r * cl * Math.cos(g.longitude()),
                r * cl * Math.sin(g.longitude()),
                r * Math.sin(g.latitude()));
    }

    /** 각도를 [0, 2π) 로 정규화. */
    public static double normalizeAngle(double a) {
        double twoPi = 2.0 * Math.PI;
        double x = a % twoPi;
        return x < 0 ? x + twoPi : x;
    }
}
