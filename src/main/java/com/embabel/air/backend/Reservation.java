package com.embabel.air.backend;

import com.embabel.agent.api.annotation.LlmTool;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String bookingReference;

    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<FlightSegment> flightSegments = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private boolean checkedIn;

    private BigDecimal paidAmount;

    private Instant createdAt;

    protected Reservation() {
    }

    public Reservation(String bookingReference) {
        this.bookingReference = bookingReference;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public List<FlightSegment> getFlightSegments() {
        return flightSegments;
    }

    public void addFlightSegment(FlightSegment segment) {
        flightSegments.add(segment);
        segment.setReservation(this);
    }

    public void removeFlightSegment(FlightSegment segment) {
        flightSegments.remove(segment);
        segment.setReservation(null);
    }

    public Customer getCustomer() {
        return customer;
    }

    public void setCustomer(Customer customer) {
        this.customer = customer;
    }

    public boolean isCheckedIn() {
        return checkedIn;
    }

    public void setCheckedIn(boolean checkedIn) {
        this.checkedIn = checkedIn;
    }

    public BigDecimal getPaidAmount() {
        return paidAmount;
    }

    public void setPaidAmount(BigDecimal paidAmount) {
        this.paidAmount = paidAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @LlmTool(description = "Check in for this reservation")
    public String checkIn() {
        if (checkedIn) {
            return "Already checked in for reservation " + bookingReference;
        }
        checkedIn = true;
        return "Successfully checked in for reservation " + bookingReference;
    }
}
