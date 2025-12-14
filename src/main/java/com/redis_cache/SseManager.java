package com.redis_cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class SseManager {

    // 구독 중인 클라이언트들
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 마지막으로 브로드캐스트했던 TOP10 스냅샷
    private volatile List<String> lastSnapshot;

    // SSE 연결 유지 시간 (30분)
    private static final long TIMEOUT = 30 * 60 * 1000L;

    // 새로운 구독자 등록 + 초깃값 한번 쏴주기
    public SseEmitter subscribe(List<String> initialSnapshot) {
        SseEmitter emitter = new SseEmitter(TIMEOUT);

        emitters.add(emitter);
        log.info("[SSE] 신규 구독자 등록. 현재 구독자 수: {}", emitters.size());

        // 연결 종료/에러시 자동 정리
        emitter.onCompletion(() -> {
            emitters.remove(emitter);
            log.info("[SSE] 구독 종료. 현재 구독자 수: {}", emitters.size());
        });

        emitter.onTimeout(() -> {
            emitters.remove(emitter);
            log.info("[SSE] 구독 타임아웃. 현재 구독자 수: {}", emitters.size());
        });

        emitter.onError(ex -> {
            emitters.remove(emitter);
            log.warn("[SSE] 구독 중 에러 발생. 현재 구독자 수: {}", emitters.size(), ex);
        });

        // 구독 직후, 현재 인기 검색어를 한 번 보내준다 (초기 화면용)
        if (initialSnapshot != null) {
            try {
                log.info("[SSE] 초기 인기 검색어 전송: {}", initialSnapshot);
                emitter.send(SseEmitter.event()
                        .name("popular-init")
                        .data(initialSnapshot));
            } catch (IOException e) {
                emitters.remove(emitter);
                log.warn("[SSE] 초기 데이터 전송 실패로 구독자 제거", e);
            }
        }

        return emitter;
    }

    /**
     * 최신 TOP10을 받아서, 이전 스냅샷과 달라진 경우에만 브로드캐스트
     */
    public void broadcastIfChanged(List<String> latestTop10) {
        if (latestTop10 == null) {
            latestTop10 = List.of();
        }

        // 이전 스냅샷과 동일하면 아무 것도 안 함
        if (Objects.equals(this.lastSnapshot, latestTop10)) {
            log.debug("[SSE] 인기 검색어 TOP10 변경 없음. 브로드캐스트 스킵.");
            return;
        }

        log.info("[SSE] 인기 검색어 TOP10 변경 감지! \n- OLD: {}\n- NEW: {}",
                this.lastSnapshot, latestTop10);

        this.lastSnapshot = List.copyOf(latestTop10);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("popular-update")
                        .data(latestTop10));
            } catch (IOException e) {
                // 전송 실패한 구독자는 제거
                emitters.remove(emitter);
                log.warn("[SSE] 브로드캐스트 중 전송 실패. 구독자 제거. 현재 구독자 수: {}", emitters.size(), e);
            }
        }
    }
}
