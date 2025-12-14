package com.redis_cache;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class SseController {

    private final SseManager sseManager;
    private final SearchService searchService;

    /**
     * 실시간 인기 검색어 SSE 구독 엔드포인트
     */
    @GetMapping(value = "/popular-keywords", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribePopularKeywords() {
        // 현재 TOP10 한 번 가져와서 초깃값으로 밀어 넣어준다
        List<String> currentTop10 = searchService.getPopularKeywordsRaw(10);
        log.info("[SSE] /api/stream/popular-keywords 구독 요청. 초기 TOP10: {}", currentTop10);

        return sseManager.subscribe(currentTop10);
    }
}
