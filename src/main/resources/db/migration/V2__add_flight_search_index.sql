create index idx_flight_search
    on flight_segment (departure_airport_code, arrival_airport_code, departure_date_time);
