package orbitsim;

import java.util.List;

/** 데모: ISS 비슷한 궤도가 대전 지상국에서 하루 동안 언제 보이는가. */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        double altitudeKm = args.length > 0 ? Double.parseDouble(args[0]) : 420.0;
        double inclinationDeg = args.length > 1 ? Double.parseDouble(args[1]) : 51.6;
        double minElevDeg = args.length > 2 ? Double.parseDouble(args[2]) : 10.0;

        OrbitalElements el = OrbitalElements.circular(altitudeKm, inclinationDeg, 0.0);
        GroundStation daejeon = new GroundStation("Daejeon", 36.35, 127.38, 0.07);
        PassPredictor predictor = new PassPredictor(el, daejeon, 0.0, minElevDeg);

        System.out.printf("orbit: a=%.1f km  T=%.1f min  station=%s  minElev=%.0f deg%n",
                el.semiMajorAxisKm(), el.periodSeconds() / 60.0, daejeon.name(), minElevDeg);
        List<PassPredictor.Pass> passes = predictor.predict(0.0, 86400.0, 10.0);
        System.out.printf("passes in 24h: %d%n", passes.size());
        for (PassPredictor.Pass p : passes) {
            System.out.printf("  AOS %7.0fs  LOS %7.0fs  dur %5.0fs  maxEl %5.1f deg%n",
                    p.aosSeconds(), p.losSeconds(), p.durationSeconds(), p.maxElevationDeg());
        }
    }
}
