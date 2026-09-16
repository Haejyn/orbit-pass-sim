package orbitsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 시간 창을 일정 간격으로 훑어 지상국에서 위성이 보이는 구간(패스)을 찾는다.
 * AOS/LOS 는 스텝 사이를 이분법으로 좁혀 초 단위로 정한다.
 */
public final class PassPredictor {

    /** 한 번의 가시 구간. 시간은 에포크 기준 초. */
    public record Pass(double aosSeconds, double losSeconds, double maxElevationDeg, double timeOfMaxSeconds) {
        public double durationSeconds() { return losSeconds - aosSeconds; }
    }

    /**
     * 에포크 기준 t 초의 ECI 위치를 주는 궤적. 전파 방법이 달라도 **같은 탐색·이분법 코드**로
     * 패스를 구할 수 있어야, 두 전파기의 패스 차이가 전파 차이만으로 설명된다.
     */
    @FunctionalInterface
    public interface Trajectory {
        Vector3 positionEciAt(double secondsSinceEpoch);
    }

    private static final double BISECTION_TOLERANCE_S = 0.5;

    private final Trajectory trajectory;
    private final GroundStation station;
    private final double thetaAtEpoch;
    private final double minElevationRad;

    /**
     * @param minElevationDeg 이 고도각 이상일 때만 "보인다" 로 친다 (보통 0~10°)
     */
    public PassPredictor(OrbitalElements elements, GroundStation station, double thetaAtEpoch, double minElevationDeg) {
        this(twoBodyTrajectory(elements), station, thetaAtEpoch, minElevationDeg);
    }

    /**
     * 궤적을 직접 주입한다 — 외부 기준(예: SGP4 골든 궤적)을 같은 탐색 코드로 돌려 패스를 대조할 때 쓴다.
     * 생성자 오버로드로 두면 {@code new PassPredictor(null, …)} 가 모호해지므로 정적 팩토리로 둔다.
     */
    public static PassPredictor forTrajectory(Trajectory trajectory, GroundStation station,
            double thetaAtEpoch, double minElevationDeg) {
        return new PassPredictor(trajectory, station, thetaAtEpoch, minElevationDeg);
    }

    private PassPredictor(Trajectory trajectory, GroundStation station, double thetaAtEpoch, double minElevationDeg) {
        if (trajectory == null || station == null) {
            throw new IllegalArgumentException("elements and station required");
        }
        if (!(minElevationDeg >= -90.0 && minElevationDeg < 90.0)) {
            throw new IllegalArgumentException("minElevationDeg out of range: " + minElevationDeg);
        }
        this.trajectory = trajectory;
        this.station = station;
        this.thetaAtEpoch = thetaAtEpoch;
        this.minElevationRad = Math.toRadians(minElevationDeg);
    }

    private static Trajectory twoBodyTrajectory(OrbitalElements elements) {
        if (elements == null) {
            throw new IllegalArgumentException("elements and station required");
        }
        return t -> TwoBodyPropagator.stateAt(elements, t).positionKm();
    }

    /** t 초 시점의 고도각 [rad]. */
    public double elevationAt(double t) {
        Vector3 eci = trajectory.positionEciAt(t);
        Vector3 ecef = Frames.eciToEcef(eci, Frames.earthRotationAngle(thetaAtEpoch, t));
        return station.lookAngles(ecef).elevation();
    }

    /**
     * @param startSeconds 창 시작 (에포크 기준)
     * @param endSeconds 창 끝, start 보다 커야 한다
     * @param stepSeconds 훑는 간격, 0 < step ≤ 창 길이
     */
    public List<Pass> predict(double startSeconds, double endSeconds, double stepSeconds) {
        // `!(a > b)` 꼴은 NaN 도 걸러낸다 — `a <= b` 로 바꾸면 NaN 이 통과한다
        if (!(endSeconds > startSeconds)) {
            throw new IllegalArgumentException("end must be after start");
        }
        if (!(stepSeconds > 0.0) || stepSeconds > endSeconds - startSeconds) {
            throw new IllegalArgumentException("step must be in (0, window]: " + stepSeconds);
        }
        List<Pass> passes = new ArrayList<>();
        boolean visible = isVisible(startSeconds);
        double aos = visible ? startSeconds : Double.NaN;
        double maxElev = visible ? elevationAt(startSeconds) : Double.NEGATIVE_INFINITY;
        double tMax = startSeconds;
        double prev = startSeconds;
        for (double t = startSeconds + stepSeconds; t <= endSeconds + 1e-9; t += stepSeconds) {
            boolean nowVisible = isVisible(t);
            if (nowVisible) {
                double el = elevationAt(t);
                if (el > maxElev) { maxElev = el; tMax = t; }
            }
            if (!visible && nowVisible) {
                aos = bisect(prev, t);
                maxElev = elevationAt(t); tMax = t;
            } else if (visible && !nowVisible) {
                double los = bisect(t, prev);
                passes.add(new Pass(aos, los, Math.toDegrees(maxElev), tMax));
                maxElev = Double.NEGATIVE_INFINITY;
            }
            visible = nowVisible;
            prev = t;
        }
        if (visible) {
            passes.add(new Pass(aos, endSeconds, Math.toDegrees(maxElev), tMax));
        }
        return Collections.unmodifiableList(passes);
    }

    private boolean isVisible(double t) { return elevationAt(t) >= minElevationRad; }

    /** invisible 쪽 tA 와 visible 쪽 tB 사이에서 경계 시각을 찾는다. */
    private double bisect(double tInvisible, double tVisible) {
        double a = tInvisible;
        double b = tVisible;
        while (Math.abs(b - a) > BISECTION_TOLERANCE_S) {
            double mid = (a + b) / 2.0;
            if (isVisible(mid)) {
                b = mid;
            } else {
                a = mid;
            }
        }
        return b;
    }
}
