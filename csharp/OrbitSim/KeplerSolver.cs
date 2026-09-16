namespace OrbitSim;

/// <summary>
/// 케플러 방정식 M = E − e·sin(E) 를 뉴턴-랩슨으로 푼다 (REQ-KEP-01 ~ 04).
/// 타원 궤도(0 ≤ e &lt; 1)만 다룬다.
/// </summary>
public static class KeplerSolver
{
    public const double Tolerance = 1e-12;
    public const int MaxIterations = 50;

    /// <param name="meanAnomaly">평균 근점 이각 M [rad], 임의 범위 허용</param>
    /// <param name="eccentricity">이심률 e, 0 ≤ e &lt; 1</param>
    /// <returns>이심 근점 이각 E [rad], M 과 같은 회전수 범위</returns>
    /// <exception cref="ArgumentException">e 가 범위 밖이거나 입력이 NaN</exception>
    /// <exception cref="ArithmeticException">MaxIterations 안에 수렴하지 않음</exception>
    public static double SolveEccentricAnomaly(double meanAnomaly, double eccentricity)
    {
        if (double.IsNaN(meanAnomaly) || double.IsNaN(eccentricity))
        {
            throw new ArgumentException("NaN input", nameof(meanAnomaly));
        }

        if (eccentricity < 0.0 || eccentricity >= 1.0)
        {
            throw new ArgumentException(
                $"eccentricity must be in [0,1): {eccentricity}", nameof(eccentricity));
        }

        double e = eccentricity;

        // |M| 이 크면 인접한 double 간격(ulp)이 Tolerance 보다 커져 뉴턴 보정이 두 값 사이를 오간다.
        // 해의 2π 주기성 E(M+2πk) = E(M)+2πk 를 이용해 [−π, π] 에서 풀고 회전수를 되돌린다.
        double m = Math.IEEERemainder(meanAnomaly, 2.0 * Math.PI);
        double revolutions = meanAnomaly - m;

        // 초기값: e 가 크면 M 근처에서 시작하면 발산할 수 있어 sin(M) 방향으로 e 만큼 민다.
        double eAnom = e < 0.8 ? m : m + Math.CopySign(e, Math.Sin(m));
        for (int i = 0; i < MaxIterations; i++)
        {
            double f = eAnom - (e * Math.Sin(eAnom)) - m;
            double fp = 1.0 - (e * Math.Cos(eAnom));
            double delta = f / fp;
            eAnom -= delta;
            if (Math.Abs(delta) < Tolerance)
            {
                return eAnom + revolutions;
            }
        }

        throw new ArithmeticException(
            $"Kepler solver did not converge: M={meanAnomaly} e={e}");
    }

    /// <summary>이심 근점 이각 → 진근점 이각 ν [rad].</summary>
    public static double TrueAnomaly(double eccentricAnomaly, double eccentricity)
    {
        double halfE = eccentricAnomaly / 2.0;
        double k = Math.Sqrt((1 + eccentricity) / (1 - eccentricity));
        return 2.0 * Math.Atan2(k * Math.Sin(halfE), Math.Cos(halfE));
    }

    /// <summary>진근점 이각 → 이심 근점 이각 (역변환, 대조 시험용).</summary>
    public static double EccentricFromTrue(double trueAnomaly, double eccentricity)
    {
        double k = Math.Sqrt((1 - eccentricity) / (1 + eccentricity));
        return 2.0 * Math.Atan2(k * Math.Sin(trueAnomaly / 2.0), Math.Cos(trueAnomaly / 2.0));
    }
}
