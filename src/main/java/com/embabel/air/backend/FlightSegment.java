package com.embabel.air.backend;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

import java.time.LocalDateTime;

@Entity
public class FlightSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String departureAirportCode;

    private LocalDateTime departureDateTime;

    private String arrivalAirportCode;

    private LocalDateTime arrivalDateTime;

    private String airline;

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

    public Reservation getReservation() {
        return reservation;
    }

    void setReservation(Reservation reservation) {
        this.reservation = reservation;
    }
}
