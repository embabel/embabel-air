package com.embabel.air.backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface FlightSegmentRepository extends JpaRepository<FlightSegment, String> {

    List<FlightSegment> findByDepartureAirportCodeAndArrivalAirportCodeAndDepartureDateTimeBetween(
            String departureAirportCode,
            String arrivalAirportCode,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    );

    List<FlightSegment> findByDepartureAirportCodeAndDepartureDateTimeBetween(
            String departureAirportCode,
            LocalDateTime startDateTime,
            LocalDateTime endDateTime
    );
}
