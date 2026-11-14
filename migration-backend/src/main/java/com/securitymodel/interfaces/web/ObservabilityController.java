package com.securitymodel.interfaces.web;

import com.securitymodel.application.observability.ObservabilitySummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Admin-only aggregate metrics; see R5-04/R5-05. */
@RestController
@RequestMapping("/api/observability")
public class ObservabilityController {

    private final ObservabilitySummaryService summaryService;

    public ObservabilityController(ObservabilitySummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return summaryService.summary();
    }
}
