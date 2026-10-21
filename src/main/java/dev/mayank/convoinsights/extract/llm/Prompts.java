package dev.mayank.convoinsights.extract.llm;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Versioned prompts live in {@code src/main/resources/prompts/extract-<version>.txt}. */
public final class Prompts {

    private Prompts() {
    }

    public static String load(String version) {
        var resource = new ClassPathResource("prompts/extract-" + version + ".txt");
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing prompt " + resource.getPath(), e);
        }
    }
}
