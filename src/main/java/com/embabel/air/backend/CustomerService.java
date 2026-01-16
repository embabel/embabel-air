package com.embabel.air.backend;

import com.embabel.agent.api.identity.UserService;
import org.jspecify.annotations.Nullable;

/**
 * Service for managing Embabel Air users.
 */
public interface CustomerService extends UserService<Customer> {

    /**
     * Get the currently authenticated user.
     */
    @Nullable
    Customer getAuthenticatedUser();
}
