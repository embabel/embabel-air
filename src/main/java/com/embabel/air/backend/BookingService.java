package com.embabel.air.backend;

import java.time.LocalDate;
import java.util.List;

public interface BookingService {

    /**
     * Search for available direct flights between two airports on a given date.
     */
    List<FlightSegment> searchDirectFlights(String fromAirport, String toAirport, LocalDate date);

    /**
     * Search for available routes (direct or connecting) between two airports.
     * Returns itineraries, each containing one or more flight segments.
     */
    List<Itinerary> searchRoutes(String fromAirport, String toAirport, LocalDate date, int maxConnections);

    /**
     * Book a trip for a customer given selected flight segment IDs.
     * Creates a Reservation, assigns segments, decrements seats.
     */
    Reservation book(Customer customer, List<String> flightSegmentIds);

    /**
     * Cancel a reservation. Releases seats back to available inventory.
     */
    void cancel(String bookingReference);

    /**
     * Rebook an existing reservation onto different flights.
     * Cancels old segments, books new ones.
     */
    Reservation rebook(String bookingReference, List<String> newFlightSegmentIds);
}
