package orbitsim.cli;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import orbitsim.GroundStation;
import orbitsim.J2Propagator;
import orbitsim.OrbitalElements;
import orbitsim.PassPredictor;
import orbitsim.TwoBodyPropagator;

/**
 * {@code PassPredictor.predict} 의 창 길이별 비용을 잰다 — 시간 · <b>궤적 평가 횟수</b> · 할당.
 *
 * <p>이 클래스는 {@code src/cli/java} 에 둔다 — 제품 코드가 아니라 시험 장비이므로
 * 커버리지·뮤테이션·정적분석 대상({@code src/main/java})에서 빼기 위해서다 ({@link DiffCli} 와 같은 이유).
 *
 * <p><b>게이트는 시간이 아니라 "표본당 궤적 평가 횟수" 로 건다.</b> 결정적이라 러너 성능에도, JIT 가 할당을
 * 없애 주는 정도(JDK 버전마다 다르다)에도 흔들리지 않는다. 시간과 할당은 <b>보고만</b> 하고 문턱을 두지 않는다.
 * 궤적 호출은 {@link PassPredictor.Trajectory} 가 함수형 인터페이스라 감싸서 센다.
 *
 * <pre>
 *   java -cp build/classes:build/cli-classes orbitsim.cli.BenchCli [--check &lt;표본당 최대 평가 횟수&gt;]
 * </pre>
 */
public final class BenchCli {

    private static final double STEP_S = 10.0;
    private static final double MIN_ELEVATION_DEG = 10.0;
    private static final int[] WINDOW_DAYS = {1, 7, 30};
    private static final int MEASURED_RUNS = 9;
    private static final long WARMUP_NANOS = 400_000_000L;
    private static final double DAY_S = 86400.0;

    /** 계산 결과가 버려져 JIT 가 일을 통째로 지우는 것을 막는 수집기. */
    private static volatile long sink;

    private BenchCli() {}

    private record Row(String model, int days, long samples, long trajectoryCalls, int passes,
            double medianMs, double nsPerSample, double allocBytesPerSample) {
        double callsPerSample() {
            return (double) trajectoryCalls / samples;
        }
    }

    public static void main(String[] args) {
        double maxCallsPerSample = Double.NaN;
        if (args.length == 2 && args[0].equals("--check")) {
            maxCallsPerSample = Double.parseDouble(args[1]);
        } else if (args.length != 0) {
            System.err.println("usage: BenchCli [--check <max trajectory calls per sample>]");
            System.exit(2);
            return;
        }

        OrbitalElements el = OrbitalElements.circular(420.0, 51.6, 0.0);       // ISS 급 (Main 과 같다)
        GroundStation daejeon = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
        PassPredictor.Trajectory twoBody = t -> TwoBodyPropagator.stateAt(el, t).positionKm();
        PassPredictor.Trajectory j2 = J2Propagator.trajectory(el);

        List<Row> rows = new ArrayList<>();
        for (int days : WINDOW_DAYS) {
            rows.add(measure("two-body", twoBody, daejeon, days));
            rows.add(measure("j2", j2, daejeon, days));
        }

        System.out.printf("step %.0f s, min elevation %.0f deg, station %s, %d measured runs (median), JVM %s%n",
                STEP_S, MIN_ELEVATION_DEG, daejeon.name(), MEASURED_RUNS, System.getProperty("java.version"));
        System.out.printf("%-9s %5s %9s %11s %12s %7s %10s %12s %14s%n",
                "model", "days", "samples", "traj-calls", "calls/sample", "passes", "median-ms", "ns/sample", "alloc-B/sample");
        for (Row r : rows) {
            System.out.printf("%-9s %5d %9d %11d %12.4f %7d %10.2f %12.1f %14.1f%n",
                    r.model(), r.days(), r.samples(), r.trajectoryCalls(), r.callsPerSample(), r.passes(),
                    r.medianMs(), r.nsPerSample(), r.allocBytesPerSample());
        }

        if (!Double.isNaN(maxCallsPerSample)) {
            boolean failed = false;
            for (Row r : rows) {
                if (r.callsPerSample() > maxCallsPerSample) {
                    System.err.printf("FAIL %s %d days: %.4f trajectory calls per sample > %.4f%n",
                            r.model(), r.days(), r.callsPerSample(), maxCallsPerSample);
                    failed = true;
                }
                if (r.passes() == 0) {
                    System.err.printf("FAIL %s %d days: no passes found — the measured input is wrong%n", r.model(), r.days());
                    failed = true;
                }
            }
            System.exit(failed ? 1 : 0);
        }
    }

    private static Row measure(String model, PassPredictor.Trajectory trajectory, GroundStation station, int days) {
        double end = days * DAY_S;
        // 표본 수 — predict 의 루프와 같은 식: 시작 1 회 + t 를 step 씩 더해 end 까지.
        long samples = 1 + (long) Math.floor((end + 1e-9) / STEP_S);

        // 1) 결정적 부분 — 궤적을 감싸 호출 횟수를 센다. 시간·할당은 이 실행에서 재지 않는다 (감싸는 비용이 섞인다).
        long[] calls = new long[1];
        PassPredictor counting = PassPredictor.forTrajectory(t -> {
            calls[0]++;
            return trajectory.positionEciAt(t);
        }, station, 0.0, MIN_ELEVATION_DEG);
        int passes = counting.predict(0.0, end, STEP_S).size();

        // 2) 시간·할당 — 감싸지 않은 궤적으로.
        PassPredictor predictor = PassPredictor.forTrajectory(trajectory, station, 0.0, MIN_ELEVATION_DEG);
        long warmupUntil = System.nanoTime() + WARMUP_NANOS;
        int warmupRuns = 0;
        while (warmupRuns < 3 || System.nanoTime() < warmupUntil) {
            sink += predictor.predict(0.0, end, STEP_S).size();
            warmupRuns++;
        }

        long[] nanos = new long[MEASURED_RUNS];
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long start = System.nanoTime();
            sink += predictor.predict(0.0, end, STEP_S).size();
            nanos[i] = System.nanoTime() - start;
        }
        Arrays.sort(nanos);
        double medianNanos = nanos[MEASURED_RUNS / 2];

        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().threadId();
        long before = threads.getThreadAllocatedBytes(threadId);
        sink += predictor.predict(0.0, end, STEP_S).size();
        long allocated = threads.getThreadAllocatedBytes(threadId) - before;

        return new Row(model, days, samples, calls[0], passes,
                medianNanos / 1e6, medianNanos / samples, (double) allocated / samples);
    }
}
