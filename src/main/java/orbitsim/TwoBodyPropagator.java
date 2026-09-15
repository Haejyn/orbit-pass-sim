package orbitsim;

/**
 * 이체 문제 해석해 전파기. 섭동(J2·대기항력)은 다루지 않는다.
 * 궤도 요소 → 근점 좌표계(PQW) 위치·속도 → 3회 회전으로 ECI.
 */
public final class TwoBodyPropagator {

    private TwoBodyPropagator() {}

    /**
     * @param el 궤도 요소
     * @param secondsSinceEpoch 에포크 이후 경과 시간 [s], 음수 허용
     * @return ECI 상태 벡터
     */
    public static StateVector stateAt(OrbitalElements el, double secondsSinceEpoch) {
        if (!Double.isFinite(secondsSinceEpoch)) {
            throw new IllegalArgumentException("time must be finite: " + secondsSinceEpoch);
        }
        double e = el.eccentricity();
        double a = el.semiMajorAxisKm();
        double meanAnomaly = el.meanAnomalyAtEpoch() + el.meanMotion() * secondsSinceEpoch;
        double eAnom = KeplerSolver.solveEccentricAnomaly(meanAnomaly, e);
        double nu = KeplerSolver.trueAnomaly(eAnom, e);

        double p = a * (1.0 - e * e);
        double r = p / (1.0 + e * Math.cos(nu));
        Vector3 posPqw = new Vector3(r * Math.cos(nu), r * Math.sin(nu), 0.0);
        double k = Math.sqrt(Constants.MU_EARTH / p);
        Vector3 velPqw = new Vector3(-k * Math.sin(nu), k * (e + Math.cos(nu)), 0.0);

        return new StateVector(pqwToEci(posPqw, el), pqwToEci(velPqw, el));
    }

    /** PQW → ECI: R_z(Ω)·R_x(i)·R_z(ω). */
    static Vector3 pqwToEci(Vector3 v, OrbitalElements el) {
        Vector3 afterArgp = v.rotateZ(el.argPerigee());
        double ci = Math.cos(el.inclination());
        double si = Math.sin(el.inclination());
        Vector3 afterInc = new Vector3(
                afterArgp.x(),
                ci * afterArgp.y() - si * afterArgp.z(),
                si * afterArgp.y() + ci * afterArgp.z());
        return afterInc.rotateZ(el.raan());
    }
}
