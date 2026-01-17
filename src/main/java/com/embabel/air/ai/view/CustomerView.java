/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.air.ai.view;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.air.backend.Customer;
import com.embabel.air.backend.Reservation;
import com.embabel.air.backend.SkyPointsStatus;
import com.embabel.springdata.EntityView;
import com.embabel.springdata.LlmView;
import io.vavr.collection.List;

import java.time.LocalDate;

/**
 * EntityView for Customer that exposes customer tools to the LLM.
 */
@LlmView
public interface CustomerView extends EntityView<Customer> {

    @LlmTool(description = "Get the customer's flight reservations, optionally filtered by date range")
    default List<Reservation> getReservations(
            @LlmTool.Param(description = "Start date (YYYY-MM-DD), or omit for all reservations", required = false)
            LocalDate fromDate,
            @LlmTool.Param(description = "End date (YYYY-MM-DD), or omit for all reservations", required = false)
            LocalDate toDate
    ) {
        return List.ofAll(getEntity().getReservations())
                .filter(r -> fromDate == null || !departureDate(r).isBefore(fromDate))
                .filter(r -> toDate == null || !departureDate(r).isAfter(toDate));
    }

    private static LocalDate departureDate(Reservation r) {
        return r.getFlightSegments().getFirst().getDepartureDateTime().toLocalDate();
    }

    @LlmTool(description = "Get the customer's SkyPoints loyalty status")
    default SkyPointsStatus getStatus() {
        return getEntity().getStatus();
    }

    @Override
    default String summary() {
        var customer = getEntity();
        var status = customer.getStatus();
        return "Customer: %s%s".formatted(
                customer.getDisplayName(),
                status != null ? " (" + status.getLevel() + ")" : ""
        );
    }

    @Override
    default String fullText() {
        var customer = getEntity();
        var status = customer.getStatus();
        var sb = new StringBuilder();
        sb.append("Customer: ").append(customer.getDisplayName()).append("\n");
        if (customer.getEmail() != null) {
            sb.append("Email: ").append(customer.getEmail()).append("\n");
        }
        if (status != null) {
            sb.append("SkyPoints Member ID: ").append(status.getMemberId()).append("\n");
            sb.append("SkyPoints Status: ").append(status.getLevel()).append("\n");
            sb.append("Points Balance: ").append(status.getPoints()).append("\n");
        }
        sb.append("Reservations: ").append(customer.getReservations().size());
        return sb.toString();
    }
}
