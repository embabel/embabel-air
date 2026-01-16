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

    @Enumerated(EnumType.STRING)
    private Level level;

    private int points;

    private LocalDate signUpDate;

    protected SkyPointsStatus() {
    }

    public static SkyPointsStatus createNew() {
        var status = new SkyPointsStatus();
        status.points = 0;
        status.level = Level.BRONZE;
        status.signUpDate = LocalDate.now();
        return status;
    }

    public static SkyPointsStatus create(Level level, int points, LocalDate signUpDate) {
        var status = new SkyPointsStatus();
        status.level = level;
        status.points = points;
        status.signUpDate = signUpDate;
        return status;
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
