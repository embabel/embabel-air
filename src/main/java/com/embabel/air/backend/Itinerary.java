package com.embabel.air.backend;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

public record Itinerary(
        List<FlightSegment> segments,
        Duration totalTravelTime,
        Duration totalLayoverTime,
        BigDecimal estimatedPrice
) {

    public int connections() {
        return segments.size() - 1;
    }

    public String summary() {
        var route = segments.stream()
                .map(FlightSegment::getDepartureAirportCode)
                .collect(Collectors.joining(" → "));
        route += " → " + segments.getLast().getArrivalAirportCode();

        var sb = new StringBuilder();
        sb.append(route);
        sb.append(" | Travel time: ").append(formatDuration(totalTravelTime));
        if (connections() > 0) {
            sb.append(" | Layover: ").append(formatDuration(totalLayoverTime));
        }
        sb.append(" | Est. price: $").append(estimatedPrice);
        sb.append("\n");

        for (var seg : segments) {
            sb.append("  ").append(seg.getDepartureAirportCode())
                    .append(" → ").append(seg.getArrivalAirportCode())
                    .append(" | ").append(seg.getAirline())
                    .append(" | ").append(seg.getEquipment())
                    .append(" | Departs ").append(seg.getDepartureDateTime())
                    .append(" Arrives ").append(seg.getArrivalDateTime())
                    .append(" (").append(formatDuration(seg.getDuration())).append(")")
                    .append(" | ").append(seg.getSeatsLeft()).append(" seats left")
                    .append(" | ID: ").append(seg.getId())
                    .append("\n");
        }

        return sb.toString().trim();
    }

    private static String formatDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        return hours + "h " + minutes + "m";
    }
}
