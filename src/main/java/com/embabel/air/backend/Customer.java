package com.embabel.air.backend;

import com.embabel.agent.api.identity.User;
import jakarta.persistence.*;
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
    public SkyPointsStatus getStatus() {
        return skyPointsStatus;
    }

    /**
     * Enroll in our program to earn SkyPoints.
     */
    public void signUpForSkyPoints() {
        if (this.skyPointsStatus != null) {
            throw new IllegalStateException("Customer already has a status");
        }
        this.skyPointsStatus = SkyPointsStatus.createNew();
    }

    public void setStatus(SkyPointsStatus status) {
        this.skyPointsStatus = status;
    }

    public List<Reservation> getReservations() {
        return reservations;
    }

    public void addReservation(Reservation reservation) {
        reservations.add(reservation);
        reservation.setCustomer(this);
    }

}
