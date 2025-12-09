package com.redis_cache;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 검색 키워드 엔티티
 * 사용자가 검색한 키워드와 검색 횟수를 저장합니다. */
@Entity
@Table(name = "search_keywords")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 검색 키워드 ,중복되지 않도록 UNIQUE 제약조건 설정 */
    @Column(nullable = false, unique = true, length = 100)
    private String keyword;

    /** 검색 횟수
     * 기본값 0 */
    @Column(nullable = false)
    @Builder.Default
    private Long searchCount = 0L;

    /** 최초 검색 시간 */
    @Column(name = "first_searched_at")
    private LocalDateTime firstSearchedAt;

    /** 최근 검색 시간 */
    @Column(name = "last_searched_at")
    private LocalDateTime lastSearchedAt;

    /** 검색 횟수 증가 */
    public void incrementSearchCount() {
        this.searchCount++;
        this.lastSearchedAt = LocalDateTime.now();

        if (this.firstSearchedAt == null) {
            this.firstSearchedAt = LocalDateTime.now();
        }
    }
}