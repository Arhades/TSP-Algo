import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

// Run from TSP-Algo with Java 17 or later: java VerificationRoute.java
public class VerificationRoute {
    static final double DEFAULT_BUDGET_KM = 200;
    static final double LOW_IMPACT = 0.20; // Only scores above this qualify.
    static final double HIGH_IMPACT = 0.75;
    static final double POPULATION_WEIGHT = 0.70;
    static final LocalDate STUDY_DATE = LocalDate.of(2026, 11, 1);

    record Place(int id, String name, double lat, double lon, long population, LocalDate updated) {}
    record Candidate(Place place, double impact) {}
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

    static List<Candidate> score(List<Place> places) {
        long maxPopulation = 1;
        long maxAge = 1;
        for (Place p : places) {
            maxPopulation = Math.max(maxPopulation, p.population);
            maxAge = Math.max(maxAge, age(p));
        }
        List<Candidate> eligible = new ArrayList<>();
        for (Place p : places) {
            double population = Math.log1p(p.population) / Math.log1p(maxPopulation);
            double normalizedAge = (double) age(p) / maxAge;
            // Simulated verification need: population measures impact, age measures staleness.
            double impact = POPULATION_WEIGHT * population
                    + (1 - POPULATION_WEIGHT) * normalizedAge;
            if (impact > LOW_IMPACT) {
                eligible.add(new Candidate(p, impact));
            }
        }
        return eligible;
    }

    static PriorityQueue<Choice> newQueue() {
        // Highest priority first. Break ties using the smaller GeoNameID.
        return new PriorityQueue<>(Comparator.comparingDouble(Choice::priority).reversed()
                .thenComparingInt(choice -> choice.candidate.place.id));
    }

    static boolean fits(double travelled, double nextLeg, Place next, Place base, double budgetKm) {
        return travelled + nextLeg + distance(next, base) <= budgetKm;
    }

    // Both algorithms use this rule. A measures from base, B from its current stop.
    static double priority(Candidate candidate, double distanceKm) {
        return (candidate.impact) / (1 + distanceKm);
    }

    static List<Visit> staticPlan(Place base, List<Candidate> eligible, double budgetKm) {
        PriorityQueue<Choice> queue = newQueue();
        for (Candidate c : eligible) {
            double fromBase = distance(base, c.place);
            queue.add(new Choice(c, priority(c, fromBase), fromBase));
        }
        List<Visit> route = new ArrayList<>();
        double roundTrip = 0;
        while (!queue.isEmpty()) {
            Candidate c = queue.remove().candidate;
            Place previous = base;
            int bestIndex = 0;
            double leastExtra = Double.POSITIVE_INFINITY;
            double returnLeg = route.isEmpty() ? 0 : distance(route.get(route.size() - 1).candidate.place, base);

            // Try each gap, including the final leg back to base.
            for (int i = 0; i <= route.size(); i++) {
                boolean lastGap = i == route.size();
                Place next = lastGap ? base : route.get(i).candidate.place;
                double oldLeg = lastGap ? returnLeg : route.get(i).legKm;
                double extra = distance(previous, c.place) + distance(c.place, next) - oldLeg;
                if (extra < leastExtra) {
                    leastExtra = extra;
                    bestIndex = i;
                }
                previous = next;
            }
            if (roundTrip + leastExtra > budgetKm) continue;

            // Insertion changes the incoming leg of the next stop, too.
            Place before = bestIndex == 0 ? base : route.get(bestIndex - 1).candidate.place;
            if (bestIndex < route.size()) {
                Candidate next = route.get(bestIndex).candidate;
                route.set(bestIndex, new Visit(next, distance(c.place, next.place)));
            }
            route.add(bestIndex, new Visit(c, distance(before, c.place)));
            roundTrip += leastExtra;
        }
        return route;
    }

