package com.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class GitHubAccessService {
    
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private final OkHttpClient httpClient = new OkHttpClient();
    
    public Map<String, Object> generateAccessReport(String org, String token) throws IOException {
        long startTime = System.currentTimeMillis();
        
        try {
            // Step 1: Fetch all repositories for the organization
            System.out.println("Fetching repositories for org: " + org);
            List<RepositoryInfo> repositories = fetchAllRepositories(org, token);
            System.out.println("Found " + repositories.size() + " repositories");
            
            if (repositories.isEmpty()) {
                return Map.of(
                    "error", "No repositories found",
                    "organization", org
                );
            }
            
            // Step 2: For each repository, fetch collaborators
            System.out.println("Fetching collaborators for each repository...");
            Map<String, Set<String>> userToRepos = new ConcurrentHashMap<>();
            Map<String, String> repoToLanguage = new ConcurrentHashMap<>();
            
            // Process repositories in parallel for efficiency
            repositories.parallelStream().forEach(repo -> {
                try {
                    List<CollaboratorInfo> collaborators = fetchCollaborators(org, repo.name, token);
                    repoToLanguage.put(repo.name, repo.language);
                    
                    // Add repository to each user's access list
                    for (CollaboratorInfo collab : collaborators) {
                        userToRepos.computeIfAbsent(collab.login, k -> new HashSet<>())
                                   .add(repo.name);
                    }
                } catch (IOException e) {
                    System.err.println("Error fetching collaborators for " + repo.name + ": " + e.getMessage());
                }
            });
            
            // Step 3: Build the aggregated report
            Map<String, Object> report = buildReport(userToRepos, repoToLanguage, repositories.size());
            
            // Add metadata
            long endTime = System.currentTimeMillis();
            ((Map<String, Object>) report).put("executionTimeMs", endTime - startTime);
            ((Map<String, Object>) report).put("organization", org);
            ((Map<String, Object>) report).put("totalRepositories", repositories.size());
            ((Map<String, Object>) report).put("totalUsers", userToRepos.size());
            
            return report;
            
        } catch (Exception e) {
            System.err.println("Error generating report: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    private List<RepositoryInfo> fetchAllRepositories(String org, String token) throws IOException {
        List<RepositoryInfo> repos = new ArrayList<>();
        int page = 1;
        
        while (true) {
            String url = GITHUB_API_BASE + "/orgs/" + org + "/repos?page=" + page + "&per_page=100";
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IOException("GitHub API error: " + response.code() + " " + response.message());
                }
                
                String body = response.body().string();
                JsonArray items = JsonParser.parseString(body).getAsJsonArray();
                
                if (items.size() == 0) break;
                
                for (JsonElement item : items) {
                    JsonObject obj = item.getAsJsonObject();
                    String repoName = obj.get("name").getAsString();
                    String language = obj.has("language") && !obj.get("language").isJsonNull()
                            ? obj.get("language").getAsString()
                            : "Unknown";
                    
                    repos.add(new RepositoryInfo(repoName, language));
                }
                
                page++;
            }
        }
        
        return repos;
    }
    
    private List<CollaboratorInfo> fetchCollaborators(String org, String repo, String token) throws IOException {
        List<CollaboratorInfo> collaborators = new ArrayList<>();
        int page = 1;
        
        while (true) {
            String url = GITHUB_API_BASE + "/repos/" + org + "/" + repo + "/collaborators?page=" + page + "&per_page=100";
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "token " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 404 || response.code() == 403) {
                    return collaborators;
                }
                
                if (!response.isSuccessful()) {
                    System.err.println("Warning: " + response.code() + " for " + org + "/" + repo);
                    return collaborators;
                }
                
                String body = response.body().string();
                JsonArray items = JsonParser.parseString(body).getAsJsonArray();
                
                if (items.size() == 0) break;
                
                for (JsonElement item : items) {
                    JsonObject obj = item.getAsJsonObject();
                    
                    // Safe null checks
                    if (obj.has("login") && obj.get("login") != null) {
                        String login = obj.get("login").getAsString();
                        String permission = obj.has("permission") && obj.get("permission") != null 
                            ? obj.get("permission").getAsString() 
                            : "unknown";
                        
                        collaborators.add(new CollaboratorInfo(login, permission));
                    }
                }
                
                page++;
            }
        }
        
        return collaborators;
    }
    
    private Map<String, Object> buildReport(Map<String, Set<String>> userToRepos, 
                                           Map<String, String> repoToLanguage,
                                           int totalRepos) {
        // Handle empty case
        if (userToRepos.isEmpty()) {
            Map<String, Object> emptyReport = new HashMap<>();
            emptyReport.put("status", "success");
            emptyReport.put("message", "No user access data available (repositories may be private or inaccessible)");
            emptyReport.put("userAccess", new HashMap<>());
            emptyReport.put("summary", Map.of(
                "totalUsersWithAccess", 0,
                "averageRepositoriesPerUser", 0
            ));
            return emptyReport;
        }
        
        Map<String, Object> accessMap = new TreeMap<>(); // Sorted for readability
        
        for (Map.Entry<String, Set<String>> entry : userToRepos.entrySet()) {
            String username = entry.getKey();
            Set<String> repos = entry.getValue();
            
            Map<String, Object> userInfo = new HashMap<>();
            userInfo.put("repositories", new ArrayList<>(repos));
            userInfo.put("repositoryCount", repos.size());
            userInfo.put("accessLevel", calculateAccessLevel(repos.size(), totalRepos));
            
            accessMap.put(username, userInfo);
        }
        
        Map<String, Object> report = new HashMap<>();
        report.put("status", "success");
        report.put("userAccess", accessMap);
        report.put("summary", Map.of(
            "totalUsersWithAccess", userToRepos.size(),
            "averageRepositoriesPerUser", 
                userToRepos.values().stream()
                    .mapToInt(Set::size)
                    .average()
                    .orElse(0)
        ));
        
        return report;
    }
    
    private String calculateAccessLevel(int repoCount, int totalRepos) {
        double percentage = (double) repoCount / totalRepos * 100;
        if (percentage >= 80) return "HIGH";
        if (percentage >= 40) return "MEDIUM";
        return "LOW";
    }
}

class RepositoryInfo {
    String name;
    String language;
    
    RepositoryInfo(String name, String language) {
        this.name = name;
        this.language = language;
    }
}

class CollaboratorInfo {
    String login;
    String permission;
    
    CollaboratorInfo(String login, String permission) {
        this.login = login;
        this.permission = permission;
    }
}