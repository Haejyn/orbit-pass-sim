namespace OrbitSim;

/// <summary>불변 3차원 벡터.</summary>
public readonly record struct Vector3(double X, double Y, double Z)
{
    public static Vector3 Zero => new(0, 0, 0);

    public Vector3 Plus(Vector3 o) => new(X + o.X, Y + o.Y, Z + o.Z);

    public Vector3 Minus(Vector3 o) => new(X - o.X, Y - o.Y, Z - o.Z);

    public Vector3 Scale(double k) => new(X * k, Y * k, Z * k);

    public double Dot(Vector3 o) => (X * o.X) + (Y * o.Y) + (Z * o.Z);

    public Vector3 Cross(Vector3 o) =>
        new((Y * o.Z) - (Z * o.Y), (Z * o.X) - (X * o.Z), (X * o.Y) - (Y * o.X));

    public double Norm() => Math.Sqrt(Dot(this));

    /// <summary>영벡터를 정규화하면 <see cref="InvalidOperationException"/>.</summary>
    public Vector3 Normalized()
    {
        double n = Norm();
        if (n == 0.0)
        {
            throw new InvalidOperationException("cannot normalize zero vector");
        }

        return Scale(1.0 / n);
    }

    /// <summary>z 축 기준 회전 (오른손 법칙, 각도 rad).</summary>
    public Vector3 RotateZ(double angle)
    {
        double c = Math.Cos(angle);
        double s = Math.Sin(angle);
        return new Vector3((c * X) - (s * Y), (s * X) + (c * Y), Z);
    }
}
