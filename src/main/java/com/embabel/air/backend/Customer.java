package com.embabel.air.backend;

import com.embabel.agent.api.identity.User;
import jakarta.persistence.*;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

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

    @Embedded
    @Nullable
    private SkyPointsStatus skyPointsStatus;

    protected Customer() {
    }

    public Customer(String displayName, String username) {
        this.displayName = displayName;
        this.username = username;
    }

    public Customer(String displayName, String username, @Nullable String email) {
        this.displayName = displayName;
        this.username = username;
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

}
