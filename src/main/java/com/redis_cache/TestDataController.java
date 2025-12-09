package com.redis_cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestDataController {

    @Autowired
    private SearchService searchService;

    @PostMapping("/generate-data")
    @GetMapping("/generate-data")
    public ResponseEntity<Map<String, Object>> generateTestData() {
        List<String> recent = java.util.List.of("도커", "쿠버네티스", "AWS", "마이크로서비스", "GraphQL");

        Map<String, Long> inc = new java.util.LinkedHashMap<>();
        inc.put("스프링부트", 9L);
        inc.put("자바", 8L);
        inc.put("리액트", 7L);
        inc.put("파이썬", 1L);
        inc.put("자바스크립트", 2L);
        inc.put("노드", 5L);
        inc.put("뷰", 1L);
        inc.put("앵귤러", 2L);
        inc.put("타입스크립트", 3L);
        inc.put("코틀린", 4L);

        Map<String, java.util.List<String>> snap = searchService.fastGenerateAndSnapshot(inc, recent, 10);

        return ResponseEntity.ok(Map.of(
                "message", "테스트 데이터가 생성되었습니다",
                "popular", snap.getOrDefault("popular", java.util.List.of()),
                "recent", snap.getOrDefault("recent", java.util.List.of())
        ));
    }

    @PostMapping("/clear-cache")
    @GetMapping("/clear-cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        searchService.clearAllCacheFast();
        return ResponseEntity.ok(Map.of("message", "캐시가 초기화되었습니다"));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getTestDataStatus() {
        Map<String, Object> status = searchService.getRedisStatus();
        Map<String, Object> result = new java.util.HashMap<>(status);
        result.put("message", "테스트 데이터 상태 확인");
        result.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.ok(result);
    }
}
