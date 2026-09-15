package orbitsim;

/**
 * 고전 궤도 요소 (케플러 요소). 각도는 rad, 길이는 km.
 *
 * @param semiMajorAxisKm 장반경 a — 근지점이 지표 위여야 한다
 * @param eccentricity 이심률 e, 0 ≤ e < 1
 * @param inclination 궤도 경사각 i, [0, π]
 * @param raan 승교점 적경 Ω
 * @param argPerigee 근지점 인수 ω
 * @param meanAnomalyAtEpoch 에포크 평균 근점 이각 M0
 */
public record OrbitalElements(
        double semiMajorAxisKm,
        double eccentricity,
        double inclination,
        double raan,
        double argPerigee,
        double meanAnomalyAtEpoch) {

    public OrbitalElements {
        requireFinite(semiMajorAxisKm, "a");
        requireFinite(eccentricity, "e");
        requireFinite(inclination, "i");
        requireFinite(raan, "raan");
        requireFinite(argPerigee, "argPerigee");
        requireFinite(meanAnomalyAtEpoch, "M0");
        if (eccentricity < 0.0 || eccentricity >= 1.0) {
            throw new IllegalArgumentException("eccentricity must be in [0,1): " + eccentricity);
        }
        if (inclination < 0.0 || inclination > Math.PI) {
            throw new IllegalArgumentException("inclination must be in [0,pi]: " + inclination);
        }
        double perigee = semiMajorAxisKm * (1.0 - eccentricity);
        if (perigee <= Constants.R_EARTH) {
            throw new IllegalArgumentException(
                    "perigee radius " + perigee + " km must exceed Earth radius " + Constants.R_EARTH);
        }
    }

    private static void requireFinite(double v, String name) {
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException(name + " must be finite: " + v);
        }
    }

    /** 원궤도 편의 생성자 — 고도(km)·경사각(deg)·RAAN(deg) 만 준다. */
    public static OrbitalElements circular(double altitudeKm, double inclinationDeg, double raanDeg) {
        return new OrbitalElements(
                Constants.R_EARTH + altitudeKm, 0.0,
                Math.toRadians(inclinationDeg), Math.toRadians(raanDeg), 0.0, 0.0);
    }

    /** 궤도 주기 T = 2π·sqrt(a³/μ) [s]. */
    public double periodSeconds() {
        double a = semiMajorAxisKm;
        return 2.0 * Math.PI * Math.sqrt(a * a * a / Constants.MU_EARTH);
    }

    /** 평균 운동 n = sqrt(μ/a³) [rad/s]. */
    public double meanMotion() {
        double a = semiMajorAxisKm;
        return Math.sqrt(Constants.MU_EARTH / (a * a * a));
    }

    public double perigeeRadiusKm() { return semiMajorAxisKm * (1.0 - eccentricity); }

    public double apogeeRadiusKm() { return semiMajorAxisKm * (1.0 + eccentricity); }
}
