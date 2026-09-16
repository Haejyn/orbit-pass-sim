package orbitsim;

/** 지구 상수. 단위는 km · s · rad 로 통일한다. */
public final class Constants {
    /** 지구 중력 상수 μ [km^3/s^2] (WGS-84) */
    public static final double MU_EARTH = 398600.4418;
    /** 지구 적도 반지름 [km] (WGS-84). 좌표 변환은 구형 지구로 근사한다. */
    public static final double R_EARTH = 6378.137;
    /** 지구 자전 각속도 [rad/s] */
    public static final double OMEGA_EARTH = 7.2921159e-5;
    /**
     * 지구 편평률의 2차 대역 조화 계수 J2 (WGS-84, 무차원).
     * 적도가 부푼 정도를 나타내며, 저궤도에서 가장 큰 섭동원이다 — {@link J2Propagator}.
     */
    public static final double J2_EARTH = 1.08262668e-3;

    private Constants() {}
}
