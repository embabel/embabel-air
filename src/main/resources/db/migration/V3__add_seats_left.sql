alter table flight_segment add column equipment varchar(255);
alter table flight_segment add column seats_left integer not null default 0;
