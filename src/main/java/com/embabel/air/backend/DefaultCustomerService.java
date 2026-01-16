package com.embabel.air.backend;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultCustomerService implements CustomerService {

    private final CustomerRepository customerRepository;

    DefaultCustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    @Nullable
    public Customer getAuthenticatedUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof UserDetails userDetails) {
            return customerRepository.findByUsername(userDetails.getUsername()).orElse(null);
        }
        return null;
    }

    @Override
    @Nullable
    public Customer findById(@NonNull String id) {
        return customerRepository.findById(id).orElse(null);
    }

    @Override
    @Nullable
    public Customer findByUsername(@NonNull String username) {
        return customerRepository.findByUsername(username).orElse(null);
    }

    @Override
    @Nullable
    public Customer findByEmail(@NonNull String email) {
        return customerRepository.findByEmail(email).orElse(null);
    }
}
