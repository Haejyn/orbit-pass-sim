namespace OrbitSim;

/// <summary>
/// 이체 문제 해석해 전파기 (REQ-PRP-01 ~ 06). 섭동(J2·대기항력)은 다루지 않는다.
/// 궤도 요소 → 근점 좌표계(PQW) 위치·속도 → 3회 회전으로 ECI.
/// </summary>
public static class TwoBodyPropagator
{
    /// <param name="el">궤도 요소</param>
    /// <param name="secondsSinceEpoch">에포크 이후 경과 시간 [s], 음수 허용</param>
    /// <returns>ECI 상태 벡터</returns>
    public static StateVector StateAt(OrbitalElements el, double secondsSinceEpoch)
    {
        if (!double.IsFinite(secondsSinceEpoch))
        {
            throw new ArgumentException(
                $"time must be finite: {secondsSinceEpoch}", nameof(secondsSinceEpoch));
        }

        double e = el.Eccentricity;
        double a = el.SemiMajorAxisKm;
        double meanAnomaly = el.MeanAnomalyAtEpoch + (el.MeanMotion() * secondsSinceEpoch);
        double eAnom = KeplerSolver.SolveEccentricAnomaly(meanAnomaly, e);
        double nu = KeplerSolver.TrueAnomaly(eAnom, e);

        double p = a * (1.0 - (e * e));
        double r = p / (1.0 + (e * Math.Cos(nu)));
        var posPqw = new Vector3(r * Math.Cos(nu), r * Math.Sin(nu), 0.0);
        double k = Math.Sqrt(Constants.MuEarth / p);
        var velPqw = new Vector3(-k * Math.Sin(nu), k * (e + Math.Cos(nu)), 0.0);

        return new StateVector(PqwToEci(posPqw, el), PqwToEci(velPqw, el));
    }

    /// <summary>PQW → ECI: R_z(Ω)·R_x(i)·R_z(ω).</summary>
    public static Vector3 PqwToEci(Vector3 v, OrbitalElements el)
    {
        Vector3 afterArgp = v.RotateZ(el.ArgPerigee);
        double ci = Math.Cos(el.Inclination);
        double si = Math.Sin(el.Inclination);
        var afterInc = new Vector3(
            afterArgp.X,
            (ci * afterArgp.Y) - (si * afterArgp.Z),
            (si * afterArgp.Y) + (ci * afterArgp.Z));
        return afterInc.RotateZ(el.Raan);
    }
}
