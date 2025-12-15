# Spring Redis 기반 실시간 검색 시스템 개선 프로젝트

## 📌 프로젝트 개요 

- **프로젝트명**: Spring Redis 기반 검색 시스템 개선
- **팀명**: 풀스택 프로젝트 3조
- **개발 기간**: 2025.12.08 ~ 2025.12.14
- **프로젝트 정리 노션** : **[Spring Redis 프로젝트 노션 페이지](https://www.notion.so/Spring-Redis-2c633876ad38805fb459e1fc2064fe4c)**
- **팀 구성**
  - 김지원 (팀장)
  - 조준환
  - 전하성
  - 이건호

본 프로젝트는 **Redis 기술을 익히고** Spring Boot와 연동하는 것을 기반으로, 기존 검색 시스템에서 발생하던 **성능 저하**, **Race Condition**, **DB-Redis 정합성 문제**, **캐싱 지연**, **데이터 무한 증가 문제**를 해결하여 **Redis 기반의 안정적인 실시간 검색 시스템**을 구축하는 것을 목표로 진행되었습니다.

---

## 🎯 프로젝트 목표

- Redis Atomic 연산을 활용한 **동시성 문제 해결**
- DB와 Redis 간 **정합성 보장**
- 검색 트래픽 증가에도 안정적인 **고성능 구조 설계**
- 실시간 인기 검색어 갱신 구조 개선 **(Polling → SSE)**

---

## 🚀 성능 개선 요약

| 구분 | 개선 전 | 개선 후 | 효과 |
|------|--------|--------|------|
| 최근 검색어 처리 | LREM → LPUSH → LTRIM 개별 실행 | MULTI / EXEC 트랜잭션 처리 | Race Condition 제거 |
| 동시성 안정성 | 동시 요청 시 중복·순서 꼬임 | Atomic 단위 처리 | 고동시성 환경에서도 안정 |
| DB 처리 방식 | 검색 시 DB 동기 저장 | DB 비동기 처리 | 응답 지연 제거 |
| DB 실패 대응 | Redis만 반영되어 정합성 붕괴 | Redis DLQ 적재 + Scheduler 재처리 | 정합성 보장 |
| 인기 검색어(ZSET) | 데이터 무한 증가 | Top-N(최대 1000개) 유지 | 메모리·성능 안정화 |
| ZSET 조회 성능 | 10만 건 기준 약 301ms | 동일 조건 40ms | 약 7.5배 개선 |
| 캐싱 전략 | @Cacheable 사용 | Redis 직접 조회 | 실시간 반영 확보 |
| 실시간 갱신 방식 | 3초 주기 Polling | SSE 기반 Push | 불필요한 요청 제거 |
| 네트워크 부하 | 지속적 API 호출 | 변경 발생 시에만 전송 | 서버 부하 감소 |
| 사용자 경험 | 잦은 갱신·불안정 | 즉각적·안정적 반영 | UX 향상 |

---

## 🧑‍🤝‍🧑 팀원 역할

| 이름 | 역할 | 주요 담당 |
| --- | --- | --- |
| 김지원 | 팀장 | 프로젝트 총괄 및 역할 분배<br>Redis 기능 분석 및 구조 개선<br>Race Condition 해결 <br>DB 정합성 개선 및 데이터 일관성 확성 <br>플로우 차트·문서화·노션 정리 |
| 조준환 | 팀원 | Race Condition 해결<br>Polling방식 실시간 갱신을 SSE 구조로 전환<br>ZSET 인기검색어 용량 관리 적용으로 조회 성능·시간복잡도 개선<br>플로우 차트·문서화 |
| 전하성 | 팀원 | Polling방식 3초간 갱신 방식을 SSE 단방향 구조로 전환<br>Spring-Cache @Cacheable @CacheEvict 개선을 통해 효율화<br>플로우 차트 (Redis 상태확인) |
| 이건호 | 팀원 |  |

---

## 🛠 기술 스택

### Front-end
- JavaScript

### Back-end
- Java 21
- Spring Boot
- Spring Web
- Spring Data Redis
- Spring Cache

### Infra
- Redis
- Docker
- Scheduler (@Scheduled)

---
## Flow Chart

### 기존 프로젝트
<img width="8231" height="9857" alt="기존_flow" src="https://github.com/user-attachments/assets/64409ce5-8793-428b-bea8-38d78c8a0202" />


### 개선 프로젝트
<img width="9360" height="10185" alt="개선_flow" src="https://github.com/user-attachments/assets/b4636179-561e-48ec-83e9-5a45cc4071fd" />

---

## ⚠️ 기존 문제점 & 개선 사항

### 1️⃣ Race Condition 발생

**문제**
- 최근 검색어 업데이트 시 `LREM → LPUSH → LTRIM` 순차 실행
- 동시 요청에서 리스트 중복 및 순서 꼬임 발생
```java
stringRedisTemplate.opsForList().remove(RECENT_KEYWORDS_KEY, 0, keyword);
stringRedisTemplate.opsForList().leftPush(RECENT_KEYWORDS_KEY, keyword
stringRedisTemplate.opsForList().trim(RECENT_KEYWORDS_KEY, 0, 9);
});
```

**개선**
- Redis **MULTI / EXEC 트랜잭션** 적용
- 최근 검색어 업데이트를 하나의 Atomic 작업으로 처리
- Race Condition 완전 해결
```java
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
```

---

### 2️⃣ DB 정합성 문제 & 느린 동기 처리

**문제**
- 검색 요청 시 DB 저장을 동기로 처리
- DB 실패 시 Redis만 반영되어 정합성 붕괴
- 실패 데이터 복구 불가
```java
@CacheEvict(cacheNames = "search", allEntries = true)
public void processSearch(String keyword) {
    saveOrUpdateSearchKeyword(keyword); //DB 저장
    updateRealTimeRanking(keyword); //Redis ZSET(Sorted Set) 업데이트
    updateRecentKeywords(keyword); //Redis List 업데이트
}
```

**개선**
- DB 저장 **비동기 처리 (CompletableFuture)**
- DB 실패 시 Redis 기반 **DLQ(Dead Letter Queue)** 적재
- Scheduler를 통한 DLQ 재처리
- Redis를 선반영하여 **실시간성 & UX 개선**
```java
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
```
```java
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
```

---

### 3️⃣ ZSET 무한 증가로 인한 성능 저하

**문제**
- 인기 검색어 ZSET 데이터 무한 증가
- 데이터 10만 건 이상 시 응답 시간 급격히 증가

**개선**
- **Top-N 유지 전략** 적용
- `ZREMRANGEBYRANK`를 활용해 하위 데이터 정리
- 최대 1000개까지만 유지

**테스트 결과**
- 개선전
<img width="690" height="193" alt="기존_image" src="https://github.com/user-attachments/assets/bdeaa5c5-218d-46f9-bd42-e7deae226a05" />

- 개선 후
<img width="706" height="171" alt="개선_image" src="https://github.com/user-attachments/assets/911a4a63-28aa-40bd-ada8-ea9a91055f8e" />

- 응답 시간 **301ms → 40ms (약 7.5배 개선)**

---

### 4️⃣ @Cacheable로 인한 실시간 반영 실패

**문제**
- 인기 검색어 조회에 @Cacheable 사용
- Redis 최신 데이터 반영 불가
```java
 @Cacheable(value = "search", key = "'popular_keywords'") 
public List<String> getPopularKeywords(int limit) {..}
```

**개선**
- @Cacheable 제거
- Redis 직접 조회로 실시간성 확보
```java
public List<String> getPopularKeywords(int limit) {...}
```

---

### 5️⃣ 실시간 인기 검색어 갱신 구조 개선 (Polling → SSE)

**기존**
- 3초 주기 Polling 방식
- 불필요한 API 호출 및 네트워크/서버 부하
- 의미 없는 화면 갱신 반복
```javaScript
(async function init() {

await loadKeywords();

setInterval(updatePopularKeywords, 3000);
```

**개선**
- **SSE(Server-Sent Events)** 기반 Push 구조 도입
- 인기 검색어 **순위 변경 발생 시에만 이벤트 전송**
- Polling 완전 제거

  **1) SSE 구독 엔드포인트 추가**
  [**SseController**]: 클라이언트가 SSE로 연결 -> 초기 접속 시 현재 TOP10을 한 번 전송
  ```java
  @GetMapping(value = "/popular-keywords", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter subscribePopularKeywords() {
      // 현재 TOP10 한 번 가져와서 초기값으로 밀어 넣어준다
      List<String> currentTop10 = searchService.getPopularKeywordsRaw(10);
      return sseManager.subscribe(currentTop10);
  }
  ```
  **2) 인기 검색어 변경 감지 로직 추가**
  [**SearchService**]: Redis ZSET 기준 최신 TOP10 조회 -> SSE 매니저에 변경 여부 판단 위임 (SseManager.broadcastIfChanged()호출)
  ```java
  private void notifyPopularChangeIfNeeded() {
    try {
        List<String> latestTop10 = getPopularKeywordsRaw(10); // 이미 있는 메서드 활용
        sseManager.broadcastIfChanged(latestTop10);
    } catch (Exception ex) {
        log.warn("[SSE] 인기 검색어 변경 알림 중 예외 발생.", ex);
    }}
  ```
  **3) 변경 발생 시에만 이벤트 전송**
  [SseManager]: 이전 스냅샷과 비교 -> 순위 변경이 있을 때만 SSE 이벤트 전송
  ```java
  public void broadcastIfChanged(List<String> latestTop10) {
    if (latestTop10 == null) {
        latestTop10 = List.of();
    }
    // 이전 스냅샷과 동일하면 아무것도 안 함
    if (Objects.equals(this.lastSnapshot, latestTop10)) {
        return;
    }
    this.lastSnapshot = List.copyOf(latestTop10);

    for (SseEmitter emitter : emitters) {
        try {
            emitter.send(SseEmitter.event()
                    .name("popular-update")
                    .data(latestTop10));
        } catch (IOException e) {
            // 전송 실패한 구독자는 제거
            emitters.remove(emitter);
        }
    }
  ```

  **4) 프론트엔드 Polling 제거 → SSE 전환**
  - [**script.js**]
    - setInterval() 기반 Polling 제거
    - 서버 Push 이벤트 기반 화면 갱신
  ```javascript
  popularEventSource = new EventSource("/api/stream/popular-keywords");
  popularEventSource.addEventListener("popular-update", (event) => {
      const data = JSON.parse(event.data);
      displayKeywords("popularKeywords", Array.isArray(data) ? data : []);
  });
  ```

