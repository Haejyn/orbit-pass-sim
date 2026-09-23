package orbitsim.cli;

import java.util.List;
import java.util.Locale;
import orbitsim.Frames;
import orbitsim.GroundStation;
import orbitsim.OrbitalElements;
import orbitsim.PassPredictor;
import orbitsim.TwoBodyPropagator;

/**
 * README 의 하늘 궤적 그림 입력 — 데모({@code Main})와 같은 궤도·지상국으로 하루 패스를 예측하고,
 * 패스마다 AOS 부터 LOS 까지 10 초 간격의 방위각·고도각을 CSV 로 낸다.
 *
 * <p>시험 장비라 {@code src/cli/java} 에 둔다({@link BenchCli} 와 같은 이유).
 *
 * <pre>
 *   java -cp build/classes:build/cli-classes orbitsim.cli.SkyTrackCli &gt; sky.csv
 * </pre>
 */
public final class SkyTrackCli {

    private SkyTrackCli() {}

    public static void main(String[] args) {
        OrbitalElements elements = OrbitalElements.circular(420.0, 51.6, 0.0);
        GroundStation daejeon = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
        List<PassPredictor.Pass> passes = new PassPredictor(elements, daejeon, 0.0, 10.0).predict(0.0, 86400.0, 10.0);

        System.out.println("pass,t,azimuth_deg,elevation_deg,max_elevation_deg");
        for (int i = 0; i < passes.size(); i++) {
            PassPredictor.Pass pass = passes.get(i);
            for (double t = pass.aosSeconds(); t <= pass.losSeconds(); t += 10.0) {
                var ecef = Frames.eciToEcef(TwoBodyPropagator.stateAt(elements, t).positionKm(), Frames.earthRotationAngle(0.0, t));
                GroundStation.LookAngles look = daejeon.lookAngles(ecef);
                System.out.printf(Locale.ROOT, "%d,%.0f,%.3f,%.3f,%.1f%n", i + 1, t, look.azimuthDeg(), look.elevationDeg(), pass.maxElevationDeg());
            }
        }
    }
}
