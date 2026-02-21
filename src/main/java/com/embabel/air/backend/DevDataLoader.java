package com.embabel.air.backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Component
@Profile("dev")
public class DevDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataLoader.class);

    public static final String DEFAULT_PASSWORD = "password";

    public static final List<DemoUser> DEMO_USERS = List.of(
            new DemoUser("alex.novice", "Alex Novice", "New customer, no flights yet", null),
            new DemoUser("sam.bronze", "Sam Bronze", "Bronze status, some travel history", SkyPointsStatus.Level.BRONZE),
            new DemoUser("jamie.silver", "Jamie Silver", "Silver status, regular traveler", SkyPointsStatus.Level.SILVER),
            new DemoUser("taylor.gold", "Taylor Gold", "Gold status, frequent flyer", SkyPointsStatus.Level.GOLD),
            new DemoUser("morgan.platinum", "Morgan Platinum", "Platinum status, elite traveler", SkyPointsStatus.Level.PLATINUM)
    );

    public record DemoUser(String username, String displayName, String description, SkyPointsStatus.Level level) {
    }

    private static final List<String> AIRPORTS = List.of(
            "JFK", "LAX", "ORD", "DFW", "DEN", "SFO", "SEA", "LAS", "MIA", "BOS",
            "ATL", "PHX", "IAH", "MSP", "DTW", "PHL", "LGA", "BWI", "SLC", "DCA"
    );

    private static final List<String> AIRLINES = List.of(
            "AA", "UA", "DL", "WN", "AS", "B6", "NK", "F9"
    );

    private static final List<String> EQUIPMENT = List.of(
            "Boeing 737-800", "Boeing 737 MAX 8", "Boeing 777-200", "Boeing 787-9",
            "Airbus A320", "Airbus A321", "Airbus A350-900", "Embraer E175"
    );

    private final CustomerRepository customerRepository;
    private final FlightSegmentRepository flightSegmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final Random random;

    DevDataLoader(CustomerRepository customerRepository, FlightSegmentRepository flightSegmentRepository,
                  PasswordEncoder passwordEncoder) {
        this.customerRepository = customerRepository;
        this.flightSegmentRepository = flightSegmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.random = new Random(42);
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (customerRepository.count() > 0) {
            log.info("Data already exists, skipping seed");
            return;
        }

        log.info("Seeding dev data...");
        seedDemoCustomers();
        seedAvailableFlights();
        log.info("Dev data seeding complete");
        log.info("All demo users use password: '{}'", DEFAULT_PASSWORD);
    }

    private void seedDemoCustomers() {
        var encodedPassword = passwordEncoder.encode(DEFAULT_PASSWORD);
        var now = LocalDateTime.now();

        for (var demoUser : DEMO_USERS) {
            var customer = new Customer(
                    demoUser.displayName(),
                    demoUser.username(),
                    encodedPassword,
                    demoUser.username() + "@example.com"
            );

            if (demoUser.level() != null) {
                int points = switch (demoUser.level()) {
                    case BRONZE -> 5000 + random.nextInt(10000);
                    case SILVER -> 25000 + random.nextInt(25000);
                    case GOLD -> 75000 + random.nextInt(25000);
                    case PLATINUM -> 150000 + random.nextInt(100000);
                };
                var signUpDate = LocalDate.now().minusYears(random.nextInt(3) + 1);
                customer.setStatus(SkyPointsStatus.create(demoUser.level(), points, signUpDate));

                // Create reservations based on level
                int pastReservations = switch (demoUser.level()) {
                    case BRONZE -> 2;
                    case SILVER -> 5;
                    case GOLD -> 10;
                    case PLATINUM -> 25;
                };
                int futureReservations = switch (demoUser.level()) {
                    case BRONZE -> 1;
                    case SILVER -> 2;
                    case GOLD -> 3;
                    case PLATINUM -> 5;
                };

                // Past reservations
                for (int i = 0; i < pastReservations; i++) {
                    var reservation = createReservation(now.minusDays(random.nextInt(365) + 1), true);
                    customer.addReservation(reservation);
                }

                // Future reservations
                for (int i = 0; i < futureReservations; i++) {
                    var reservation = createReservation(now.plusDays(random.nextInt(90) + 1), false);
                    customer.addReservation(reservation);
                }
            }

            customerRepository.save(customer);
            log.info("Created demo user: {} ({})", demoUser.username(), demoUser.description());
        }
    }

    private Reservation createReservation(LocalDateTime departureTime, boolean isPast) {
        var bookingRef = generateBookingReference();
        var reservation = new Reservation(bookingRef);
        reservation.setPaidAmount(BigDecimal.valueOf(200 + random.nextInt(800)));

        // Create 1-2 segments (one-way or round-trip)
        var isRoundTrip = random.nextBoolean();
        var departure = AIRPORTS.get(random.nextInt(AIRPORTS.size()));
        var arrival = AIRPORTS.get(random.nextInt(AIRPORTS.size()));
        while (arrival.equals(departure)) {
            arrival = AIRPORTS.get(random.nextInt(AIRPORTS.size()));
        }

        var outbound = createFlightSegment(departure, arrival, departureTime);
        reservation.addFlightSegment(outbound);

        if (isRoundTrip) {
            var returnTime = departureTime.plusDays(random.nextInt(7) + 1);
            var returnFlight = createFlightSegment(arrival, departure, returnTime);
            reservation.addFlightSegment(returnFlight);
        }

        if (isPast) {
            reservation.setCheckedIn(true);
        }

        return reservation;
    }

    private FlightSegment createFlightSegment(String departure, String arrival, LocalDateTime departureTime) {
        var hour = 6 + random.nextInt(16);
        var adjustedDeparture = departureTime.withHour(hour).withMinute(random.nextInt(4) * 15);
        var flightDurationMinutes = 60 + random.nextInt(300);
        var arrivalTime = adjustedDeparture.plusMinutes(flightDurationMinutes);

        var airline = AIRLINES.get(random.nextInt(AIRLINES.size()));
        var equipment = EQUIPMENT.get(random.nextInt(EQUIPMENT.size()));

        var segment = new FlightSegment(departure, adjustedDeparture, arrival, arrivalTime, airline);
        segment.setEquipment(equipment);
        segment.setSeatsLeft(random.nextInt(50));

        return segment;
    }

    private String generateBookingReference() {
        var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static final List<String[]> POPULAR_ROUTES = List.of(
            new String[]{"JFK", "LAX"}, new String[]{"LAX", "JFK"},
            new String[]{"JFK", "SFO"}, new String[]{"SFO", "JFK"},
            new String[]{"JFK", "MIA"}, new String[]{"MIA", "JFK"},
            new String[]{"JFK", "ORD"}, new String[]{"ORD", "JFK"},
            new String[]{"LAX", "ORD"}, new String[]{"ORD", "LAX"},
            new String[]{"LAX", "SEA"}, new String[]{"SEA", "LAX"},
            new String[]{"ORD", "MIA"}, new String[]{"MIA", "ORD"},
            new String[]{"DFW", "ATL"}, new String[]{"ATL", "DFW"},
            new String[]{"SFO", "SEA"}, new String[]{"SEA", "SFO"},
            new String[]{"BOS", "DCA"}, new String[]{"DCA", "BOS"}
    );

    private void seedAvailableFlights() {
        var now = LocalDateTime.now();

        // Seed popular routes: 2 flights per day for 30 days on each route
        for (var route : POPULAR_ROUTES) {
            for (int day = 0; day < 30; day++) {
                // Morning flight
                seedFlight(route[0], route[1], now.plusDays(day), 7 + random.nextInt(4));
                // Afternoon/evening flight
                seedFlight(route[0], route[1], now.plusDays(day), 14 + random.nextInt(6));
            }
        }

        // Additional random flights for variety and connecting options
        for (int i = 0; i < 300; i++) {
            var departure = AIRPORTS.get(random.nextInt(AIRPORTS.size()));
            var arrival = AIRPORTS.get(random.nextInt(AIRPORTS.size()));
            while (arrival.equals(departure)) {
                arrival = AIRPORTS.get(random.nextInt(AIRPORTS.size()));
            }

            var daysOffset = random.nextInt(30);
            var hour = 6 + random.nextInt(16);
            seedFlight(departure, arrival, now.plusDays(daysOffset), hour);
        }
        log.info("Created {} available flight segments", flightSegmentRepository.count());
    }

    private void seedFlight(String departure, String arrival, LocalDateTime baseDate, int hour) {
        var departureTime = baseDate.withHour(hour).withMinute(random.nextInt(4) * 15);
        var flightDurationMinutes = 60 + random.nextInt(300);
        var arrivalTime = departureTime.plusMinutes(flightDurationMinutes);

        var airline = AIRLINES.get(random.nextInt(AIRLINES.size()));
        var equipment = EQUIPMENT.get(random.nextInt(EQUIPMENT.size()));
        var seatsLeft = 10 + random.nextInt(150);

        var segment = new FlightSegment(departure, departureTime, arrival, arrivalTime, airline);
        segment.setEquipment(equipment);
        segment.setSeatsLeft(seatsLeft);

        flightSegmentRepository.save(segment);
    }
}
