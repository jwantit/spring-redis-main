package com.redis_cache;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SearchKeywordRepository extends JpaRepository<SearchKeyword, Long> {
    Optional<SearchKeyword> findByKeyword(String keyword);

    @Query("SELECT sk FROM SearchKeyword sk WHERE sk.keyword LIKE CONCAT(:prefix, '%') ORDER BY sk.searchCount DESC")
    List<SearchKeyword> findByKeywordStartingWithOrderBySearchCountDesc(@Param("prefix") String prefix);

    List<SearchKeyword> findAllByKeywordIn(Collection<String> keywords);

    List<SearchKeyword> findTop10ByOrderBySearchCountDesc();

    List<SearchKeyword> findTop10ByOrderByLastSearchedAtDesc();
}
