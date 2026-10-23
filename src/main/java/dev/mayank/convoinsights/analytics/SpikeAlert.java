package dev.mayank.convoinsights.analytics;

/** A brand-bucket whose share of negative conversations crossed the alert threshold. */
public record SpikeAlert(String brand, String bucket, long conversations, long negative, double negativeShare) {
}
