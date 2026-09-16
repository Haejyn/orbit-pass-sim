namespace OrbitSim.Tests;

/// <summary>
/// Java 시험이 쓰는 골든을 그대로 읽어 C# 구현을 같은 기준에 건다.
/// `rk4_states.csv` 는 Python RK4 수치 적분(케플러 방정식을 쓰지 않는 독립 방법)이 만든 것이다.
/// </summary>
public class CrossGoldenTests
{
    [Fact]
    public void Analytic_propagation_matches_the_rk4_reference()
    {
        // REQ-PRP-04 와 같은 기준: 위치 < 0.01 km · 속도 < 1e-5 km/s
        int checkedRows = 0;
        foreach (string[] r in Golden.Rows("rk4_states.csv"))
        {
            var el = OrbitalElements.Create(
                Golden.Num(r[1]), Golden.Num(r[2]),
                double.DegreesToRadians(Golden.Num(r[3])),
                double.DegreesToRadians(Golden.Num(r[4])),
                double.DegreesToRadians(Golden.Num(r[5])), 0.0);
            StateVector s = TwoBodyPropagator.StateAt(el, Golden.Num(r[6]));
            var expectedR = new Vector3(Golden.Num(r[7]), Golden.Num(r[8]), Golden.Num(r[9]));
            var expectedV = new Vector3(Golden.Num(r[10]), Golden.Num(r[11]), Golden.Num(r[12]));

            Assert.True(s.PositionKm.Minus(expectedR).Norm() < 0.01,
                $"{r[0]} t={r[6]}: |dr|={s.PositionKm.Minus(expectedR).Norm():E3} km");
            Assert.True(s.VelocityKmS.Minus(expectedV).Norm() < 1e-5,
                $"{r[0]} t={r[6]}: |dv|={s.VelocityKmS.Minus(expectedV).Norm():E3} km/s");
            checkedRows++;
        }

        Assert.True(checkedRows >= 25, $"golden shrank unexpectedly: {checkedRows} rows");
    }

    [Fact]
    public void Epoch_state_matches_the_sgp4_golden()
    {
        // sgp4_states.csv 의 t=0 행: SGP4 상태에서 뽑은 요소로 전파하면 에포크에서 같은 자리여야 한다.
        int checkedRows = 0;
        foreach (string[] r in Golden.Rows("sgp4_states.csv").Where(r => Golden.Num(r[8]) == 0.0))
        {
            var el = OrbitalElements.Create(
                Golden.Num(r[1]), Golden.Num(r[2]),
                double.DegreesToRadians(Golden.Num(r[3])),
                double.DegreesToRadians(Golden.Num(r[4])),
                double.DegreesToRadians(Golden.Num(r[5])),
                double.DegreesToRadians(Golden.Num(r[6])));
            Vector3 position = TwoBodyPropagator.StateAt(el, 0.0).PositionKm;
            var expected = new Vector3(Golden.Num(r[9]), Golden.Num(r[10]), Golden.Num(r[11]));

            Assert.True(position.Minus(expected).Norm() < 1e-5,
                $"{r[0]}: |dr|={position.Minus(expected).Norm():E3} km");
            checkedRows++;
        }

        Assert.True(checkedRows >= 3, $"expected one epoch row per satellite, got {checkedRows}");
    }
}
