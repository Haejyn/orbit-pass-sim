namespace OrbitSim.Tests;

/// <summary>
/// C# 구현을 <b>명세로</b> 직접 검증한다. 골든 대조만 있으면 Java 가 틀렸을 때 둘이 사이좋게 틀린다.
/// 기준은 `docs/requirements.md` 의 판정 기준이다.
/// </summary>
public class SpecTests
{
    [Theory]
    [InlineData(0.0, 0.0)]
    [InlineData(1.0, 0.0)]
    [InlineData(1.0, 0.5)]
    [InlineData(-2.5, 0.9)]
    [InlineData(3.0, 0.999)]
    public void Kepler_residual_is_tiny(double m, double e)
    {
        // REQ-KEP-01: |E − e·sinE − M| < 1e-11
        double eAnom = KeplerSolver.SolveEccentricAnomaly(m, e);
        double residual = eAnom - (e * Math.Sin(eAnom)) - m;
        Assert.True(Math.Abs(residual) < 1e-11, $"residual={residual:E3}");
    }

    [Theory]
    [InlineData(1e6, 0.3)]
    [InlineData(1e12, 0.7)]
    [InlineData(-1e12, 0.3)]
    public void Kepler_handles_huge_mean_anomaly(double m, double e)
    {
        // |M| 이 크면 잔차를 그 자리에서 잴 수 없다 — E 와 회전수가 같은 크기라 빼는 순간 유효숫자가
        // 날아간다 (ulp(1e12) ≈ 1.2e-4). 그래서 방정식은 **줄인 영역**에서 확인하고, 큰 M 에서는
        // "예외 없이 유한한 답 + 회전수 보존" 을 본다 (D-4 가 났던 자리, REQ-PRP-05).
        double eAnom = KeplerSolver.SolveEccentricAnomaly(m, e);
        Assert.True(double.IsFinite(eAnom));

        double mReduced = Math.IEEERemainder(m, 2.0 * Math.PI);
        double eReduced = KeplerSolver.SolveEccentricAnomaly(mReduced, e);
        double residual = eReduced - (e * Math.Sin(eReduced)) - mReduced;
        Assert.True(Math.Abs(residual) < 1e-11, $"reduced residual={residual:E3}");

        double expected = eReduced + (m - mReduced);
        Assert.True(Math.Abs(eAnom - expected) <= 8 * Math.Abs(m) * 1.1e-16,
            $"E={eAnom:R} vs E_reduced+2πk={expected:R}");
    }

    [Theory]
    [InlineData(0.3, 0.0)]
    [InlineData(2.0, 0.4)]
    [InlineData(-1.0, 0.9)]
    public void True_anomaly_round_trips(double eAnom, double e)
    {
        // REQ-KEP-03: E ↔ ν 왕복 차이 < 1e-12 rad
        double nu = KeplerSolver.TrueAnomaly(eAnom, e);
        Assert.True(Math.Abs(KeplerSolver.EccentricFromTrue(nu, e) - eAnom) < 1e-12);
    }

    [Theory]
    [InlineData(1.0)]
    [InlineData(-1e-18)]
    [InlineData(double.NaN)]
    public void Kepler_rejects_out_of_range_eccentricity(double e) =>
        Assert.Throws<ArgumentException>(() => KeplerSolver.SolveEccentricAnomaly(0.5, e));

