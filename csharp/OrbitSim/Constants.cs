namespace OrbitSim;

/// <summary>지구 상수. 단위는 km · s · rad 로 통일한다 (Java `orbitsim.Constants` 와 같은 값이어야 한다).</summary>
public static class Constants
{
    /// <summary>지구 중력 상수 μ [km^3/s^2] (WGS-84)</summary>
    public const double MuEarth = 398600.4418;

    /// <summary>지구 적도 반지름 [km] (WGS-84). 좌표 변환은 구형 지구로 근사한다.</summary>
    public const double REarth = 6378.137;

    /// <summary>지구 자전 각속도 [rad/s]</summary>
    public const double OmegaEarth = 7.2921159e-5;
}
