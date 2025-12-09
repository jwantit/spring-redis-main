package com.redis_cache;

import com.redis_cache.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    @Autowired
    private SearchService searchService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> search(@RequestBody Map<String, String> request) {
        String keyword = request.get("keyword");

        if (keyword == null || keyword.trim().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "검색어를 입력해주세요"));
        }

        searchService.processSearch(keyword.trim());

        // Redis 상태를 함께 반환
        Map<String, Object> redisStatus = searchService.getRedisStatus();

        return ResponseEntity.ok(Map.of(
                "message", "검색이 완료되었습니다",
                "keyword", keyword,
                "redisKeys", Map.of(
                        "popular_keywords", redisStatus.get("popularKeywords"),
                        "recent_keywords", redisStatus.get("recentKeywords")
                )
        ));
    }

    @GetMapping("/popular")
    public ResponseEntity<Map<String, Object>> getPopularKeywords() {
        List<String> popularKeywords = searchService.getPopularKeywords(10);
        Map<String, Object> redisStatus = searchService.getRedisStatus();

        return ResponseEntity.ok(Map.of(
                "keywords", popularKeywords,
                "redisKey", "popular_keywords",
                "redisValue", redisStatus.get("popularKeywords"),
                "totalCount", redisStatus.get("totalPopularCount")
        ));
    }

    @GetMapping("/recent")
    public ResponseEntity<Map<String, Object>> getRecentKeywords() {
        List<String> recentKeywords = searchService.getRecentKeywords(10);
        Map<String, Object> redisStatus = searchService.getRedisStatus();

        return ResponseEntity.ok(Map.of(
                "keywords", recentKeywords,
                "redisKey", "recent_keywords",
                "redisValue", redisStatus.get("recentKeywords"),
                "totalCount", redisStatus.get("totalRecentCount")
        ));
    }

    @GetMapping("/debug/redis-status")
    public ResponseEntity<Map<String, Object>> getRedisStatus() {
        Map<String, Object> status = searchService.getRedisStatus();

        // Key:Value 구조를 명확히 표시
        return ResponseEntity.ok(Map.of(
                "redisData", Map.of(
                        "popular_keywords", Map.of(
                                "type", "SortedSet",
                                "key", "popular_keywords",
                                "value", status.get("popularKeywords"),
                                "count", status.get("totalPopularCount")
                        ),
                        "recent_keywords", Map.of(
                                "type", "List",
                                "key", "recent_keywords",
                                "value", status.get("recentKeywords"),
                                "count", status.get("totalRecentCount")
                        )
                ),
                "totalPopularCount", status.get("totalPopularCount"),
                "totalRecentCount", status.get("totalRecentCount"),
                "popularKeywords", status.get("popularKeywords"),
                "recentKeywords", status.get("recentKeywords")
        ));
    }

    @GetMapping("/compare/redis-vs-db")
    public ResponseEntity<Map<String, Object>> compareRedisVsDB() {
        Map<String, Object> comparison = searchService.compareRedisVsDB();
        Map<String, Object> redisStatus = searchService.getRedisStatus();

        // Redis Key:Value 정보를 포함한 성능 비교
        return ResponseEntity.ok(Map.of(
                "redisResult", comparison.get("redisResult"),
                "dbResult", comparison.get("dbResult"),
                "redisTime", comparison.get("redisTime"),
                "dbTime", comparison.get("dbTime"),
                "performanceImprovement", comparison.get("performanceImprovement"),
                "redisKeyValueData", Map.of(
                        "popular_keywords", redisStatus.get("popularKeywords"),
                        "recent_keywords", redisStatus.get("recentKeywords")
                )
        ));
    }

    // 새로 추가: Redis 모든 키 조회
    @GetMapping("/debug/redis-keys")
    public ResponseEntity<Map<String, Object>> getAllRedisKeys() {
        Map<String, Object> status = searchService.getRedisStatus();

        return ResponseEntity.ok(Map.of(
                "keys", Map.of(
                        "popular_keywords", Map.of(
                                "dataType", "SortedSet (ZSET)",
                                "description", "검색어를 점수(검색횟수)와 함께 저장",
                                "operations", List.of("ZINCRBY", "ZREVRANGE", "ZCARD"),
                                "currentValue", status.get("popularKeywords")
                        ),
                        "recent_keywords", Map.of(
                                "dataType", "List",
                                "description", "최근 검색어를 순서대로 저장 (최대 10개)",
                                "operations", List.of("LPUSH", "LRANGE", "LTRIM"),
                                "currentValue", status.get("recentKeywords")
                        )
                )
        ));
    }
}