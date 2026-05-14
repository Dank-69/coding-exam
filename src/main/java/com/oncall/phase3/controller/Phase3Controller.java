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
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
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
        model.addAttribute("message", message == null ? "" : message);
        return "v3-chat";
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestParam("message") String message) {
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try {
                agentService.chat(message, List.of(), event -> send(emitter, event));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("Agent stream failed", ex);
                send(emitter, new AgentTraceEvent("error", ex.getMessage() == null ? "internal error" : ex.getMessage()));
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentChatResponse> chatJson(@RequestBody AgentChatRequest request) {
        AgentChatResponse response = agentService.chat(
                request.message(),
                request.history() == null ? List.of() : request.history(),
                event -> { }
        );
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .body(response);
    }

    private void send(SseEmitter emitter, AgentTraceEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event.data()));
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to send SSE event", ex);
        }
    }
}
