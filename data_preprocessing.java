import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

// Run from TSP-Algo: java data_preprocessing.java
public class data_preprocessing {
    public static void main(String[] args) throws Exception {
        Path input = Path.of("src", "MY.txt");
        Path output = Path.of("src", "MY_cleaned.txt");

        // Keep one record for each exact coordinate pair, in input order.
        Map<String, String> records = new LinkedHashMap<>();
        int rows = 0;
        int duplicates = 0;
        int invalid = 0;
        int otherFeatures = 0;

        try (BufferedReader reader = Files.newBufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                rows++;
                String[] fields = line.split("\t", -1);
                if (!valid(fields)) {
                    invalid++;
                    continue;
                }
                // P means populated places, including cities, towns and villages.
                // Filter before deduplicating so only P records compete for a coordinate.
                if (!fields[6].equals("P")) {
                    otherFeatures++;
                    continue;
                }
                int id = Integer.parseInt(fields[0]);
                String coordinates = coordinateKey(fields);
                if (records.containsKey(coordinates)) {
                    duplicates++;
                    String[] oldFields = records.get(coordinates).split("\t", -1);
                    LocalDate oldDate = LocalDate.parse(oldFields[18]);
                    LocalDate newDate = LocalDate.parse(fields[18]);
                    int oldId = Integer.parseInt(oldFields[0]);
                    // Prefer the latest record, then the smaller ID on equal dates.
                    if (newDate.isAfter(oldDate) || (newDate.equals(oldDate) && id < oldId)) {
                        records.put(coordinates, line);
                    }
                } else {
                    records.put(coordinates, line);
                }
            }
        }

        // Write a separate file using the same 19-column format.
        Files.write(output, records.values());
        System.out.println("Rows read: " + rows);
        System.out.println("Invalid rows removed: " + invalid);
        System.out.println("Non-P records removed: " + otherFeatures);
        System.out.println("Repeated coordinate pairs removed: " + duplicates);
        System.out.println("Records kept: " + records.size());
        System.out.println("Output: " + output);
    }

    static String coordinateKey(String[] fields) {
        // Decimal normalization treats 1.50 and 1.5 as the same coordinate.
        // No rounding: nearby points remain separate.
        String latitude = new java.math.BigDecimal(fields[4]).stripTrailingZeros().toPlainString();
        String longitude = new java.math.BigDecimal(fields[5]).stripTrailingZeros().toPlainString();
        return latitude + "," + longitude;
    }

    static boolean valid(String[] fields) {
        if (fields.length != 19 || fields[1].isBlank()) {
            return false;
        }
        try {
            int id = Integer.parseInt(fields[0]);
            double latitude = Double.parseDouble(fields[4]);
            double longitude = Double.parseDouble(fields[5]);
            long population = fields[14].isEmpty() ? 0 : Long.parseLong(fields[14]);
            LocalDate.parse(fields[18]);
            return id > 0 && population >= 0
                    && Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                    && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180;
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException error) {
            return false;
        }
    }
}
