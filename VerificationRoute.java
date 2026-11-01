import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

// Run from Project with Java 17 or later: java VerificationRoute.java
public class VerificationRoute {
    static final double BUDGET_KM = 500;
    static final double MAX_CONFIDENCE = 0.75; // Only scores below this qualify.
    static final double HIGH_UNCERTAINTY = 0.80;
    static final double POPULATION_WEIGHT = 0.70;
    static final int SAMPLE_SIZE = 1000;
    static final LocalDate STUDY_DATE = LocalDate.of(2026, 11, 1);

    record Place(int id, String name, double lat, double lon, long population, LocalDate updated) {}
    record Candidate(Place place, double uncertainty, double value) {}
    record Choice(Candidate candidate, double priority, double legKm) {}
    record Visit(Candidate candidate, double legKm) {}

    static double distance(Place a, Place b) {
        // Haversine distance on a spherical Earth, in kilometres.
        double latDifference = Math.toRadians(b.lat - a.lat);
        double lonDifference = Math.toRadians(b.lon - a.lon);
        double h = Math.pow(Math.sin(latDifference / 2), 2)
                + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat))
                * Math.pow(Math.sin(lonDifference / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    static Place readPlace(String[] row) {
        return new Place(Integer.parseInt(row[0]), row[1], Double.parseDouble(row[4]),
                Double.parseDouble(row[5]), row[14].isEmpty() ? 0 : Long.parseLong(row[14]),
                LocalDate.parse(row[18]));
    }

    static long age(Place p) {
        return Math.max(0, ChronoUnit.DAYS.between(p.updated, STUDY_DATE));
    }

    static List<Candidate> score(List<Place> sample) {
        long maxPopulation = 1;
        long maxAge = 1;
        for (Place p : sample) {
            maxPopulation = Math.max(maxPopulation, p.population);
            maxAge = Math.max(maxAge, age(p));
        }
        List<Candidate> eligible = new ArrayList<>();
        for (Place p : sample) {
            double population = Math.log1p(p.population) / Math.log1p(maxPopulation);
            double uncertainty = (double) age(p) / maxAge;
            double confidence = 1 - uncertainty;
            if (confidence < MAX_CONFIDENCE) {
                double value = 100 * (POPULATION_WEIGHT * population
                        + (1 - POPULATION_WEIGHT) * uncertainty);
                eligible.add(new Candidate(p, uncertainty, value));
            }
        }
        return eligible;
    }

    static PriorityQueue<Choice> newQueue() {
        // Highest priority first. Break ties using the smaller GeoNameID.
        return new PriorityQueue<>(Comparator.comparingDouble(Choice::priority).reversed()
                .thenComparingInt(choice -> choice.candidate.place.id));
    }

    static boolean fits(double travelled, double nextLeg, Place next, Place base) {
        return travelled + nextLeg + distance(next, base) <= BUDGET_KM;
    }

    static List<Visit> staticPlan(Place base, List<Candidate> eligible) {
        PriorityQueue<Choice> queue = newQueue();
        for (Candidate c : eligible) {
            queue.add(new Choice(c, c.value, 0));
        }
        List<Visit> route = new ArrayList<>();
        Place current = base;
        double travelled = 0;
        while (!queue.isEmpty()) {
            Candidate c = queue.remove().candidate;
            double leg = distance(current, c.place);
            if (fits(travelled, leg, c.place, base)) {
                route.add(new Visit(c, leg));
                travelled += leg;
                current = c.place;
            }
        }
        return route;
    }

    static List<Visit> dynamicPlan(Place base, List<Candidate> eligible) {
        List<Candidate> remaining = new ArrayList<>(eligible);
        List<Visit> route = new ArrayList<>();
        Place current = base;
        double travelled = 0;
        while (!remaining.isEmpty()) {
            // Moving changes every distance-based priority, so rebuild the queue.
            PriorityQueue<Choice> queue = newQueue();
            for (Candidate c : remaining) {
                double leg = distance(current, c.place);
                if (fits(travelled, leg, c.place, base)) {
                    queue.add(new Choice(c, c.value / (1 + leg), leg));
                }
            }
            if (queue.isEmpty()) break;
            Choice best = queue.remove();
            route.add(new Visit(best.candidate, best.legKm));
            travelled += best.legKm;
            current = best.candidate.place;
            remaining.remove(best.candidate);
        }
        return route;
    }

    static void printRoute(String title, Place base, List<Visit> route, long nanos) {
        System.out.println("\n" + title + "\nStart: " + base.name);
        double km = 0;
        double value = 0;
        int high = 0;
        Place last = base;
        for (Visit visit : route) {
            Candidate c = visit.candidate;
            System.out.printf("  %s [%d]  +%.2f km  +%.2f points%n",
                    c.place.name, c.place.id, visit.legKm, c.value);
            km += visit.legKm;
            value += c.value;
            if (c.uncertainty >= HIGH_UNCERTAINTY) high++;
            last = c.place;
        }
        double back = distance(last, base);
        System.out.printf("Return: %s  +%.2f km%n", base.name, back);
        System.out.printf("Total %.2f km | %.2f points | %d verified | %d high uncertainty | %.3f ms%n",
                km + back, value, route.size(), high, nanos / 1_000_000.0);
    }

    public static void main(String[] args) throws Exception {
        int baseId = args.length == 0 ? 1735161 : Integer.parseInt(args[0]); // Kuala Lumpur
        Path data = Path.of("src", "MY.txt");
        List<Place> places = new ArrayList<>();
        Place base = null;
        try (BufferedReader reader = Files.newBufferedReader(data)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.split("\t", -1);
                if (Integer.parseInt(row[0]) == baseId) base = readPlace(row);
                if (row[6].equals("P")) places.add(readPlace(row));
            }
        }
        if (base == null) {
            System.out.println("No record found for starting GeoNameID " + baseId);
            return;
        }
        // Fixed seed keeps the experiment reproducible when comparing bases.
        Collections.shuffle(places, new Random(201));
        List<Place> sample = places.subList(0, Math.min(SAMPLE_SIZE, places.size()));
        List<Candidate> eligible = score(sample);
        eligible.removeIf(c -> c.place.id == baseId); // The depot earns no points.
        System.out.printf("Base: %s [%d] | Sample: %d | Eligible: %d | Budget: %.0f km%n",
                base.name, base.id, sample.size(), eligible.size(), BUDGET_KM);
        long start = System.nanoTime();
        List<Visit> routeA = staticPlan(base, eligible);
        printRoute("A - Static priority", base, routeA, System.nanoTime() - start);
        start = System.nanoTime();
        List<Visit> routeB = dynamicPlan(base, eligible);
        printRoute("B - Dynamic priority", base, routeB, System.nanoTime() - start);
    }
}
