using System.Globalization;

namespace OrbitSim;

/// <summary>차분 시험의 한 사례. 열 이름은 `tools/differential.py` 가 쓰는 CSV 와 같다.</summary>
public readonly record struct DiffCase(
    string Id, string Kind,
    double SemiMajorAxisKm, double Eccentricity, double Inclination, double Raan, double ArgPerigee,
    double MeanAnomalyAtEpoch, double TimeSeconds,
    double LatitudeDeg, double LongitudeDeg, double AltitudeKm, double ThetaAtEpoch,
    double MinElevationDeg, double StartSeconds, double EndSeconds, double StepSeconds, double AngleRad);

/// <summary>한 사례의 결과. 실패는 사유를 토큰으로 정규화해 두 구현이 **같은 이유로** 거부하는지 본다.</summary>
public readonly record struct DiffResult(string Status, IReadOnlyList<double> Values);

/// <summary>
/// Java `orbitsim.cli.DiffCli` 와 **같은 계약**으로 사례를 계산한다.
/// 두 구현이 같은 입력에 같은 답(또는 같은 거부 사유)을 내는지 대조하는 것이 목적이다 (REQ-DIF-01 · 02).
/// </summary>
public static class DifferentialCases
{
    /// <summary>출력 칸 수. 사례 종류마다 쓰는 개수가 다르고 남는 칸은 비운다.</summary>
    public const int MaxOutputs = 6;

    public static readonly IReadOnlyList<string> InputColumns =
    [
        "case", "kind", "a_km", "e", "i_rad", "raan_rad", "argp_rad", "m0_rad", "t_s",
        "lat_deg", "lon_deg", "alt_km", "theta0_rad", "min_elev_deg", "start_s", "end_s", "step_s", "angle_rad",
    ];

    /// <summary>예외를 토큰으로 정규화한다 — 언어마다 예외 형은 달라도 **판정은 같아야** 한다.</summary>
    public static DiffResult Evaluate(DiffCase c)
    {
        try
        {
            return new DiffResult("ok", Compute(c));
        }
        catch (ArgumentException)
        {
            return new DiffResult("reject", []);
        }
        catch (ArithmeticException)
        {
            return new DiffResult("diverge", []);
        }
        catch (InvalidOperationException)
        {
            return new DiffResult("state", []);
        }
    }

    /// <summary>CSV 한 줄(헤더 제외)을 사례로 읽는다. 빈 칸은 쓰지 않는 값이라 NaN 으로 둔다.</summary>
    public static DiffCase ParseCase(IReadOnlyList<string> f)
    {
        ArgumentNullException.ThrowIfNull(f);
        return new DiffCase(
            f[0], f[1],
            Num(f[2]), Num(f[3]), Num(f[4]), Num(f[5]), Num(f[6]), Num(f[7]), Num(f[8]),
            Num(f[9]), Num(f[10]), Num(f[11]), Num(f[12]), Num(f[13]), Num(f[14]), Num(f[15]), Num(f[16]), Num(f[17]));
    }

    /// <summary>왕복 가능한 표기로 쓴다 — NaN · Infinity · -Infinity 는 Java 와 같은 철자다.</summary>
    public static string Format(double v) => v.ToString("R", CultureInfo.InvariantCulture);

    private static double Num(string s) =>
        string.IsNullOrEmpty(s) ? double.NaN : double.Parse(s, CultureInfo.InvariantCulture);

    private static double[] Compute(DiffCase c) => c.Kind switch
    {
        "normalize" => [Frames.NormalizeAngle(c.AngleRad)],
        "kepler" => Kepler(c),
        "elements" => Elements(c),
        "state" => State(c),
        "look" => Look(c),
        "pass" => Pass(c),
        _ => throw new ArgumentException($"unknown kind: {c.Kind}", nameof(c)),
    };

    private static double[] Kepler(DiffCase c)
    {
        double e = c.Eccentricity;
        double eAnom = KeplerSolver.SolveEccentricAnomaly(c.MeanAnomalyAtEpoch, e);
        double nu = KeplerSolver.TrueAnomaly(eAnom, e);
        return [eAnom, nu, KeplerSolver.EccentricFromTrue(nu, e)];
    }

    private static double[] Elements(DiffCase c)
    {
        OrbitalElements el = Elems(c);
        return [el.PeriodSeconds(), el.MeanMotion(), el.PerigeeRadiusKm(), el.ApogeeRadiusKm()];
    }

    private static double[] State(DiffCase c)
    {
        StateVector s = TwoBodyPropagator.StateAt(Elems(c), c.TimeSeconds);
        return
        [
            s.PositionKm.X, s.PositionKm.Y, s.PositionKm.Z,
            s.VelocityKmS.X, s.VelocityKmS.Y, s.VelocityKmS.Z,
        ];
    }

    private static double[] Look(DiffCase c)
    {
        Vector3 eci = TwoBodyPropagator.StateAt(Elems(c), c.TimeSeconds).PositionKm;
        Vector3 ecef = Frames.EciToEcef(eci, Frames.EarthRotationAngle(c.ThetaAtEpoch, c.TimeSeconds));
        GroundStation.LookAngles look = Station(c).ComputeLookAngles(ecef);
        return [look.ElevationDeg, look.AzimuthDeg, look.RangeKm];
    }

    private static double[] Pass(DiffCase c)
    {
        var predictor = new PassPredictor(Elems(c), Station(c), c.ThetaAtEpoch, c.MinElevationDeg);
        IReadOnlyList<PassPredictor.Pass> passes =
            predictor.Predict(c.StartSeconds, c.EndSeconds, c.StepSeconds);
        if (passes.Count == 0)
        {
            return [0, double.NaN, double.NaN, double.NaN, double.NaN];
        }

        PassPredictor.Pass p = passes[0];
        return [passes.Count, p.AosSeconds, p.LosSeconds, p.MaxElevationDeg, p.TimeOfMaxSeconds];
    }

    private static OrbitalElements Elems(DiffCase c) => OrbitalElements.Create(
        c.SemiMajorAxisKm, c.Eccentricity, c.Inclination, c.Raan, c.ArgPerigee, c.MeanAnomalyAtEpoch);

    private static GroundStation Station(DiffCase c) =>
        new("station", c.LatitudeDeg, c.LongitudeDeg, c.AltitudeKm);
}
