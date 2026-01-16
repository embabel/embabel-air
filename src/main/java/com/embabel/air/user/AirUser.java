package com.embabel.air.user;

import com.embabel.agent.api.identity.User;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

/**
 * User model for Embabel Air.
 */
public class AirUser implements User {

    private final String id;
    private final String displayName;
    private final String username;

    public AirUser(String id, String displayName, String username) {
        this.id = id;
        this.displayName = displayName;
        this.username = username;
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
        return null;
    }
}
