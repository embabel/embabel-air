package com.embabel.air.backend;

import jakarta.persistence.*;

import java.time.Duration;
import java.time.LocalDateTime;

@Entity
@Table(indexes = {
        @Index(name = "idx_flight_search", columnList = "departureAirportCode, arrivalAirportCode, departureDateTime")
})
public class FlightSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String departureAirportCode;

    private LocalDateTime departureDateTime;

    private String arrivalAirportCode;

    private LocalDateTime arrivalDateTime;

    private String airline;

    private String equipment;

    private int seatsLeft;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reservation_id")
    private Reservation reservation;

    protected FlightSegment() {
    }

    public FlightSegment(String departureAirportCode, LocalDateTime departureDateTime,
                         String arrivalAirportCode, LocalDateTime arrivalDateTime, String airline) {
        this.departureAirportCode = departureAirportCode;
        this.departureDateTime = departureDateTime;
        this.arrivalAirportCode = arrivalAirportCode;
        this.arrivalDateTime = arrivalDateTime;
        this.airline = airline;
    }

    public String getId() {
        return id;
    }

    public String getDepartureAirportCode() {
        return departureAirportCode;
    }

    public LocalDateTime getDepartureDateTime() {
        return departureDateTime;
    }

    public String getArrivalAirportCode() {
        return arrivalAirportCode;
    }

    public LocalDateTime getArrivalDateTime() {
        return arrivalDateTime;
    }

    public String getAirline() {
        return airline;
    }

    public String getEquipment() {
        return equipment;
    }

    public Duration getDuration() {
        return Duration.between(departureDateTime, arrivalDateTime);
    }

    public int getSeatsLeft() {
        return seatsLeft;
    }

    public void setSeatsLeft(int seatsLeft) {
        this.seatsLeft = seatsLeft;
    }

    public Reservation getReservation() {
        return reservation;
    }

    void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }
}