**효과**
- 서버/네트워크 부하 감소
- UX 안정성 향상
- 다중 사용자 실시간 동기화 구현

---

## 🔄 개선된 검색 처리 흐름

1. Redis ZSET 인기 검색어 업데이트 (Atomic)
2. Redis List 최근 검색어 업데이트 (MULTI / EXEC)
3. DB 저장 비동기 처리
4. DB 실패 시 DLQ 적재
5. Scheduler가 DLQ 재처리
6. Redis-DB 정합성 유지

---

## 🧠 프로젝트 회고

본 프로젝트는 Redis 기술을 깊이 있게 이해하고 활용하는 과정이었습니다. 특히, 실무에 가까운 비동기 처리, DLQ 기반의 실패 데이터 관리, SSE를 통한 실시간 이벤트 Push 구조를 직접 설계하고 구현하면서 DB/Redis 간의 데이터 정합성을 고려한 안정적인 시스템 설계 경험을 축적할 수 있었습니다. 구조적 취약점을 해결하고 성능을 최적화하는 데 성공한 의미 있는 프로젝트였습니다.

---

## 📄 라이선스 및 기반 코드

이 프로젝트는 하기 소스코드를 기반으로 제작 및 개선 되었습니다. <br>
(https://github.com/excelh11/spring_redis)
![구현화면](https://github.com/user-attachments/assets/bfe18443-79ce-4f31-b0dd-a4eb85c8f2b6)