    static List<Visit> dynamicPlan(Place base, List<Candidate> eligible, double budgetKm) {
        List<Candidate> remaining = new ArrayList<>(eligible);
        List<Visit> route = new ArrayList<>();
        Place current = base;
        double travelled = 0;
        while (!remaining.isEmpty()) {
            Choice best = null;
            double highestPriority = -1;
            for (Candidate c : remaining) {
                double dist = distance(current, c.place);
                if (!fits(travelled, dist, c.place, base, budgetKm)) {
                    continue;
                }
                double score = priority(c, dist);

                if (score > highestPriority || 
                    (score == highestPriority && c.place.id < best.candidate.place.id)) {
                    highestPriority = priority(c, dist);
                    best = new Choice(c, priority(c, dist), dist);
                }
                
            }
            if (best == null) break;
            route.add(new Visit(best.candidate, best.legKm));
            travelled += best.legKm;
            current = best.candidate.place;
            remaining.remove(best.candidate);
        }
        return route;
    }

    static double printRoute(String title, Place base, List<Visit> route, long nanos) {
        System.out.println("\n" + title + "\nStart: " + base.name);
        double km = 0;
        double routeScore = 0;
        int high = 0;
        Place last = base;
        for (Visit visit : route) {
            Candidate c = visit.candidate;
            System.out.printf("  %s [%d]  +%.2f km  impact %.4f%n",
                    c.place.name, c.place.id, visit.legKm, c.impact);
            km += visit.legKm;
            routeScore += c.impact;
            if (c.impact >= HIGH_IMPACT) high++;
            last = c.place;
        }
        double back = distance(last, base);
        System.out.printf("Return: %s  +%.2f km%n", base.name, back);
        System.out.printf("Total %.2f km | %.4f total impact | %d verified | %d high impact | %.3f ms%n",
                km + back, routeScore, route.size(), high, nanos / 1_000_000.0);
        return routeScore;
    }

    public static void main(String[] args) throws Exception {
        int baseId = args.length == 0 ? 1735161 : Integer.parseInt(args[0]); // Kuala Lumpur
        double budgetKm;
        try {
            budgetKm = args.length < 2 ? DEFAULT_BUDGET_KM : Double.parseDouble(args[1]);
        } catch (NumberFormatException error) {
            System.out.println("Budget must be a number in kilometres.");
            return;
        }
        if (!Double.isFinite(budgetKm) || budgetKm < 0) {
            System.out.println("Budget must be finite and non-negative.");
            return;
        }
        // Optional third argument selects the preprocessed file.
        Path data = args.length < 3 ? Path.of("src", "MY.txt") : Path.of(args[2]);
        List<Place> places = new ArrayList<>();
        Place base = null;
        try (BufferedReader reader = Files.newBufferedReader(data)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] row = line.split("\t", -1);
                Place place = readPlace(row);
                if (place.id == baseId) base = place;
                places.add(place);
            }
        }
        if (base == null) {
            System.out.println("No record found for starting GeoNameID " + baseId);
            return;
        }
        List<Candidate> eligible = score(places);
        Place depot = base;
        // Even a direct trip to this point and back must fit the budget.
        eligible.removeIf(c -> c.place.id == baseId || 2 * distance(depot, c.place) > budgetKm);
        System.out.printf("Base: %s [%d] | Records checked: %d | Eligible within budget: %d | Budget: %s km%n",
                base.name, base.id, places.size(), eligible.size(), budgetKm);
        long start = System.nanoTime();
        List<Visit> routeA = staticPlan(base, eligible, budgetKm);
        double totalA = printRoute("A - Static priority with cheapest insertion", base, routeA, System.nanoTime() - start);
        start = System.nanoTime();
        List<Visit> routeB = dynamicPlan(base, eligible, budgetKm);
        double totalB = printRoute("B - Dynamic priority", base, routeB, System.nanoTime() - start);
        System.out.println("\nFinal route scores");
        System.out.printf("A (static):  %.4f%n", totalA);
        System.out.printf("B (dynamic): %.4f%n", totalB);
        if (Math.abs(totalA - totalB) < 1e-9) {
            System.out.println("Tie: both routes collected the same route score.");
        } else {
            String winner = totalA > totalB ? "A (static)" : "B (dynamic)";
            System.out.println("Winner by route score: " + winner);
        }
    }
}
