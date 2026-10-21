package dev.mayank.convoinsights.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record Turn(@NotNull Speaker speaker, @NotBlank String text, String ts) {
}
