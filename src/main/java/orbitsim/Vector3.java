package orbitsim;

/** 불변 3차원 벡터. */
public record Vector3(double x, double y, double z) {

    public static final Vector3 ZERO = new Vector3(0, 0, 0);

    public Vector3 plus(Vector3 o) { return new Vector3(x + o.x, y + o.y, z + o.z); }

    public Vector3 minus(Vector3 o) { return new Vector3(x - o.x, y - o.y, z - o.z); }

    public Vector3 scale(double k) { return new Vector3(x * k, y * k, z * k); }

    public double dot(Vector3 o) { return x * o.x + y * o.y + z * o.z; }

    public Vector3 cross(Vector3 o) {
        return new Vector3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double norm() { return Math.sqrt(dot(this)); }

    /** 영벡터를 정규화하면 IllegalStateException. */
    public Vector3 normalized() {
        double n = norm();
        if (n == 0.0) {
            throw new IllegalStateException("cannot normalize zero vector");
        }
        return scale(1.0 / n);
    }

    /** z 축 기준 회전 (오른손 법칙, 각도 rad). */
    public Vector3 rotateZ(double angle) {
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        return new Vector3(c * x - s * y, s * x + c * y, z);
    }
}
