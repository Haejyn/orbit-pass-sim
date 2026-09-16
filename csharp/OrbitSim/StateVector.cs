namespace OrbitSim;

/// <summary>ECI 위치 [km] · 속도 [km/s].</summary>
public readonly record struct StateVector(Vector3 PositionKm, Vector3 VelocityKmS)
{
    /// <summary>비에너지 ε = v²/2 − μ/r [km²/s²]. 이체 문제에서 보존된다.</summary>
    public double SpecificEnergy()
    {
        double v = VelocityKmS.Norm();
        double r = PositionKm.Norm();
        return (v * v / 2.0) - (Constants.MuEarth / r);
    }

    /// <summary>비각운동량 h = r × v [km²/s]. 이체 문제에서 보존된다.</summary>
    public Vector3 SpecificAngularMomentum() => PositionKm.Cross(VelocityKmS);
}
