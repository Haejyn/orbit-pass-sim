namespace OrbitSim;

/// <summary>지상국 (REQ-GST-01 ~ 03). 위도·경도는 deg 로 받고 내부에서 rad 로 쓴다.</summary>
public sealed record GroundStation
{
    public GroundStation(string name, double latitudeDeg, double longitudeDeg, double altitudeKm)
    {
        if (string.IsNullOrWhiteSpace(name))
        {
            throw new ArgumentException("name required", nameof(name));
        }

        if (!(latitudeDeg >= -90.0 && latitudeDeg <= 90.0))
        {
            throw new ArgumentException($"latitude out of range: {latitudeDeg}", nameof(latitudeDeg));
        }

        if (!(longitudeDeg >= -180.0 && longitudeDeg <= 180.0))
        {
            throw new ArgumentException($"longitude out of range: {longitudeDeg}", nameof(longitudeDeg));
        }

        if (!double.IsFinite(altitudeKm) || altitudeKm < -1.0)
        {
            throw new ArgumentException($"altitude invalid: {altitudeKm}", nameof(altitudeKm));
        }

        Name = name;
        LatitudeDeg = latitudeDeg;
        LongitudeDeg = longitudeDeg;
        AltitudeKm = altitudeKm;
    }

    public string Name { get; }

    public double LatitudeDeg { get; }

    public double LongitudeDeg { get; }

    public double AltitudeKm { get; }

    /// <summary>관측 결과: 고도각·방위각 [rad], 거리 [km].</summary>
    public readonly record struct LookAngles(double Elevation, double Azimuth, double RangeKm)
    {
        public double ElevationDeg => double.RadiansToDegrees(Elevation);

        public double AzimuthDeg => double.RadiansToDegrees(Azimuth);
    }

    public Vector3 PositionEcef() =>
        Frames.GeodeticToEcef(new Frames.Geodetic(
            double.DegreesToRadians(LatitudeDeg), double.DegreesToRadians(LongitudeDeg), AltitudeKm));

    /// <summary>
    /// 위성 ECEF 위치를 지상국 기준 ENU 로 옮겨 고도각·방위각을 구한다.
    /// 방위각은 북쪽 0, 동쪽 π/2, [0, 2π).
    /// </summary>
    public LookAngles ComputeLookAngles(Vector3 satEcef)
    {
        Vector3 rho = satEcef.Minus(PositionEcef());
        double lat = double.DegreesToRadians(LatitudeDeg);
        double lon = double.DegreesToRadians(LongitudeDeg);
        double sl = Math.Sin(lat);
        double cl = Math.Cos(lat);
        double so = Math.Sin(lon);
        double co = Math.Cos(lon);
        double east = (-so * rho.X) + (co * rho.Y);
        double north = (-sl * co * rho.X) - (sl * so * rho.Y) + (cl * rho.Z);
        double up = (cl * co * rho.X) + (cl * so * rho.Y) + (sl * rho.Z);
        double range = rho.Norm();
        if (range == 0.0)
        {
            throw new ArgumentException("satellite coincides with station", nameof(satEcef));
        }

        double elevation = Math.Asin(up / range);
        double azimuth = Frames.NormalizeAngle(Math.Atan2(east, north));
        return new LookAngles(elevation, azimuth, range);
    }
}
