package com.redis_cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
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

    @CacheEvict(cacheNames = "search", allEntries = true)
    public void processSearch(String keyword) {
        saveOrUpdateSearchKeyword(keyword); //DB 저장
        updateRealTimeRanking(keyword); //Redis ZSET(Sorted Set) 업데이트
        updateRecentKeywords(keyword); //Redis List 업데이트
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
ㅇㅇㅇ
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

    public Map<String, List<String>> fastGenerateAndSnapshot(Map<String, Long> increments, List<String> recent, int limit) {
        updateRedisBulkOnly(increments, recent); //Redis 업데이트. ZSET 인기 검색어 점수 증가, LIST 최근 검색어 리스트 업데이트, 즉시 Redis 캐싱 반영
        CompletableFuture.runAsync(() -> upsertDbBulk(increments)); //비동기로 DB 저장
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

    private void saveOrUpdateSearchKeyword(String keyword) {
        SearchKeyword searchKeyword = searchKeywordRepository
                .findByKeyword(keyword)
                .orElse(SearchKeyword.builder()
                        .keyword(keyword)
                        .searchCount(0L)
                        .build());
        searchKeyword.incrementSearchCount();
        searchKeywordRepository.save(searchKeyword);
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
        stringRedisTemplate.opsForList().remove(RECENT_KEYWORDS_KEY, 0, keyword); //LREM 중복 제거
        stringRedisTemplate.opsForList().leftPush(RECENT_KEYWORDS_KEY, keyword); //LPUSH 가장 앞에 추가 
        stringRedisTemplate.opsForList().trim(RECENT_KEYWORDS_KEY, 0, 9); // LTrim 최근 10개만 유지
    }

    @Cacheable(value = "search", key = "'popular_keywords'")
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

    public void clearAllCacheFast() {
        stringRedisTemplate.delete(POPULAR_KEYWORDS_KEY);
        stringRedisTemplate.delete(RECENT_KEYWORDS_KEY);
    }

    public Map<String, Object> getRedisStatus() {
        ZSetOperations<String, String> zops = stringRedisTemplate.opsForZSet();
        Set<ZSetOperations.TypedTuple<String>> popularWithScores = zops.reverseRangeWithScores(POPULAR_KEYWORDS_KEY, 0, -1); // 내림차순으로 가져옴. 범위 0~-1 : 전체 조회 
        List<String> recentKeywords = stringRedisTemplate.opsForList().range(RECENT_KEYWORDS_KEY, 0, -1); //최근 검색어 최신 순으로 가져옴
        Long popularCount = zops.zCard(POPULAR_KEYWORDS_KEY); //총 원소 개수 조회 
        Long recentCount = stringRedisTemplate.opsForList().size(RECENT_KEYWORDS_KEY); //총 리스트 원소 개수 조회
        return Map.of(
                "popularKeywords", popularWithScores != null ? popularWithScores : Set.of(),
                "recentKeywords", recentKeywords != null ? recentKeywords : List.of(),
                "totalPopularCount", popularCount != null ? popularCount : 0L,
                "totalRecentCount", recentCount != null ? recentCount : 0L
        );
    }

    private void safePurgeCorrupted() {
        try {
            stringRedisTemplate.delete(POPULAR_KEYWORDS_KEY);
            stringRedisTemplate.delete(RECENT_KEYWORDS_KEY);
        } catch (DataAccessException ignored) {
        }
    }
}
