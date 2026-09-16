namespace OrbitSim;

/// <summary>
/// 고전 궤도 요소 (케플러 요소). 각도는 rad, 길이는 km (REQ-ELM-01 · 02).
/// </summary>
/// <param name="SemiMajorAxisKm">장반경 a — 근지점이 지표 위여야 한다</param>
/// <param name="Eccentricity">이심률 e, 0 ≤ e &lt; 1</param>
/// <param name="Inclination">궤도 경사각 i, [0, π]</param>
/// <param name="Raan">승교점 적경 Ω</param>
/// <param name="ArgPerigee">근지점 인수 ω</param>
/// <param name="MeanAnomalyAtEpoch">에포크 평균 근점 이각 M0</param>
public readonly record struct OrbitalElements(
    double SemiMajorAxisKm,
    double Eccentricity,
    double Inclination,
    double Raan,
    double ArgPerigee,
    double MeanAnomalyAtEpoch)
{
    public OrbitalElements Validated()
    {
        RequireFinite(SemiMajorAxisKm, "a");
        RequireFinite(Eccentricity, "e");
        RequireFinite(Inclination, "i");
        RequireFinite(Raan, "raan");
        RequireFinite(ArgPerigee, "argPerigee");
        RequireFinite(MeanAnomalyAtEpoch, "M0");
        if (Eccentricity < 0.0 || Eccentricity >= 1.0)
        {
            throw new ArgumentException($"eccentricity must be in [0,1): {Eccentricity}", nameof(Eccentricity));
        }

        if (Inclination < 0.0 || Inclination > Math.PI)
        {
            throw new ArgumentException($"inclination must be in [0,pi]: {Inclination}", nameof(Inclination));
        }

        double perigee = SemiMajorAxisKm * (1.0 - Eccentricity);
        if (perigee <= Constants.REarth)
        {
            throw new ArgumentException(
                $"perigee radius {perigee} km must exceed Earth radius {Constants.REarth}",
                nameof(SemiMajorAxisKm));
        }

        return this;
    }

    /// <summary>검증까지 마친 요소를 만든다 — Java 의 record 압축 생성자와 같은 자리.</summary>
    public static OrbitalElements Create(double a, double e, double i, double raan, double argp, double m0) =>
        new OrbitalElements(a, e, i, raan, argp, m0).Validated();

    /// <summary>원궤도 편의 생성자 — 고도(km)·경사각(deg)·RAAN(deg) 만 준다.</summary>
    public static OrbitalElements Circular(double altitudeKm, double inclinationDeg, double raanDeg) =>
        Create(Constants.REarth + altitudeKm, 0.0,
            double.DegreesToRadians(inclinationDeg), double.DegreesToRadians(raanDeg), 0.0, 0.0);

    /// <summary>궤도 주기 T = 2π·sqrt(a³/μ) [s].</summary>
    public double PeriodSeconds()
    {
        double a = SemiMajorAxisKm;
        return 2.0 * Math.PI * Math.Sqrt(a * a * a / Constants.MuEarth);
    }

    /// <summary>평균 운동 n = sqrt(μ/a³) [rad/s].</summary>
    public double MeanMotion()
    {
        double a = SemiMajorAxisKm;
        return Math.Sqrt(Constants.MuEarth / (a * a * a));
    }

    public double PerigeeRadiusKm() => SemiMajorAxisKm * (1.0 - Eccentricity);

    public double ApogeeRadiusKm() => SemiMajorAxisKm * (1.0 + Eccentricity);

    private static void RequireFinite(double v, string name)
    {
        if (!double.IsFinite(v))
        {
            throw new ArgumentException($"{name} must be finite: {v}", name);
        }
    }
}
