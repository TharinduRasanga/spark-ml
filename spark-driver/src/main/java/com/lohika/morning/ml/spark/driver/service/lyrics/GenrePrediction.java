package com.lohika.morning.ml.spark.driver.service.lyrics;

public class GenrePrediction {

    private String genre;
    private Double popProbability;
    private Double countryProbability;
    private Double bluesProbability;
    private Double jazzProbability;
    private Double reggaeProbability;
    private Double rockProbability;
    private Double hipHopProbability;

    public GenrePrediction(String genre, Double popProbability,
                           Double countryProbability, Double bluesProbability, Double jazzProbability,
                           Double reggaeProbability, Double rockProbability, Double hipHopProbability) {
        this.genre = genre;
        this.popProbability = popProbability;
        this.countryProbability = countryProbability;
        this.bluesProbability = bluesProbability;
        this.jazzProbability = jazzProbability;
        this.reggaeProbability = reggaeProbability;
        this.rockProbability = rockProbability;
        this.hipHopProbability = hipHopProbability;
    }

    public GenrePrediction(String genre) {
        this.genre = genre;
    }

    public String getGenre() {
        return genre;
    }

    public Double getPopProbability() {
        return popProbability;
    }

    // Add getters for probabilities of additional genres

    public Double getCountryProbability() {
        return countryProbability;
    }

    public Double getBluesProbability() {
        return bluesProbability;
    }

    public Double getJazzProbability() {
        return jazzProbability;
    }

    public Double getReggaeProbability() {
        return reggaeProbability;
    }

    public Double getRockProbability() {
        return rockProbability;
    }

    public Double getHipHopProbability() {
        return hipHopProbability;
    }

    // Add more getters for additional genres as needed
}
