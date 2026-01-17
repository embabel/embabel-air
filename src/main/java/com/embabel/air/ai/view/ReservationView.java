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
import com.embabel.air.backend.Reservation;
import com.embabel.springdata.EntityView;
import com.embabel.springdata.EntityViewFor;

/**
 * EntityView for Customer that exposes customer tools to the LLM.
 */
@EntityViewFor(entity = Reservation.class, description = "Reservation view")
public interface ReservationView extends EntityView<Reservation> {

    @Override
    default String summary() {
        var reservation = getEntity();
        return "Reservation %s (id=%s): %d flight segments, booked on %s, from %s to %s".formatted(
                reservation.getBookingReference(),
                reservation.getId(),
                reservation.getFlightSegments().size(),
                reservation.getCreatedAt().toString(),
                reservation.getFlightSegments().getFirst().getDepartureAirportCode(),
                reservation.getFlightSegments().getLast().getArrivalAirportCode()
        );
    }

    @Override
    default String fullText() {
        var reservation = getEntity();
        var sb = new StringBuilder();
        sb.append(summary()).append("\n");
        for (var segment : reservation.getFlightSegments()) {
            sb.append("Segment from %s to %s, departing at %s, arriving at %s\n".formatted(
                    segment.getDepartureAirportCode(),
                    segment.getArrivalAirportCode(),
                    segment.getDepartureDateTime().toString(),
                    segment.getArrivalDateTime().toString()
            ));
        }
        return sb.toString();
    }

    @LlmTool(description = "Check in for this reservation")
    default String checkIn() {
        var reservation = getEntity();
        if (reservation.isCheckedIn()) {
            return "Already checked in for reservation " + reservation.getBookingReference();
        }
        reservation.setCheckedIn(true);
        return "Successfully checked in for reservation " + reservation.getBookingReference();
    }
}
