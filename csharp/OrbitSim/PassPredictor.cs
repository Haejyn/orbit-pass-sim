namespace OrbitSim;

/// <summary>
/// 시간 창을 일정 간격으로 훑어 지상국에서 위성이 보이는 구간(패스)을 찾는다 (REQ-PAS-01 ~ 06).
/// AOS/LOS 는 스텝 사이를 이분법으로 좁혀 초 단위로 정한다.
/// </summary>
public sealed class PassPredictor
{
    private const double BisectionToleranceS = 0.5;

    private readonly Func<double, Vector3> _trajectory;
    private readonly GroundStation _station;
    private readonly double _thetaAtEpoch;
    private readonly double _minElevationRad;

    /// <param name="minElevationDeg">이 고도각 이상일 때만 "보인다" 로 친다 (보통 0~10°)</param>
    public PassPredictor(OrbitalElements elements, GroundStation station, double thetaAtEpoch, double minElevationDeg)
        : this(TwoBodyTrajectory(elements), station, thetaAtEpoch, minElevationDeg)
    {
    }

    private PassPredictor(Func<double, Vector3>? trajectory, GroundStation? station,
        double thetaAtEpoch, double minElevationDeg)
    {
        if (trajectory is null || station is null)
        {
            throw new ArgumentException("elements and station required", nameof(trajectory));
        }

        if (!(minElevationDeg >= -90.0 && minElevationDeg < 90.0))
        {
            throw new ArgumentException(
                $"minElevationDeg out of range: {minElevationDeg}", nameof(minElevationDeg));
        }

        _trajectory = trajectory;
        _station = station;
        _thetaAtEpoch = thetaAtEpoch;
        _minElevationRad = double.DegreesToRadians(minElevationDeg);
    }

    /// <summary>한 번의 가시 구간. 시간은 에포크 기준 초.</summary>
    public readonly record struct Pass(
        double AosSeconds, double LosSeconds, double MaxElevationDeg, double TimeOfMaxSeconds)
    {
        public double DurationSeconds => LosSeconds - AosSeconds;
    }

    /// <summary>궤적을 직접 주입한다 — 외부 기준 궤적을 같은 탐색 코드로 돌려 패스를 대조할 때 쓴다.</summary>
    public static PassPredictor ForTrajectory(Func<double, Vector3> trajectory, GroundStation station,
        double thetaAtEpoch, double minElevationDeg) =>
        new(trajectory, station, thetaAtEpoch, minElevationDeg);

    /// <summary>t 초 시점의 고도각 [rad].</summary>
    public double ElevationAt(double t)
    {
        Vector3 eci = _trajectory(t);
        Vector3 ecef = Frames.EciToEcef(eci, Frames.EarthRotationAngle(_thetaAtEpoch, t));
        return _station.ComputeLookAngles(ecef).Elevation;
    }

    /// <param name="startSeconds">창 시작 (에포크 기준)</param>
    /// <param name="endSeconds">창 끝, start 보다 커야 한다</param>
    /// <param name="stepSeconds">훑는 간격, 0 &lt; step ≤ 창 길이</param>
    public IReadOnlyList<Pass> Predict(double startSeconds, double endSeconds, double stepSeconds)
    {
        // `!(a > b)` 꼴은 NaN 도 걸러낸다 — `a <= b` 로 바꾸면 NaN 이 통과한다
        if (!(endSeconds > startSeconds))
        {
            throw new ArgumentException("end must be after start", nameof(endSeconds));
        }

        if (!(stepSeconds > 0.0) || stepSeconds > endSeconds - startSeconds)
        {
            throw new ArgumentException(
                $"step must be in (0, window]: {stepSeconds}", nameof(stepSeconds));
        }

        var passes = new List<Pass>();
        bool visible = IsVisible(startSeconds);
        double aos = visible ? startSeconds : double.NaN;
        double maxElev = visible ? ElevationAt(startSeconds) : double.NegativeInfinity;
        double tMax = startSeconds;
        double prev = startSeconds;
        for (double t = startSeconds + stepSeconds; t <= endSeconds + 1e-9; t += stepSeconds)
        {
            bool nowVisible = IsVisible(t);
            if (nowVisible)
            {
                double el = ElevationAt(t);
                if (el > maxElev)
                {
                    maxElev = el;
                    tMax = t;
                }
            }

            if (!visible && nowVisible)
            {
                aos = Bisect(prev, t);
                maxElev = ElevationAt(t);
                tMax = t;
            }
            else if (visible && !nowVisible)
            {
                double los = Bisect(t, prev);
                passes.Add(new Pass(aos, los, double.RadiansToDegrees(maxElev), tMax));
                maxElev = double.NegativeInfinity;
            }

            visible = nowVisible;
            prev = t;
        }

        if (visible)
        {
            passes.Add(new Pass(aos, endSeconds, double.RadiansToDegrees(maxElev), tMax));
        }

        return passes.AsReadOnly();
    }

    private static Func<double, Vector3> TwoBodyTrajectory(OrbitalElements elements)
    {
        OrbitalElements validated = elements.Validated();
        return t => TwoBodyPropagator.StateAt(validated, t).PositionKm;
    }

    private bool IsVisible(double t) => ElevationAt(t) >= _minElevationRad;

    /// <summary>invisible 쪽 tA 와 visible 쪽 tB 사이에서 경계 시각을 찾는다.</summary>
    private double Bisect(double tInvisible, double tVisible)
    {
        double a = tInvisible;
        double b = tVisible;
        while (Math.Abs(b - a) > BisectionToleranceS)
        {
            double mid = (a + b) / 2.0;
            if (IsVisible(mid))
            {
                b = mid;
            }
            else
            {
                a = mid;
            }
        }

        return b;
    }
}
