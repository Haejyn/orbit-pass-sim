package orbitsim;

/**
 * 이체 해석해에 **J2 세속 항**만 더한 전파기. 지구가 완전한 구가 아니라 적도가 부푼 것(편평률)이
 * 궤도면을 서서히 돌리는데, 그 효과의 1차 평균값만 담는다.
 *
 * <p>세 요소가 시간에 비례해 흐른다 (단주기 진동은 담지 않는다):
 * <pre>
 *   Ω̇ = −(3/2)·J2·(R⊕/p)²·n·cos i                     승교점 적경 — 궤도면이 회전
 *   ω̇ =  (3/2)·J2·(R⊕/p)²·n·(2 − 2.5·sin²i)           근지점 인수 — 장축이 회전
 *   Ṁ =  n + (3/2)·J2·(R⊕/p)²·n·√(1−e²)·(1 − 1.5·sin²i)  평균 근점 이각
 * </pre>
 * p = a(1−e²).
 *
 * <p><b>이체 경로는 건드리지 않는다.</b> 흐른 각도로 요소를 다시 만들어
 * {@link TwoBodyPropagator} 에 넘기므로, 이미 검증된 PQW→ECI 변환과 케플러 풀이를 그대로 쓴다.
 * J2 = 0 이면 결과가 이체 전파와 정확히 같다.
 *
 * <p><b>이것도 근사다.</b> 고차 중력항·대기 저항·달·태양 섭동은 없다. SGP4 와 남는 차이가
 * 무엇 때문인지는 이 코드가 답하지 않는다 — 실측 차이만 docs/test-report.md §7 에 적는다.
 */
public final class J2Propagator {

    private J2Propagator() {}

    /**
     * @param el 에포크 궤도 요소
     * @param secondsSinceEpoch 에포크 이후 경과 시간 [s], 음수 허용
     * @return ECI 상태 벡터
     */
    public static StateVector stateAt(OrbitalElements el, double secondsSinceEpoch) {
        if (!Double.isFinite(secondsSinceEpoch)) {
            throw new IllegalArgumentException("time must be finite: " + secondsSinceEpoch);
        }
        return TwoBodyPropagator.stateAt(driftedElements(el, secondsSinceEpoch), 0.0);
    }

    /** t 초 뒤의 요소 — Ω·ω·M 만 흐르고 a·e·i 는 그대로다 (세속 항의 성질). */
    static OrbitalElements driftedElements(OrbitalElements el, double secondsSinceEpoch) {
        double e = el.eccentricity();
        double i = el.inclination();
        double n = el.meanMotion();
        double p = el.semiMajorAxisKm() * (1.0 - e * e);
        double ratio = Constants.R_EARTH / p;
        double factor = 1.5 * Constants.J2_EARTH * ratio * ratio * n;
        double sinI = Math.sin(i);
        double sin2 = sinI * sinI;

        double raanRate = -factor * Math.cos(i);
        double argpRate = factor * (2.0 - 2.5 * sin2);
        double meanRate = n + factor * Math.sqrt(1.0 - e * e) * (1.0 - 1.5 * sin2);

        return new OrbitalElements(
                el.semiMajorAxisKm(), e, i,
                el.raan() + raanRate * secondsSinceEpoch,
                el.argPerigee() + argpRate * secondsSinceEpoch,
                el.meanAnomalyAtEpoch() + meanRate * secondsSinceEpoch);
    }

    /** 승교점 적경의 세속 변화율 [rad/s]. 태양동기 궤도 설계가 이 값을 1 년에 한 바퀴로 맞춘다. */
    public static double raanRateRadPerSecond(OrbitalElements el) {
        double p = el.semiMajorAxisKm() * (1.0 - el.eccentricity() * el.eccentricity());
        double ratio = Constants.R_EARTH / p;
        return -1.5 * Constants.J2_EARTH * ratio * ratio * el.meanMotion() * Math.cos(el.inclination());
    }

    /** 이 전파기를 패스 예측에 넣을 궤적으로. 탐색·이분법 코드는 이체와 똑같은 것을 쓴다. */
    public static PassPredictor.Trajectory trajectory(OrbitalElements el) {
        if (el == null) {
            throw new IllegalArgumentException("elements required");
        }
        return t -> stateAt(el, t).positionKm();
    }
}
