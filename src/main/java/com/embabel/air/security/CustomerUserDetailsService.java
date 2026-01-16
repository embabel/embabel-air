package com.embabel.air.security;

import com.embabel.air.backend.CustomerRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
class CustomerUserDetailsService implements UserDetailsService {

    private final CustomerRepository customerRepository;

    CustomerUserDetailsService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var customer = customerRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Customer not found: " + username));

        return User.builder()
                .username(customer.getUsername())
                .password(customer.getPassword())
                .roles("USER")
                .build();
    }
}
