
    create table customer (
        id varchar(255) not null,
        display_name varchar(255),
        email varchar(255),
        level varchar(255) check (level in ('BRONZE','SILVER','GOLD','PLATINUM')),
        points integer,
        sign_up_date date,
        username varchar(255),
        primary key (id),
        constraint idx_customer_username unique (username)
    );

    create table flight_segment (
        id varchar(255) not null,
        airline varchar(255),
        arrival_airport_code varchar(255),
        arrival_date_time timestamp(6),
        departure_airport_code varchar(255),
        departure_date_time timestamp(6),
        reservation_id varchar(255),
        primary key (id)
    );

    create table reservation (
        id varchar(255) not null,
        booking_reference varchar(255),
        checked_in boolean not null,
        created_at timestamp(6) with time zone,
        paid_amount numeric(38,2),
        customer_id varchar(255),
        primary key (id)
    );

    create index idx_customer_email 
       on customer (email);

    alter table if exists flight_segment 
       add constraint FKc1qa7wc9bjguhwxxicayh8qh3 
       foreign key (reservation_id) 
       references reservation;

    alter table if exists reservation 
       add constraint FK41v6ueo0hiran65w8y1cta2c2 
       foreign key (customer_id) 
       references customer;
