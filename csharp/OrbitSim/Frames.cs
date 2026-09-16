namespace OrbitSim;

/// <summary>
/// 좌표계 변환 (REQ-FRM-01 ~ 03). 지구는 구형으로 근사하고, ECI→ECEF 는 지구 자전각만 반영한다.
/// </summary>
public static class Frames
{
    /// <summary>측지 좌표 (구형 지구): 위도·경도 [rad], 고도 [km].</summary>
    public readonly record struct Geodetic(double Latitude, double Longitude, double AltitudeKm);

    /// <summary>에포크 항성시 θ0 [rad] 에서 t 초 후의 지구 자전각.</summary>
    public static double EarthRotationAngle(double thetaAtEpoch, double secondsSinceEpoch) =>
        NormalizeAngle(thetaAtEpoch + (Constants.OmegaEarth * secondsSinceEpoch));

    /// <summary>ECI → ECEF: z 축으로 −θ 회전.</summary>
    public static Vector3 EciToEcef(Vector3 eci, double earthRotationAngle) =>
        eci.RotateZ(-earthRotationAngle);

    /// <summary>ECEF → ECI: z 축으로 +θ 회전.</summary>
    public static Vector3 EcefToEci(Vector3 ecef, double earthRotationAngle) =>
        ecef.RotateZ(earthRotationAngle);

    public static Geodetic EcefToGeodetic(Vector3 ecef)
    {
        double r = ecef.Norm();
        if (r == 0.0)
        {
            throw new ArgumentException(
                "position at Earth center has no geodetic coordinates", nameof(ecef));
        }

        double lat = Math.Asin(ecef.Z / r);
        double lon = Math.Atan2(ecef.Y, ecef.X);
        return new Geodetic(lat, lon, r - Constants.REarth);
    }

    public static Vector3 GeodeticToEcef(Geodetic g)
    {
        double r = Constants.REarth + g.AltitudeKm;
        double cl = Math.Cos(g.Latitude);
        return new Vector3(
            r * cl * Math.Cos(g.Longitude),
            r * cl * Math.Sin(g.Longitude),
            r * Math.Sin(g.Latitude));
    }

    /// <summary>각도를 [0, 2π) 로 정규화. 2π 는 포함하지 않는다 (REQ-FRM-03).</summary>
    public static double NormalizeAngle(double a)
    {
        double twoPi = 2.0 * Math.PI;
        double x = a % twoPi;
        if (x < 0)
        {
            x += twoPi;
        }

        // x 가 −1e-16 처럼 아주 작은 음수면 x + 2π 가 반올림돼 정확히 2π 가 된다.
        // 계약은 반열린 구간이므로 0 으로 접는다.
        return x >= twoPi ? 0.0 : x;
    }
}
