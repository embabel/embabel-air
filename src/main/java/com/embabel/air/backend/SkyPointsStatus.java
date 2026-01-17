package com.embabel.air.backend;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.LocalDate;

@Embeddable
public class SkyPointsStatus {

    public enum Level {
        BRONZE,
        SILVER,
        GOLD,
        PLATINUM
    }

    private String memberId;

    @Enumerated(EnumType.STRING)
    private Level level;

    private int points;

    private LocalDate signUpDate;

    protected SkyPointsStatus() {
    }

    private static final java.util.List<String> PREFIXES = java.util.List.of(
            "FLY", "SKY", "JET", "ACE", "TOP", "VIP", "AIR", "PRO"
    );
    private static final java.util.List<String> SUFFIXES = java.util.List.of(
            "HIGH", "ZONE", "CLUB", "MILE", "STAR", "WING", "SOAR", "ZOOM"
    );
    private static final java.util.Random RANDOM = new java.util.Random();

    private static String generateMemberId() {
        var prefix = PREFIXES.get(RANDOM.nextInt(PREFIXES.size()));
        var suffix = SUFFIXES.get(RANDOM.nextInt(SUFFIXES.size()));
        var number = 1000 + RANDOM.nextInt(9000);
        return prefix + "-" + suffix + "-" + number;
    }

    public static SkyPointsStatus createNew() {
        var status = new SkyPointsStatus();
        status.memberId = generateMemberId();
        status.points = 0;
        status.level = Level.BRONZE;
        status.signUpDate = LocalDate.now();
        return status;
    }

    public static SkyPointsStatus create(Level level, int points, LocalDate signUpDate) {
        var status = new SkyPointsStatus();
        status.memberId = generateMemberId();
        status.level = level;
        status.points = points;
        status.signUpDate = signUpDate;
        return status;
    }

    public String getMemberId() {
        return memberId;
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public void addPoints(int additionalPoints) {
        this.points += additionalPoints;
    }

    public int getPoints() {
        return points;
    }

    public LocalDate getSignUpDate() {
        return signUpDate;
    }

}
