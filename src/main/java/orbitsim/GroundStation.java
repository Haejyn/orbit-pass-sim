package orbitsim;

/**
 * 지상국. 위도·경도는 deg 로 받고 내부에서 rad 로 쓴다.
 */
public record GroundStation(String name, double latitudeDeg, double longitudeDeg, double altitudeKm) {

    public GroundStation {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name required");
        }
        if (!(latitudeDeg >= -90.0 && latitudeDeg <= 90.0)) {
            throw new IllegalArgumentException("latitude out of range: " + latitudeDeg);
        }
        if (!(longitudeDeg >= -180.0 && longitudeDeg <= 180.0)) {
            throw new IllegalArgumentException("longitude out of range: " + longitudeDeg);
        }
        if (!Double.isFinite(altitudeKm) || altitudeKm < -1.0) {
            throw new IllegalArgumentException("altitude invalid: " + altitudeKm);
        }
    }

    /** 관측 결과: 고도각·방위각 [rad], 거리 [km]. */
    public record LookAngles(double elevation, double azimuth, double rangeKm) {
        public double elevationDeg() { return Math.toDegrees(elevation); }
        public double azimuthDeg() { return Math.toDegrees(azimuth); }
    }

    public Vector3 positionEcef() {
        return Frames.geodeticToEcef(new Frames.Geodetic(
                Math.toRadians(latitudeDeg), Math.toRadians(longitudeDeg), altitudeKm));
    }

    /**
     * 위성 ECEF 위치를 지상국 기준 ENU 로 옮겨 고도각·방위각을 구한다.
     * 방위각은 북쪽 0, 동쪽 π/2, [0, 2π).
     */
    public LookAngles lookAngles(Vector3 satEcef) {
        Vector3 rho = satEcef.minus(positionEcef());
        double lat = Math.toRadians(latitudeDeg);
        double lon = Math.toRadians(longitudeDeg);
        double sl = Math.sin(lat);
        double cl = Math.cos(lat);
        double so = Math.sin(lon);
        double co = Math.cos(lon);
        double east = -so * rho.x() + co * rho.y();
        double north = -sl * co * rho.x() - sl * so * rho.y() + cl * rho.z();
        double up = cl * co * rho.x() + cl * so * rho.y() + sl * rho.z();
        double range = rho.norm();
        if (range == 0.0) {
            throw new IllegalArgumentException("satellite coincides with station");
        }
        double elevation = Math.asin(up / range);
        double azimuth = Frames.normalizeAngle(Math.atan2(east, north));
        return new LookAngles(elevation, azimuth, range);
    }
}
