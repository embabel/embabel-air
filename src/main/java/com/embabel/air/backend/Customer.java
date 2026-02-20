package com.embabel.air.backend;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.identity.User;
import jakarta.persistence.*;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * User model for Embabel Air.
 */
@Entity
@Table(indexes = {
        @Index(name = "idx_customer_username", columnList = "username", unique = true),
        @Index(name = "idx_customer_email", columnList = "email")
})
public class Customer implements User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String displayName;

    private String username;

    @Nullable
    private String email;

    private String password;

    @Embedded
    @Nullable
    private SkyPointsStatus skyPointsStatus;

    @OneToMany(mappedBy = "customer", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<Reservation> reservations = new ArrayList<>();

    protected Customer() {
    }

    public Customer(String displayName, String username, String password) {
        this.displayName = displayName;
        this.username = username;
        this.password = password;
    }

    public Customer(String displayName, String username, String password, @Nullable String email) {
        this.displayName = displayName;
        this.username = username;
        this.password = password;
        this.email = email;
    }

    @Override
    public @NonNull String getId() {
        return id;
    }

    @Override
    public @NonNull String getDisplayName() {
        return displayName;
    }

    @Override
    public @NonNull String getUsername() {
        return username;
    }

    @Override
    @Nullable
    public String getEmail() {
        return email;
    }

    public void setEmail(@Nullable String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    @Nullable
    @LlmTool(description = "Get customer's SkyPoints loyalty status")
    public SkyPointsStatus getStatus() {
        return skyPointsStatus;
    }

    /**
     * Enroll in our program to earn SkyPoints.
     */
    public SkyPointsStatus signUpForSkyPoints() {
        if (this.skyPointsStatus != null) {
            throw new IllegalStateException("Customer already has a status");
        }
        this.skyPointsStatus = SkyPointsStatus.createNew();
        return this.skyPointsStatus;
    }

    public List<Reservation> getReservations() {
        return reservations;
    }

    @LlmTool(description = "Get customer's flight reservations with flight details including departure and arrival cities and times")
    public String getFlightInfo() {
        if (reservations.isEmpty()) {
            return "No reservations found.";
        }
        var sb = new StringBuilder();
        for (var res : reservations) {
            sb.append("Booking ").append(res.getBookingReference());
            sb.append(" (").append(res.isCheckedIn() ? "checked in" : "not checked in").append(")\n");
            for (var seg : res.getFlightSegments()) {
                sb.append("  ").append(seg.getDepartureAirportCode())
                        .append(" → ").append(seg.getArrivalAirportCode())
                        .append(" departing ").append(seg.getDepartureDateTime())
                        .append(" arriving ").append(seg.getArrivalDateTime())
                        .append("\n");
            }
        }
        return sb.toString().trim();
    }

    public void addReservation(Reservation reservation) {
        reservations.add(reservation);
        reservation.setCustomer(this);
    }

    @ApiStatus.Internal
    public void setStatus(SkyPointsStatus skyPointsStatus) {
        this.skyPointsStatus = skyPointsStatus;
    }
}
