package com.oncall.phase3.controller;

import com.oncall.phase3.model.AgentChatRequest;
import com.oncall.phase3.model.AgentChatResponse;
import com.oncall.phase3.model.AgentTraceEvent;
import com.oncall.phase3.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Controller
@RequestMapping("/v3")
public class Phase3Controller {
    private static final Logger log = LoggerFactory.getLogger(Phase3Controller.class);
    private final AgentService agentService;

    public Phase3Controller(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public String page(@RequestParam(name = "message", required = false) String message, Model model) {
        log.info("GET /v3 page messagePresent={}", message != null && !message.isBlank());
        model.addAttribute("message", message == null ? "" : message);
        return "v3-chat";
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public Flux<ServerSentEvent<String>> chatStream(@RequestParam("message") String message) {
        long start = System.currentTimeMillis();
        String logQuery = logMessage(message);
        log.info("GET /v3/chat stream start q='{}'", logQuery);
        return Flux.<AgentTraceEvent>create(fluxSink -> {
            CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                try {
                    agentService.chat(message, List.of(), event -> {
                        if (!fluxSink.isCancelled()) {
                            fluxSink.next(event);
                        }
                    });
                    if (!fluxSink.isCancelled()) {
                        fluxSink.complete();
                    }
                } catch (Exception ex) {
                    log.warn("Agent stream failed", ex);
                    if (!fluxSink.isCancelled()) {
                        fluxSink.next(new AgentTraceEvent("error", ex.getMessage() == null ? "internal error" : ex.getMessage()));
                        fluxSink.complete();
                    }
                }
            });
            fluxSink.onCancel(() -> task.cancel(true));
        })
                .doFinally(signalType -> log.info("GET /v3/chat stream end q='{}' signal={} took={}ms",
                        logQuery, signalType, System.currentTimeMillis() - start))
                .map(this::toSseEvent);
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentChatResponse> chatJson(@RequestBody AgentChatRequest request) {
        long start = System.currentTimeMillis();
        int historySize = request.history() == null ? 0 : request.history().size();
        String logQuery = logMessage(request.message());
        log.info("POST /v3/chat json start q='{}' history={}", logQuery, historySize);
        AgentChatResponse response = agentService.chat(
                request.message(),
                request.history() == null ? List.of() : request.history(),
                event -> { }
        );
        log.info("POST /v3/chat json end q='{}' toolCalls={} totalTokens={} took={}ms",
                logQuery, toolCallCount(response), response.totalTokens(), System.currentTimeMillis() - start);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response);
    }

    private ServerSentEvent<String> toSseEvent(AgentTraceEvent event) {
        String type = event.type() == null || event.type().isBlank() ? "message" : event.type();
        return ServerSentEvent.builder(event.data() == null ? "" : event.data())
                .event(type)
                .build();
    }

    private int toolCallCount(AgentChatResponse response) {
        return response.toolCalls() == null ? 0 : response.toolCalls().size();
    }

    private String logMessage(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120) + "...";
    }
}
