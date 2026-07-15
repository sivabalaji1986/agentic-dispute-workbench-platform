package com.workbench.agui.emitter;

import com.workbench.agui.events.AguiEvents;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

public class AguiEmitter {

    private final Sinks.Many<ServerSentEvent<String>> sink;
    private final ObjectMapper objectMapper;
    private final Object emitLock = new Object();

    public AguiEmitter(ObjectMapper objectMapper) {
        this.sink = Sinks.many().unicast().onBackpressureBuffer();
        this.objectMapper = objectMapper;
    }

    public void emit(Object event) {
        String json = serialize(event);
        synchronized (emitLock) {
            sink.tryEmitNext(ServerSentEvent.builder(json).build());
        }
    }

    public void complete(String threadId, String runId) {
        String json = serialize(new AguiEvents.RunFinishedEvent(threadId, runId));
        synchronized (emitLock) {
            sink.tryEmitNext(ServerSentEvent.builder(json).build());
            sink.tryEmitComplete();
        }
    }

    public void error(String threadId, String runId, String message) {
        String json = serialize(new AguiEvents.RunErrorEvent(threadId, runId, message));
        synchronized (emitLock) {
            sink.tryEmitNext(ServerSentEvent.builder(json).build());
            sink.tryEmitComplete();
        }
    }

    private String serialize(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (RuntimeException e) {
            return objectMapper.writeValueAsString(
                    new AguiEvents.RunErrorEvent(null, null, "Failed to serialize event: " + e.getMessage()));
        }
    }

    public Flux<ServerSentEvent<String>> flux() {
        return sink.asFlux();
    }
}
