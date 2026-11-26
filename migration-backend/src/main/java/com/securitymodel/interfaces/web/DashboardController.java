package com.securitymodel.interfaces.web;

import com.securitymodel.application.dashboard.DashboardOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Workspace overview. Authentication is required by the global filter chain; row visibility
 * is enforced inside {@link DashboardOverviewService} through the same owner scope used by
 * the task list, so a non-admin only ever sees their own aggregates.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardOverviewService dashboardOverviewService;

    public DashboardController(DashboardOverviewService dashboardOverviewService) {
        this.dashboardOverviewService = dashboardOverviewService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "7") int days) {
        return dashboardOverviewService.overview(days);
    }
}
