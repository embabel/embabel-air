package com.embabel.air.backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

@Service
@Transactional
public class DefaultBookingService implements BookingService {

    private final FlightSegmentRepository flightSegmentRepository;
    private final ReservationRepository reservationRepository;
    private final Random random = new Random(42);

    public DefaultBookingService(FlightSegmentRepository flightSegmentRepository,
                                 ReservationRepository reservationRepository) {
        this.flightSegmentRepository = flightSegmentRepository;
        this.reservationRepository = reservationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlightSegment> searchDirectFlights(String fromAirport, String toAirport, LocalDate date) {
        var start = date.atStartOfDay();
        var end = date.plusDays(1).atStartOfDay();
        return flightSegmentRepository
                .findByDepartureAirportCodeAndArrivalAirportCodeAndDepartureDateTimeBetween(
                        fromAirport.toUpperCase(), toAirport.toUpperCase(), start, end)
                .stream()
                .filter(s -> s.getReservation() == null && s.getSeatsLeft() > 0)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Itinerary> searchRoutes(String fromAirport, String toAirport, LocalDate date, int maxConnections) {
        var from = fromAirport.toUpperCase();
        var to = toAirport.toUpperCase();
        var results = new ArrayList<Itinerary>();

        // Direct flights
        var directFlights = searchDirectFlights(from, to, date);
        for (var flight : directFlights) {
            results.add(buildItinerary(List.of(flight)));
        }

        // Connecting flights (1 stop)
        if (maxConnections >= 1) {
            var dayStart = date.atStartOfDay();
            var dayEnd = date.plusDays(1).atStartOfDay();

            var firstLegs = flightSegmentRepository
                    .findByDepartureAirportCodeAndDepartureDateTimeBetween(from, dayStart, dayEnd)
                    .stream()
                    .filter(s -> s.getReservation() == null && s.getSeatsLeft() > 0)
                    .filter(s -> !s.getArrivalAirportCode().equals(from))
                    .toList();

            for (var first : firstLegs) {
                var connectionAirport = first.getArrivalAirportCode();
                if (connectionAirport.equals(to)) {
                    continue; // already covered by direct
                }

                // Look for connecting flights 1-6 hours after arrival
                var minConnect = first.getArrivalDateTime().plusHours(1);
                var maxConnect = first.getArrivalDateTime().plusHours(6);

                var secondLegs = flightSegmentRepository
                        .findByDepartureAirportCodeAndArrivalAirportCodeAndDepartureDateTimeBetween(
                                connectionAirport, to, minConnect, maxConnect)
                        .stream()
                        .filter(s -> s.getReservation() == null && s.getSeatsLeft() > 0)
                        .toList();

                for (var second : secondLegs) {
                    results.add(buildItinerary(List.of(first, second)));
                }
            }
        }

        results.sort(Comparator.comparing(Itinerary::totalTravelTime));
        return results;
    }

    @Override
    public Reservation book(Customer customer, List<String> flightSegmentIds) {
        var segments = flightSegmentIds.stream()
                .map(id -> flightSegmentRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Flight segment not found: " + id)))
                .toList();

        for (var seg : segments) {
            if (seg.getReservation() != null) {
                throw new IllegalStateException(
                        "Flight segment %s is already booked".formatted(seg.getId()));
            }
            if (seg.getSeatsLeft() <= 0) {
                throw new IllegalStateException(
                        "No seats left on flight %s → %s departing %s".formatted(
                                seg.getDepartureAirportCode(), seg.getArrivalAirportCode(),
                                seg.getDepartureDateTime()));
            }
        }

        var reservation = new Reservation(generateBookingReference());
        var totalPrice = BigDecimal.ZERO;

        for (var seg : segments) {
            reservation.addFlightSegment(seg);
            seg.setSeatsLeft(seg.getSeatsLeft() - 1);
            totalPrice = totalPrice.add(estimateSegmentPrice(seg));
        }

        reservation.setPaidAmount(totalPrice);
        customer.addReservation(reservation);
        reservationRepository.save(reservation);
        return reservation;
    }

    @Override
    public void cancel(String bookingReference) {
        var reservation = reservationRepository.findByBookingReference(bookingReference);
        if (reservation == null) {
            throw new IllegalArgumentException("Reservation not found: " + bookingReference);
        }

        for (var seg : new ArrayList<>(reservation.getFlightSegments())) {
            seg.setSeatsLeft(seg.getSeatsLeft() + 1);
            reservation.removeFlightSegment(seg);
        }

        var customer = reservation.getCustomer();
        if (customer != null) {
            customer.getReservations().remove(reservation);
        }
        reservationRepository.delete(reservation);
    }

    @Override
    public Reservation rebook(String bookingReference, List<String> newFlightSegmentIds) {
        var reservation = reservationRepository.findByBookingReference(bookingReference);
        if (reservation == null) {
            throw new IllegalArgumentException("Reservation not found: " + bookingReference);
        }

        var customer = reservation.getCustomer();

        // Release old segments
        for (var seg : new ArrayList<>(reservation.getFlightSegments())) {
            seg.setSeatsLeft(seg.getSeatsLeft() + 1);
            reservation.removeFlightSegment(seg);
        }

        // Load and validate new segments
        var newSegments = newFlightSegmentIds.stream()
                .map(id -> flightSegmentRepository.findById(id)
                        .orElseThrow(() -> new IllegalArgumentException("Flight segment not found: " + id)))
                .toList();

        var totalPrice = BigDecimal.ZERO;
        for (var seg : newSegments) {
            if (seg.getReservation() != null) {
                throw new IllegalStateException(
                        "Flight segment %s is already booked".formatted(seg.getId()));
            }
            if (seg.getSeatsLeft() <= 0) {
                throw new IllegalStateException(
                        "No seats left on flight %s → %s departing %s".formatted(
                                seg.getDepartureAirportCode(), seg.getArrivalAirportCode(),
                                seg.getDepartureDateTime()));
            }
            reservation.addFlightSegment(seg);
            seg.setSeatsLeft(seg.getSeatsLeft() - 1);
            totalPrice = totalPrice.add(estimateSegmentPrice(seg));
        }

        reservation.setPaidAmount(totalPrice);
        return reservation;
    }

    private Itinerary buildItinerary(List<FlightSegment> segments) {
        var first = segments.getFirst();
        var last = segments.getLast();
        var totalTravel = Duration.between(first.getDepartureDateTime(), last.getArrivalDateTime());

        var totalLayover = Duration.ZERO;
        for (int i = 0; i < segments.size() - 1; i++) {
            var arrivalTime = segments.get(i).getArrivalDateTime();
            var nextDeparture = segments.get(i + 1).getDepartureDateTime();
            totalLayover = totalLayover.plus(Duration.between(arrivalTime, nextDeparture));
        }

        var price = BigDecimal.ZERO;
        for (var seg : segments) {
            price = price.add(estimateSegmentPrice(seg));
        }

        return new Itinerary(segments, totalTravel, totalLayover, price);
    }

    /**
     * Simple pricing heuristic:
     * Base: $50 + $0.50 per minute of flight time.
     * Weekend surcharge: +30% for Saturday/Sunday departures.
     * Lucky-3 discount: -20% if departure minute is divisible by 3.
     */
    BigDecimal estimateSegmentPrice(FlightSegment segment) {
        long flightMinutes = segment.getDuration().toMinutes();
        var base = BigDecimal.valueOf(50).add(
                BigDecimal.valueOf(flightMinutes).multiply(BigDecimal.valueOf(0.50)));

        DayOfWeek day = segment.getDepartureDateTime().getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            base = base.multiply(BigDecimal.valueOf(1.30));
        }

        int minute = segment.getDepartureDateTime().getMinute();
        if (minute % 3 == 0) {
            base = base.multiply(BigDecimal.valueOf(0.80));
        }

        return base.setScale(2, RoundingMode.HALF_UP);
    }

    private String generateBookingReference() {
        var chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        var sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
