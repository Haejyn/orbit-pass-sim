package orbitsim;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Vector3Test {

    @Test
    @DisplayName("기본 연산")
    void arithmetic() {
        Vector3 a = new Vector3(1, 2, 3), b = new Vector3(-1, 0.5, 2);
        assertEquals(new Vector3(0, 2.5, 5), a.plus(b));
        assertEquals(new Vector3(2, 1.5, 1), a.minus(b));
        assertEquals(new Vector3(2, 4, 6), a.scale(2));
        assertEquals(6.0, a.dot(b), 1e-15);
        assertEquals(Math.sqrt(14), a.norm(), 1e-15);
    }

    @Test
    @DisplayName("외적은 양쪽 벡터에 수직이고 x×y=z")
    void cross() {
        Vector3 x = new Vector3(1, 0, 0), y = new Vector3(0, 1, 0);
        assertEquals(new Vector3(0, 0, 1), x.cross(y));
        Vector3 a = new Vector3(1.5, -2, 0.7), b = new Vector3(3, 1, -4);
        Vector3 c = a.cross(b);
        assertEquals(0.0, c.dot(a), 1e-12);
        assertEquals(0.0, c.dot(b), 1e-12);
    }

    @Test
    @DisplayName("정규화는 단위 길이, 영벡터는 예외")
    void normalize() {
        assertEquals(1.0, new Vector3(3, 4, 12).normalized().norm(), 1e-15);
        assertThrows(IllegalStateException.class, Vector3.ZERO::normalized);
    }

    @Test
    @DisplayName("z 축 회전은 길이를 보존하고 90° 회전이 x→y")
    void rotateZ() {
        Vector3 v = new Vector3(2, 3, 4);
        assertEquals(v.norm(), v.rotateZ(1.234).norm(), 1e-12);
        Vector3 r = new Vector3(1, 0, 0).rotateZ(Math.PI / 2);
        assertEquals(0.0, r.x(), 1e-15);
        assertEquals(1.0, r.y(), 1e-15);
        assertEquals(0.0, r.z(), 1e-15);
    }
}
