package dev.mayank.convoinsights.api;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Server-sent events: every new insight is pushed to open browsers ({@code /live.html}). Purely a
 * view: a slow or closed browser is dropped, and nothing here can block or fail the pipeline.
 */
@RestController
public class LiveFeed {

    private final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();

    @GetMapping(path = "/api/insights/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; the browser reconnects if it drops
        clients.add(emitter);
        emitter.onCompletion(() -> clients.remove(emitter));
        emitter.onTimeout(() -> clients.remove(emitter));
        emitter.onError(e -> clients.remove(emitter));
        return emitter;
    }

    /** Sends one insight (as its JSON) to every open browser. */
    public void publish(String insightJson) {
        for (SseEmitter c : clients) {
            try {
                c.send(SseEmitter.event().name("insight").data(insightJson, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                clients.remove(c);
            }
        }
    }

    public int clients() {
        return clients.size();
    }
}
