package com.redis_cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SearchService {

    private final SearchKeywordRepository searchKeywordRepository;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String POPULAR_KEYWORDS_KEY = "popular_keywords";
    private static final String RECENT_KEYWORDS_KEY = "recent_keywords";
    private static final String FAILED_INCREMENTS_DLQ = "failed_increments_dlq";
    private static final long POPULAR_MAX_SIZE = 1_000L;     // 유지하고 싶은 최대 개수
    private static final double POPULAR_MIN_SCORE_THRESHOLD = 2.0; // score 0~2는 쓰레기값으로

    //개선된 processSearch: Redis 우선 처리 (ZSET/LIST) 후, DB는 비동기 처리
    @CacheEvict(cacheNames = "search", allEntries = true)
    public void processSearch(String keyword) {

        // 1. Redis 인기 검색어 업데이트 (ZSET) - Atomic
        updateRealTimeRanking(keyword);

        // 2. Redis 최근 검색어 업데이트 (LIST with MULTI/EXEC) - Strict Atomic
        updateRecentKeywords(keyword);

        // 3. DB 업데이트를 비동기
        // DB 정합성을 확보하며, 실패 시 DLQ에 저장.
        CompletableFuture.runAsync(() -> saveOrUpdateSearchKeywordAsync(keyword))
                .exceptionally(ex -> {
                    log.error("ERROR: Asynchronous DB upsert failed for keyword: {}", keyword, ex);

                    // 비동기 실패 시 DLQ에 저장
                    saveFailedIncrementForRetry(keyword, 1L, ex);

                    return null;
                });
    }

    // 기존 saveOrUpdateSearchKeyword를 비동기 실행에 맞게 트랜잭션 분리
    // 이 메서드는 CompletableFuture 내부에서 실행되므로 @Transactional이 안전하게 작동합니다.
    @Transactional
    public void saveOrUpdateSearchKeywordAsync(String keyword) {
        SearchKeyword searchKeyword = searchKeywordRepository
                .findByKeyword(keyword)
                .orElse(SearchKeyword.builder()
                        .keyword(keyword)
                        .searchCount(0L)
                        .lastSearchedAt(LocalDateTime.now()) // 처음 저장 시 시간 기록
                        .build());

        searchKeyword.incrementSearchCount();
        searchKeyword.setLastSearchedAt(LocalDateTime.now());
        if (searchKeyword.getFirstSearchedAt() == null) {
            searchKeyword.setFirstSearchedAt(LocalDateTime.now());
        }

        searchKeywordRepository.save(searchKeyword);
    }

    // 새로 추가: 단일 실패 키워드를 DLQ에 저장
    private void saveFailedIncrementForRetry(String keyword, Long increment, Throwable ex) {
        try {
            // 단일 키워드는 간단히 "키워드:1" 형태로 저장
            String serializedData = keyword + ":" + increment;

            stringRedisTemplate.opsForList().rightPush(FAILED_INCREMENTS_DLQ, serializedData);

            log.warn("DB 업데이트 실패 데이터가 DLQ({})에 저장됨. Data: {}, Error: {}",
                    FAILED_INCREMENTS_DLQ, serializedData, ex.getMessage());

        } catch (Exception e) {
            log.error("CRITICAL ERROR: Failed to save increment to DLQ.", e);
        }
    }

    @Transactional
    @CacheEvict(cacheNames = "search", allEntries = true)
    public void processSearchBulk(Map<String, Long> increments, List<String> recent) {
        LocalDateTime now = LocalDateTime.now();

        List<SearchKeyword> existList = searchKeywordRepository.findAllByKeywordIn(increments.keySet());
        Map<String, SearchKeyword> existMap = existList.stream()
                .collect(Collectors.toMap(SearchKeyword::getKeyword, k -> k));

        List<SearchKeyword> toSave = new ArrayList<>();
        for (Map.Entry<String, Long> e : increments.entrySet()) {
            String kw = e.getKey();
            long delta = e.getValue();
            SearchKeyword sk = existMap.get(kw);
            if (sk == null) {
                sk = SearchKeyword.builder()
                        .keyword(kw)
                        .searchCount(delta)
                        .firstSearchedAt(now)
                        .lastSearchedAt(now)
                        .build();
            } else {
                sk.setSearchCount(sk.getSearchCount() + delta);
                sk.setLastSearchedAt(now);
                if (sk.getFirstSearchedAt() == null) sk.setFirstSearchedAt(now);
            }
            toSave.add(sk);
        }
        if (!toSave.isEmpty()) {
            searchKeywordRepository.saveAll(toSave);
        }

        stringRedisTemplate.executePipelined((RedisConnection conn) -> {
            var ser = stringRedisTemplate.getStringSerializer();
            byte[] zkey = ser.serialize(POPULAR_KEYWORDS_KEY);
            byte[] lkey = ser.serialize(RECENT_KEYWORDS_KEY);

            for (Map.Entry<String, Long> e : increments.entrySet()) {
                conn.zIncrBy(zkey, e.getValue(), ser.serialize(e.getKey()));
            }
            if (recent != null && !recent.isEmpty()) {
                for (String kw : recent) {
                    conn.lRem(lkey, 0, ser.serialize(kw));
                    conn.lPush(lkey, ser.serialize(kw));
                }
                conn.lTrim(lkey, 0, 9);
            }
            return null;
        });
    }

    //데이터 생성 버튼 클릭 시 빈 캐시 때문에 업데이트 안되는 현상 수정하기 위해 어노테이션 추가
    @CacheEvict(cacheNames = "search", allEntries = true)
    public Map<String, List<String>> fastGenerateAndSnapshot(Map<String, Long> increments, List<String> recent, int limit) {

        //1. Redis 업데이트. ZSET 인기 검색어 점수 증가, LIST 최근 검색어 리스트 업데이트, 즉시 Redis 캐싱 반영
        updateRedisBulkOnly(increments, recent);

        //2. 비동기로 DB 저장
        CompletableFuture.runAsync(() -> upsertDbBulk(increments));

        //3. Redis 스냅샷 (조회)
        Map<String, List<String>> snap = new HashMap<>();
        snap.put("popular", getPopularKeywordsRaw(limit));
        snap.put("recent", getRecentKeywordsRaw(limit));
        return snap;
    }

    private void upsertDbBulk(Map<String, Long> increments) {
        LocalDateTime now = LocalDateTime.now();
        List<SearchKeyword> existList = searchKeywordRepository.findAllByKeywordIn(increments.keySet()); //in 쿼리로 bulk 조회
        Map<String, SearchKeyword> existMap = existList.stream()
                .collect(Collectors.toMap(SearchKeyword::getKeyword, k -> k)); //key는 getKeyword -> "자바", value는 keyword 엔티티 자체
        List<SearchKeyword> toSave = new ArrayList<>();
        for (Map.Entry<String, Long> e : increments.entrySet()) {
            String kw = e.getKey();
            long delta = e.getValue();
            SearchKeyword sk = existMap.get(kw);
            if (sk == null) {
                sk = SearchKeyword.builder()
                        .keyword(kw)
                        .searchCount(delta)
                        .firstSearchedAt(now)
                        .lastSearchedAt(now)
                        .build();
            } else {
                sk.setSearchCount(sk.getSearchCount() + delta);
                sk.setLastSearchedAt(now);
                if (sk.getFirstSearchedAt() == null) sk.setFirstSearchedAt(now);
            }
            toSave.add(sk);
        }
        if (!toSave.isEmpty()) {
            searchKeywordRepository.saveAll(toSave);
        }
    }
    
    //파이프라인은 트랜잭션이 아니다. 파이프라인은 명령을 묶어 전송하지만 여러 명령이 하나의 원자적 묶음으로 처리되지않음
    private void updateRedisBulkOnly(Map<String, Long> increments, List<String> recent) {
        stringRedisTemplate.executePipelined((RedisConnection conn) -> { //실행하는 모든 Redis 명령어를 한 번에 묶어서 서버에 전송하는 기술. 결과도 한번에 받음. 즉 네트워크 비용을 단 1회로 감소 (속도 빨라짐)
            var ser = stringRedisTemplate.getStringSerializer();
            byte[] zkey = ser.serialize(POPULAR_KEYWORDS_KEY); //RedisConnection API는 기본적으로 bytes level로 통신
            byte[] lkey = ser.serialize(RECENT_KEYWORDS_KEY);

            //이 명령은 즉시 네트워크로 보내지지 않고 파이프라인 버퍼에 쌓임
            for (Map.Entry<String, Long> e : increments.entrySet()) {
                conn.zIncrBy(zkey, e.getValue(), ser.serialize(e.getKey())); //zIncrBy : ZSET 사용. conn은 bytes, double, key의 바이트 배열
            }
            if (recent != null && !recent.isEmpty()) {
                for (String kw : recent) {
                    conn.lRem(lkey, 0, ser.serialize(kw)); //중복제거
                    conn.lPush(lkey, ser.serialize(kw)); //맨앞에 추가
                }
                conn.lTrim(lkey, 0, 9);
            }
            return null; //개별 명령의 반환값을 즉시 사용하지 않음. 결과는 executePipelined 가 반환하는 List<Object> 등에 담기지만 null이므로 각 명령의 즉시 응답을 코드에 확인할 수는 없음. 네트워크 왕복이 줄어들어 속도 대폭 향상되긴함
        });
    }

    //Redis 인기 검색어 업데이트
    // ZINCRBY popular_keywords 1 "자바"
    // 예시 데이터(ZSET) : - ("자바", score =5) - ("스프링", score=3)
    private void updateRealTimeRanking(String keyword) {
        stringRedisTemplate.opsForZSet().incrementScore(POPULAR_KEYWORDS_KEY, keyword, 1);
    }
    
    //Redis 최근 검색어 업데이트
    //예시 데이터(LIST) : ["자바", "스프링", "Redis", ...]
    private void updateRecentKeywords(String keyword) {

        stringRedisTemplate.execute((RedisConnection connection) -> {
            connection.multi(); // 트랜잭션 시작

            byte[] keyBytes = stringRedisTemplate.getStringSerializer().serialize(RECENT_KEYWORDS_KEY);
            byte[] valueBytes = stringRedisTemplate.getStringSerializer().serialize(keyword);

            // 1. 중복 제거 (LREM)
            connection.lRem(keyBytes, 0, valueBytes);

            // 2. 가장 앞에 추가 (LPUSH)
            connection.lPush(keyBytes, valueBytes);

            // 3. 최근 10개만 유지 (LTRIM)
            // LTRIM key 0 9
            connection.lTrim(keyBytes, 0, 9);

            return connection.exec(); // 트랜잭션 실행 및 결과 반환
        });
    }

//    @Cacheable(value = "search", key = "'popular_keywords'") //interval로 popular 업데이트하는 로직함수. cacheable이 설정되어 있으면 해당 함수 안타기 때문에 redis가 업데이트 안됨
    public List<String> getPopularKeywords(int limit) {
        try {
            Set<String> keywords = stringRedisTemplate.opsForZSet().reverseRange(POPULAR_KEYWORDS_KEY, 0, limit - 1);
            if (keywords == null) return List.of();
            return new ArrayList<>(keywords);
        } catch (RuntimeException ex) {
            safePurgeCorrupted();
            return List.of();
        }
    }

    //검색 로직에 cache 삭제 기능이 있어서 업데이트 됨 
    @Cacheable(value = "search", key = "'recent_keywords'")
    public List<String> getRecentKeywords(int limit) {
        try {
            List<String> keywords = stringRedisTemplate.opsForList().range(RECENT_KEYWORDS_KEY, 0, limit - 1);
            if (keywords == null) return List.of();
            return keywords;
        } catch (RuntimeException ex) {
            safePurgeCorrupted();
            return List.of();
        }
    }

    public List<String> getPopularKeywordsRaw(int limit) {
        try {
            Set<String> keywords = stringRedisTemplate.opsForZSet().reverseRange(POPULAR_KEYWORDS_KEY, 0, limit - 1);
            if (keywords == null) return List.of();
            return new ArrayList<>(keywords);
        } catch (RuntimeException ex) {
            safePurgeCorrupted();
            return List.of();
        }
    }

    public List<String> getRecentKeywordsRaw(int limit) {
        try {
            List<String> keywords = stringRedisTemplate.opsForList().range(RECENT_KEYWORDS_KEY, 0, limit - 1);
            if (keywords == null) return List.of();
            return keywords;
        } catch (RuntimeException ex) {
            safePurgeCorrupted();
            return List.of();
        }
    }

    public List<String> getPopularKeywordsFromDB(int limit) {
        return searchKeywordRepository.findTop10ByOrderBySearchCountDesc()
                .stream().limit(limit).map(SearchKeyword::getKeyword).collect(Collectors.toList());
    }

    public List<String> getRecentKeywordsFromDB(int limit) {
        return searchKeywordRepository.findTop10ByOrderByLastSearchedAtDesc()
                .stream().limit(limit).map(SearchKeyword::getKeyword).collect(Collectors.toList());
    }

    public Map<String, Object> compareRedisVsDB() {
        long startTime, endTime;
        List<String> redisResult, dbResult;

        startTime = System.currentTimeMillis();
        redisResult = getPopularKeywordsRaw(10);
        endTime = System.currentTimeMillis();
        long redisTime = endTime - startTime;

        startTime = System.currentTimeMillis();
        dbResult = getPopularKeywordsFromDB(10);
        endTime = System.currentTimeMillis();
        long dbTime = endTime - startTime;

        return Map.of(
                "redisResult", redisResult,
                "dbResult", dbResult,
                "redisTime", redisTime + "ms",
                "dbTime", dbTime + "ms",
                "performanceImprovement", String.format("%.2f배", (double) dbTime / Math.max(redisTime, 1))
        );
    }

    @Cacheable(value = "search", key = "'autocomplete::' + #prefix")
    public List<String> getAutoCompleteKeywords(String prefix, int limit) {
        return searchKeywordRepository
                .findByKeywordStartingWithOrderBySearchCountDesc(prefix)
                .stream()
                .limit(limit)
                .map(SearchKeyword::getKeyword)
                .collect(Collectors.toList());
    }

    public SearchStatistics getSearchStatistics() {
        long totalKeywords = searchKeywordRepository.count();
        Long realtimeKeywordCount = stringRedisTemplate.opsForZSet().zCard(POPULAR_KEYWORDS_KEY);
        return SearchStatistics.builder()
                .totalKeywords(totalKeywords)
                .realtimeKeywordCount(realtimeKeywordCount != null ? realtimeKeywordCount : 0L)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class SearchStatistics {
        private Long totalKeywords;
        private Long realtimeKeywordCount;
        private LocalDateTime lastUpdated;
    }

    //캐시 초기화 버튼 클릭 시 빈 캐시 때문에 업데이트 안되는 현상 수정하기 위해 어노테이션 추가
    @CacheEvict(cacheNames = "search", allEntries = true)
    public void clearAllCacheFast() {
        stringRedisTemplate.delete(POPULAR_KEYWORDS_KEY);
        stringRedisTemplate.delete(RECENT_KEYWORDS_KEY);
    }

    public Map<String, Object> getRedisStatus() {
        ZSetOperations<String, String> zops = stringRedisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> popularWithScores = zops.reverseRangeWithScores(POPULAR_KEYWORDS_KEY, 0, -1);
        List<String> recentKeywords = stringRedisTemplate.opsForList().range(RECENT_KEYWORDS_KEY, 0, -1);

        Long popularCount = Long.valueOf(popularWithScores.size());
        Long recentCount = Long.valueOf(recentKeywords.size());

        return Map.of(
                "popularKeywords", popularWithScores != null ? popularWithScores : Set.of(),
                "recentKeywords", recentKeywords != null ? recentKeywords : List.of(),
                "totalPopularCount", popularCount != null ? popularCount : 0L,
                "totalRecentCount", recentCount != null ? recentCount : 0L
        );
    }

    @Transactional // DB 복구 작업 전체에 트랜잭션 적용
    // 이 메서드를 활성화하려면 메인 애플리케이션에 @EnableScheduling 추가 해야함
    @Scheduled(fixedDelay = 300000) // 5분마다 실행
    public void retryFailedDbUpdates() {
        log.info("DLQ 복구 배치 시작.");
        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failedCount = 0;

        // 시스템 과부하를 막기 위해 최대 처리 건수를 제한. (100건)
        final int MAX_ATTEMPTS = 100;

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            // 1. DLQ에서 데이터 안전하게 꺼내기 (LPOP)
            // LPOP을 사용하여 큐의 앞에서부터 데이터를 꺼냄과 동시에 제거.
            String serializedData = stringRedisTemplate.opsForList().leftPop(FAILED_INCREMENTS_DLQ);

            // 큐가 비어있으면 종료
            if (serializedData == null) {
                break;
            }

            try {
                // 2. 데이터 역직렬화 (복원)
                Map<String, Long> increments = deserializeIncrements(serializedData);

                // 3. DB 업데이트 재시도 (기존의 upsertDbBulk를 호출하여 벌크 처리)
                upsertDbBulk(increments);
                successCount++;
                log.info("DLQ 복구 성공: {}", increments);

            } catch (Exception e) {
                // 4. 재시도 실패 처리: 치명적 오류로 간주하고 로그만 남김 (무한 재시도 방지)
                log.error("DLQ 복구 실패 (데이터 손실 가능성): {}", serializedData, e);
                failedCount++;
                // 실패 데이터는 DLQ에서 제거되었으므로, 복구에 계속 실패한다면 별도의 수동 처리가 필요합니다.
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("DLQ 복구 배치 완료. 성공: {}건, 실패: {}건. 소요 시간: {}ms", successCount, failedCount, duration);
    }

     //DLQ에 저장된 String 데이터를 Map<String, Long>으로 역직렬화.
     //DLQ에 저장된 키워드 포맷: "keyword:1" 을 Map으로 복원
    private Map<String, Long> deserializeIncrements(String serializedData) {
        if (serializedData == null || serializedData.isEmpty()) {
            return Collections.emptyMap();
        }

        // 단일 키워드 포맷인 "keyword:1"을 처리
        Map<String, Long> increments = new HashMap<>();
        String[] pair = serializedData.split(":");

        if (pair.length == 2) {
            try {
                // DLQ에 저장된 단일 키워드를 벌크 처리에 맞게 Map<String, Long> 형태로 복원
                increments.put(pair[0].trim(), Long.valueOf(pair[1].trim()));
            } catch (NumberFormatException e) {
                log.error("역직렬화 오류: 유효하지 않은 숫자 형식: {}", pair, e);
            }
        } else {
            log.error("역직렬화 오류: 예상치 못한 DLQ 데이터 형식: {}", serializedData);
        }

        return increments;
    }
    // 10분마다 인기검색어 ZSET 용량/쓰레기값 정리
    @Scheduled(fixedDelay = 1 * 60 * 1000L) // 이전 실행 끝난 시점 기준 10분 후 다시 실행
    public void cleanupPopularKeywords() {

        ZSetOperations<String, String> zops = stringRedisTemplate.opsForZSet();

        // 1) 현재 전체 개수 조회
        Long size = zops.zCard(POPULAR_KEYWORDS_KEY);
        if (size == null) {
            return;
        }

        // 아직 트리거 기준 이하(<= 1000)이면 그냥 둔다
        if (size <= POPULAR_MAX_SIZE) {
            log.debug("popular_keywords cleanup skip: size={} (trigger={})", size, POPULAR_MAX_SIZE);
            return;
        }

        log.info("popular_keywords cleanup start: size={}", size);

        // 2) 먼저 점수 낮은 애들부터 제거 (score 0 ~ POPULAR_MIN_SCORE_THRESHOLD)
        Long removedByScore = zops.removeRangeByScore(POPULAR_KEYWORDS_KEY, 0, POPULAR_MIN_SCORE_THRESHOLD);
        log.info("popular_keywords removed by score threshold: {}", removedByScore);

        // 3) 다시 크기 확인
        size = zops.zCard(POPULAR_KEYWORDS_KEY);
        if (size == null || size <= POPULAR_MAX_SIZE) {
            log.info("popular_keywords cleanup done after score trim: size={}", size);
            return;
        }

        // 4) 그래도 너무 많으면, 하위 랭크부터 잘라내서 maxSize로 맞춘다
        long removeCount = size - POPULAR_MAX_SIZE;
        if (removeCount > 0) {
            // rank 0이 score 가장 낮은 애 → 0 ~ removeCount-1 구간 삭제
            zops.removeRange(POPULAR_KEYWORDS_KEY, 0, removeCount - 1);
            log.info("popular_keywords removed by rank: removeCount={}, newSize={}",
                    removeCount, zops.zCard(POPULAR_KEYWORDS_KEY));
        }
    }

    // 더미데이터 생성용
    public void generateDummyRedisData(int popularCount, int recentCount) {

        // 1) 인기 검색어 더미 (ZSET: popular_keywords)
        stringRedisTemplate.executePipelined((RedisConnection conn) -> {
            var ser = stringRedisTemplate.getStringSerializer();
            byte[] zkey = ser.serialize(POPULAR_KEYWORDS_KEY);

            for (int i = 1; i <= popularCount; i++) {
                String keyword = "dummy_keyword_" + i;
                // 점수는 1 ~ 1000 사이 랜덤
                double score = 1 + (Math.random() * 1000);
                conn.zIncrBy(zkey, score, ser.serialize(keyword));
            }
            return null;
        });

        // 2) 최근 검색어 더미 (LIST: recent_keywords)
        stringRedisTemplate.delete(RECENT_KEYWORDS_KEY); // 기존 것 리셋

        for (int i = 1; i <= recentCount; i++) {
            String keyword = "dummy_recent_" + i;
            stringRedisTemplate.opsForList().leftPush(RECENT_KEYWORDS_KEY, keyword);
        }
        // 최대 10개만 유지 (너 기존 정책 유지)
        stringRedisTemplate.opsForList().trim(RECENT_KEYWORDS_KEY, 0, 9);
    }

    private void safePurgeCorrupted() {
        try {
            stringRedisTemplate.delete(POPULAR_KEYWORDS_KEY);
            stringRedisTemplate.delete(RECENT_KEYWORDS_KEY);
        } catch (DataAccessException ignored) {
        }
    }
}