    [Fact]
    public void Geostationary_period_is_a_sidereal_day()
    {
        // REQ-ELM-01: 정지궤도 반경의 T = 항성일 ±1 s
        double sidereal = 2.0 * Math.PI / Constants.OmegaEarth;
        var el = OrbitalElements.Create(42164.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        Assert.True(Math.Abs(el.PeriodSeconds() - sidereal) < 1.0);
    }

    [Theory]
    [InlineData(6378.137, 0.0, 0.0)]          // 근지점이 지표에 닿는다
    [InlineData(7000.0, 1.0, 0.0)]            // e = 1
    [InlineData(7000.0, 0.0, -1e-16)]         // i < 0
    [InlineData(double.NaN, 0.0, 0.0)]
    public void Elements_reject_impossible_values(double a, double e, double i) =>
        Assert.Throws<ArgumentException>(() => OrbitalElements.Create(a, e, i, 0.0, 0.0, 0.0));

    [Fact]
    public void Elements_accept_boundary_values()
    {
        // REQ-ELM-02: e=0 · i=0 · i=π 는 허용
        OrbitalElements.Create(7000.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        OrbitalElements.Create(7000.0, 0.0, Math.PI, 0.0, 0.0, 0.0);
    }

    [Fact]
    public void Two_body_conserves_energy_and_returns_after_one_period()
    {
        // REQ-PRP-02 · 03. 보존량은 **조건이 좋은 궤도**에서 잰다 — 근지점이 낮은 고이심률 궤도는
        // ε = v²/2 − μ/r 에서 큰 두 항이 상쇄돼 1e-10 기준을 뜻 없이 깨뜨린다.
        // (Java 쪽도 같은 이유로 시험 궤도를 장반경 80,000 km 로 올렸다 — 시험 결함 D-2.)
        // 에포크를 근지점(M0 = 0)에 두어야 근지점·원지점 반경 단언이 성립한다.
        var el = OrbitalElements.Create(80000.0, 0.72, 1.1, 0.5, 0.25, 0.0);
        StateVector s0 = TwoBodyPropagator.StateAt(el, 0.0);
        StateVector half = TwoBodyPropagator.StateAt(el, el.PeriodSeconds() / 2.0);
        StateVector full = TwoBodyPropagator.StateAt(el, el.PeriodSeconds());

        Assert.True(Math.Abs((half.SpecificEnergy() - s0.SpecificEnergy()) / s0.SpecificEnergy()) < 1e-10);
        Assert.True(full.PositionKm.Minus(s0.PositionKm).Norm() < 1e-6);
        Assert.True(full.VelocityKmS.Minus(s0.VelocityKmS).Norm() < 1e-9);
        Assert.True(Math.Abs(s0.PositionKm.Norm() - el.PerigeeRadiusKm()) < 1e-6);
        Assert.True(Math.Abs(half.PositionKm.Norm() - el.ApogeeRadiusKm()) < 1e-6);
    }

    [Theory]
    [InlineData(double.NaN)]
    [InlineData(double.PositiveInfinity)]
    public void Two_body_rejects_non_finite_time(double t)
    {
        var el = OrbitalElements.Create(7000.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        Assert.Throws<ArgumentException>(() => TwoBodyPropagator.StateAt(el, t));
    }

    [Theory]
    [InlineData(0.0)]
    [InlineData(-1e-16)]
    [InlineData(-1e-9)]
    [InlineData(1e15)]
    [InlineData(-1e15)]
    public void Normalize_angle_stays_in_half_open_range(double a)
    {
        // REQ-FRM-03: 0 ≤ θ < 2π — 2π 포함 금지 (D-5 가 났던 계약)
        double x = Frames.NormalizeAngle(a);
        Assert.InRange(x, 0.0, Math.BitDecrement(2.0 * Math.PI));
    }

    [Fact]
    public void Normalize_angle_accepts_negative_zero()
    {
        // −0.0 은 0.0 과 값이 같아 InlineData 로는 따로 못 쓴다(중복으로 거부된다).
        // 계약(0 ≤ θ < 2π)은 −0.0 도 만족한다 — 부호 비트가 그대로 남는지는 차분 시험이 Java 와 대조한다.
        double x = Frames.NormalizeAngle(-0.0);
        Assert.Equal(0.0, x);
        Assert.InRange(x, 0.0, Math.BitDecrement(2.0 * Math.PI));
    }

    [Fact]
    public void Frames_round_trip()
    {
        // REQ-FRM-01
        var g = new Frames.Geodetic(double.DegreesToRadians(36.35), double.DegreesToRadians(127.38), 0.07);
        Frames.Geodetic back = Frames.EcefToGeodetic(Frames.GeodeticToEcef(g));
        Assert.True(Math.Abs(back.Latitude - g.Latitude) < 1e-12);
        Assert.True(Math.Abs(back.Longitude - g.Longitude) < 1e-12);
        Assert.True(Math.Abs(back.AltitudeKm - g.AltitudeKm) < 1e-9);
    }

    [Fact]
    public void Equatorial_station_sees_cardinal_azimuths()
    {
        // REQ-GST-01: 적도 지상국에서 정북 0° · 정동 90° · 정서 270°
        var station = new GroundStation("eq", 0.0, 0.0, 0.0);
        double r = Constants.REarth + 500.0;
        Assert.Equal(0.0, station.ComputeLookAngles(new Vector3(Constants.REarth, 0, 500)).AzimuthDeg, 9);
        Assert.Equal(90.0, station.ComputeLookAngles(new Vector3(Constants.REarth, 500, 0)).AzimuthDeg, 9);
        Assert.Equal(270.0, station.ComputeLookAngles(new Vector3(Constants.REarth, -500, 0)).AzimuthDeg, 9);
        Assert.Equal(90.0, station.ComputeLookAngles(new Vector3(r, 0, 0)).ElevationDeg, 9);
    }

    [Theory]
    [InlineData(90.1, 0.0, 0.0)]
    [InlineData(0.0, 180.1, 0.0)]
    [InlineData(0.0, 0.0, -1.1)]
    [InlineData(0.0, 0.0, double.NaN)]
    public void Ground_station_rejects_invalid_coordinates(double lat, double lon, double alt) =>
        Assert.Throws<ArgumentException>(() => new GroundStation("s", lat, lon, alt));

    [Fact]
    public void Iss_like_orbit_has_a_realistic_number_of_passes()
    {
        // REQ-PAS-01: 하루 3~8 패스, 각 ≤ 12 분
        var el = OrbitalElements.Circular(420.0, 51.6, 0.0);
        var station = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
        var predictor = new PassPredictor(el, station, 0.0, 10.0);
        IReadOnlyList<PassPredictor.Pass> passes = predictor.Predict(0.0, 86400.0, 10.0);

        Assert.InRange(passes.Count, 3, 8);
        Assert.All(passes, p => Assert.InRange(p.DurationSeconds, 0.0, 12 * 60.0));
    }

    [Theory]
    [InlineData(0.0, 0.0, 10.0)]
    [InlineData(0.0, 100.0, 0.0)]
    [InlineData(0.0, 100.0, 200.0)]
    [InlineData(0.0, 100.0, double.NaN)]
    public void Predict_rejects_invalid_windows(double start, double end, double step)
    {
        var el = OrbitalElements.Circular(420.0, 51.6, 0.0);
        var predictor = new PassPredictor(el, new GroundStation("s", 0, 0, 0), 0.0, 10.0);
        Assert.Throws<ArgumentException>(() => predictor.Predict(start, end, step));
    }

    [Fact]
    public void Zero_vector_cannot_be_normalized() =>
        Assert.Throws<InvalidOperationException>(() => Vector3.Zero.Normalized());
}
