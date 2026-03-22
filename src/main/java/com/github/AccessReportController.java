package com.github;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AccessReportController {
    
    @Autowired
    private GitHubAccessService gitHubService;
    
    @GetMapping("/access-report")
    public Map<String, Object> getAccessReport(
            @RequestParam String org,
            @RequestParam(required = false) String token) {
        
        try {
            // Use provided token or environment variable
            String authToken = token != null ? token : System.getenv("GITHUB_TOKEN");
            
            if (authToken == null || authToken.isEmpty()) {
                return Map.of(
                    "error", "GitHub token not provided",
                    "message", "Set GITHUB_TOKEN environment variable or pass ?token=your_token"
                );
            }
            
            // Fetch the report
            Map<String, Object> report = gitHubService.generateAccessReport(org, authToken);
            return report;
            
        } catch (Exception e) {
            return Map.of(
                "error", "Failed to generate report",
                "details", e.getMessage()
            );
        }
    }
    
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "OK", "service", "GitHub Access Report API");
    }
}
