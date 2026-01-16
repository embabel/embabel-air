package com.embabel.air.user;

import com.embabel.agent.api.identity.UserService;

/**
 * Service for managing Embabel Air users.
 */
public interface AirUserService extends UserService<AirUser> {

    /**
     * Get the currently authenticated user.
     */
    AirUser getAuthenticatedUser();
}
