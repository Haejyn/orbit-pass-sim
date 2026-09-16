package orbitsim.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import orbitsim.Frames;
import orbitsim.GroundStation;
import orbitsim.KeplerSolver;
import orbitsim.OrbitalElements;
import orbitsim.PassPredictor;
import orbitsim.StateVector;
import orbitsim.TwoBodyPropagator;
import orbitsim.Vector3;

/**
 * 차분 시험용 CLI — 입력 CSV 의 사례를 계산해 출력 CSV 로 쓴다.
 * C# {@code OrbitSim.Cli} 와 같은 입출력 계약이어야 한다 ({@code tools/differential.py} 가 둘을 대조한다).
 *
 * <p>이 클래스는 {@code src/cli/java} 에 둔다 — 제품 코드가 아니라 시험 장비이므로
 * 커버리지·뮤테이션·정적분석 대상({@code src/main/java})에서 빼기 위해서다.
 */
public final class DiffCli {

    /** 사례 종류마다 쓰는 출력 칸 수가 다르고, 남는 칸은 비운다. */
    public static final int MAX_OUTPUTS = 6;

    private DiffCli() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: DiffCli <input.csv> <output.csv>");
            System.exit(2);
            return;
        }
        List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder("case,status,o1,o2,o3,o4,o5,o6\n");
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                continue;
            }
            String[] f = lines.get(i).split(",", -1);
            String[] row = evaluate(f);
            sb.append(f[0]).append(',').append(row[0]);
            for (int k = 0; k < MAX_OUTPUTS; k++) {
                sb.append(',').append(row[k + 1]);
            }
            sb.append('\n');
        }
        // 개행은 LF 로 고정한다 — OS 가 달라도 같은 파일이 나와야 한다.
        Files.writeString(Path.of(args[1]), sb.toString(), StandardCharsets.UTF_8);
    }

    /** @return {status, o1..o6} — 예외는 토큰으로 정규화해 두 구현의 **판정**을 견준다. */
    static String[] evaluate(String[] f) {
        String[] out = new String[MAX_OUTPUTS + 1];
        java.util.Arrays.fill(out, "");
        try {
            double[] values = compute(f);
            out[0] = "ok";
            for (int k = 0; k < values.length; k++) {
                out[k + 1] = Double.toString(values[k]);
            }
        } catch (IllegalArgumentException e) {
            out[0] = "reject";
        } catch (ArithmeticException e) {
            out[0] = "diverge";
        } catch (IllegalStateException e) {
            out[0] = "state";
        }
        return out;
    }

    private static double[] compute(String[] f) {
        String kind = f[1];
        return switch (kind) {
            case "normalize" -> new double[] {Frames.normalizeAngle(num(f, 17))};
            case "kepler" -> kepler(f);
            case "elements" -> elements(f);
            case "state" -> state(f);
            case "look" -> look(f);
            case "pass" -> pass(f);
            default -> throw new IllegalArgumentException("unknown kind: " + kind);
        };
    }

    private static double[] kepler(String[] f) {
        double e = num(f, 3);
        double eAnom = KeplerSolver.solveEccentricAnomaly(num(f, 7), e);
        double nu = KeplerSolver.trueAnomaly(eAnom, e);
        return new double[] {eAnom, nu, KeplerSolver.eccentricFromTrue(nu, e)};
    }

    private static double[] elements(String[] f) {
        OrbitalElements el = elems(f);
        return new double[] {
            el.periodSeconds(), el.meanMotion(), el.perigeeRadiusKm(), el.apogeeRadiusKm(),
        };
    }

    private static double[] state(String[] f) {
        StateVector s = TwoBodyPropagator.stateAt(elems(f), num(f, 8));
        return new double[] {
            s.positionKm().x(), s.positionKm().y(), s.positionKm().z(),
            s.velocityKmS().x(), s.velocityKmS().y(), s.velocityKmS().z(),
        };
    }

    private static double[] look(String[] f) {
        double t = num(f, 8);
        Vector3 eci = TwoBodyPropagator.stateAt(elems(f), t).positionKm();
        Vector3 ecef = Frames.eciToEcef(eci, Frames.earthRotationAngle(num(f, 12), t));
        GroundStation.LookAngles look = station(f).lookAngles(ecef);
        return new double[] {look.elevationDeg(), look.azimuthDeg(), look.rangeKm()};
    }

    private static double[] pass(String[] f) {
        PassPredictor predictor = new PassPredictor(elems(f), station(f), num(f, 12), num(f, 13));
        List<PassPredictor.Pass> passes = new ArrayList<>(predictor.predict(num(f, 14), num(f, 15), num(f, 16)));
        if (passes.isEmpty()) {
            return new double[] {0, Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        }
        PassPredictor.Pass p = passes.get(0);
        return new double[] {
            passes.size(), p.aosSeconds(), p.losSeconds(), p.maxElevationDeg(), p.timeOfMaxSeconds(),
        };
    }

    private static OrbitalElements elems(String[] f) {
        return new OrbitalElements(num(f, 2), num(f, 3), num(f, 4), num(f, 5), num(f, 6), num(f, 7));
    }

    private static GroundStation station(String[] f) {
        return new GroundStation("station", num(f, 9), num(f, 10), num(f, 11));
    }

    /** 빈 칸은 그 사례가 쓰지 않는 값이라 NaN 으로 둔다. */
    private static double num(String[] f, int index) {
        String s = f[index];
        return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }
}
