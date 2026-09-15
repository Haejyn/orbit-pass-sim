package orbitsim;

/**
 * 케플러 방정식 M = E - e·sin(E) 를 뉴턴-랩슨으로 푼다.
 * 타원 궤도(0 ≤ e < 1)만 다룬다.
 */
public final class KeplerSolver {
    public static final double TOLERANCE = 1e-12;
    public static final int MAX_ITERATIONS = 50;

    private KeplerSolver() {}

    /**
     * @param meanAnomaly 평균 근점 이각 M [rad], 임의 범위 허용
     * @param eccentricity 이심률 e, 0 ≤ e < 1
     * @return 이심 근점 이각 E [rad], M 과 같은 회전수 범위
     * @throws IllegalArgumentException e 가 범위 밖이거나 입력이 NaN
     * @throws ArithmeticException MAX_ITERATIONS 안에 수렴하지 않음
     */
    public static double solveEccentricAnomaly(double meanAnomaly, double eccentricity) {
        if (Double.isNaN(meanAnomaly) || Double.isNaN(eccentricity)) {
            throw new IllegalArgumentException("NaN input");
        }
        if (eccentricity < 0.0 || eccentricity >= 1.0) {
            throw new IllegalArgumentException("eccentricity must be in [0,1): " + eccentricity);
        }
        final double e = eccentricity;
        // [D-4] |M| 이 크면 인접한 double 사이 간격(ulp)이 TOLERANCE 보다 커져 뉴턴 보정이 두 값 사이를
        // 영원히 오가며 수렴 판정을 못 한다 (|M|≈1e6 부터 무작위 입력의 약 16% 실패, 1년 전파에서도 재현).
        // 해의 2π 주기성(E(M+2πk) = E(M)+2πk)을 이용해 [-π, π] 에서 풀고 회전수를 되돌린다.
        final double m = Math.IEEEremainder(meanAnomaly, 2.0 * Math.PI);
        final double revolutions = meanAnomaly - m;
        // 초기값: e 가 크면 M 근처에서 시작하면 발산할 수 있어 sin(M) 방향으로 e 만큼 민다.
        double eAnom = e < 0.8 ? m : m + Math.copySign(e, Math.sin(m));
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double f = eAnom - e * Math.sin(eAnom) - m;
            double fp = 1.0 - e * Math.cos(eAnom);
            double delta = f / fp;
            eAnom -= delta;
            if (Math.abs(delta) < TOLERANCE) {
                return eAnom + revolutions;
            }
        }
        throw new ArithmeticException("Kepler solver did not converge: M=" + meanAnomaly + " e=" + e);
    }

    /** 이심 근점 이각 → 진근점 이각 ν [rad]. */
    public static double trueAnomaly(double eccentricAnomaly, double eccentricity) {
        double halfE = eccentricAnomaly / 2.0;
        double k = Math.sqrt((1 + eccentricity) / (1 - eccentricity));
        return 2.0 * Math.atan2(k * Math.sin(halfE), Math.cos(halfE));
    }

    /** 진근점 이각 → 이심 근점 이각 (역변환, 대조 시험용). */
    public static double eccentricFromTrue(double trueAnomaly, double eccentricity) {
        double k = Math.sqrt((1 - eccentricity) / (1 + eccentricity));
        return 2.0 * Math.atan2(k * Math.sin(trueAnomaly / 2.0), Math.cos(trueAnomaly / 2.0));
    }
}
