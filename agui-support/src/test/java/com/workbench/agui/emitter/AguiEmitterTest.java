package com.workbench.agui.emitter;

import com.workbench.agui.events.AguiEvents;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AguiEmitterTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void emitRunStartedEventProducesMatchingSseData() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.emit(new AguiEvents.RunStartedEvent("thread-1", "run-1"));
        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_STARTED", node.get("type").asString());
                    assertEquals("thread-1", node.get("threadId").asString());
                    assertEquals("run-1", node.get("runId").asString());
                })
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void emitProgressCustomEventProducesNestedValueShape() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.emit(AguiEvents.CustomEvent.progress("orchestrator", "Understanding dispute..."));
        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("CUSTOM", node.get("type").asString());
                    assertEquals("progress", node.get("name").asString());
                    assertEquals("orchestrator", node.get("value").get("source").asString());
                    assertEquals("Understanding dispute...", node.get("value").get("text").asString());
                })
                .expectNextCount(1)
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void concurrentEmitsFromTwoThreadsBothAppearInFlux() throws InterruptedException {
        AguiEmitter emitter = new AguiEmitter(objectMapper);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            ready.countDown();
            awaitLatch(go);
            emitter.emit(AguiEvents.CustomEvent.progress("case-review", "Checking transaction status..."));
        });
        Thread t2 = new Thread(() -> {
            ready.countDown();
            awaitLatch(go);
            emitter.emit(AguiEvents.CustomEvent.progress("policy", "Searching policy document..."));
        });

        t1.start();
        t2.start();
        ready.await(2, TimeUnit.SECONDS);
        go.countDown();
        t1.join(2000);
        t2.join(2000);
        emitter.complete("thread-1", "run-1");

        AtomicInteger progressCount = new AtomicInteger();
        StepVerifier.create(emitter.flux())
                .thenConsumeWhile(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    if ("CUSTOM".equals(node.get("type").asString())) {
                        progressCount.incrementAndGet();
                    }
                    return !"RUN_FINISHED".equals(node.get("type").asString());
                })
                // thenConsumeWhile stops as soon as the predicate returns false without
                // consuming that (non-matching) element, so the RUN_FINISHED signal that
                // broke the loop is still pending and must be consumed explicitly here.
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        assertEquals(2, progressCount.get());
    }

    @Test
    void completeEmitsRunFinishedThenTerminatesFlux() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void errorEmitsRunErrorThenTerminatesFlux() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.error("thread-1", "run-1", "Something went wrong");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_ERROR", node.get("type").asString());
                    assertEquals("Something went wrong", node.get("message").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    @Test
    void serializationErrorEmitsRunErrorAndFluxContinues() {
        AguiEmitter emitter = new AguiEmitter(objectMapper);

        emitter.emit(new ExplodingEvent());
        emitter.complete("thread-1", "run-1");

        StepVerifier.create(emitter.flux())
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_ERROR", node.get("type").asString());
                })
                .assertNext(sse -> {
                    JsonNode node = objectMapper.readTree(sse.data());
                    assertEquals("RUN_FINISHED", node.get("type").asString());
                })
                .expectComplete()
                .verify(Duration.ofSeconds(2));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ExplodingEvent(String type) {
        ExplodingEvent() {
            this("EXPLODE");
        }

        @Override
        public String type() {
            throw new IllegalStateException("Deliberate serialization failure for test");
        }
    }
}
