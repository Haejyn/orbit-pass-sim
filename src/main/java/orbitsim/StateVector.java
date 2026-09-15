package orbitsim;

/** ECI 위치 [km] · 속도 [km/s]. */
public record StateVector(Vector3 positionKm, Vector3 velocityKmS) {

    /** 비에너지 ε = v²/2 − μ/r [km²/s²]. 이체 문제에서 보존된다. */
    public double specificEnergy() {
        double v = velocityKmS.norm();
        double r = positionKm.norm();
        return v * v / 2.0 - Constants.MU_EARTH / r;
    }

    /** 비각운동량 h = r × v [km²/s]. 이체 문제에서 보존된다. */
    public Vector3 specificAngularMomentum() {
        return positionKm.cross(velocityKmS);
    }
}
