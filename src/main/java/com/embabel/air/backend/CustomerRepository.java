package com.embabel.air.backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String> {

    Optional<Customer> findByUsername(String username);

    Optional<Customer> findByEmail(String email);
}
