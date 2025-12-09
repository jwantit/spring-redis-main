package com.redis_cache;

import com.redis_cache.SearchKeyword;
import com.redis_cache.SearchKeywordRepository;
import com.redis_cache.SearchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisCacheApplicationTests {

	@Autowired
	private SearchService searchService;

	@Autowired
	private SearchKeywordRepository searchKeywordRepository;

	@Autowired
	private RedisTemplate<String, Object> redisTemplate;

	@Test
	void contextLoads() {
		System.out.println("Spring Boot 애플리케이션 컨텍스트 로딩 테스트");
		assertThat(searchService).isNotNull();
		assertThat(searchKeywordRepository).isNotNull();
		assertThat(redisTemplate).isNotNull();
	}

	@Test
	void 테스트_데이터_생성_및_검증() {
		System.out.println("테스트 데이터 생성 시작");

		// 기존 데이터 정리
		clearAllData();

		// 인기 검색어 테스트 데이터 생성
		String[] popularKeywords = {
				"스프링부트", "자바", "리액트", "파이썬", "자바스크립트",
				"노드", "뷰", "앵귤러", "타입스크립트", "코틀린"
		};

		int[] searchCounts = {50, 45, 40, 35, 30, 25, 20, 15, 10, 5};

		for (int i = 0; i < popularKeywords.length; i++) {
			String keyword = popularKeywords[i];
			int count = searchCounts[i];

			System.out.println("키워드 '" + keyword + "' " + count + "회 검색 시뮬레이션");

			for (int j = 0; j < count; j++) {
				searchService.processSearch(keyword);
			}
		}

		// 최근 검색어용 추가 키워드
		String[] recentKeywords = {
				"도커", "쿠버네티스", "AWS", "마이크로서비스", "GraphQL"
		};

		for (String keyword : recentKeywords) {
			searchService.processSearch(keyword);
			System.out.println("최근 검색어 추가: " + keyword);
		}

		// 검증
		validateTestData(popularKeywords, recentKeywords);

		System.out.println("테스트 데이터 생성 완료");
	}

	@Test
	void Redis_연동_테스트() {
		System.out.println("Redis 연동 테스트 시작");

		clearAllData();

		// 테스트 검색어 처리
		searchService.processSearch("Redis테스트");
		searchService.processSearch("Redis테스트");
		searchService.processSearch("Redis테스트");

		// 인기 검색어 확인
		List<String> popularKeywords = searchService.getPopularKeywords(10);
		assertThat(popularKeywords).contains("Redis테스트");

		// 최근 검색어 확인
		List<String> recentKeywords = searchService.getRecentKeywords(10);
		assertThat(recentKeywords).contains("Redis테스트");

		System.out.println("Redis 연동 테스트 완료");
	}

	@Test
	void MariaDB_연동_테스트() {
		System.out.println("MariaDB 연동 테스트 시작");

		String testKeyword = "MariaDB테스트";

		// 검색 처리
		searchService.processSearch(testKeyword);
		searchService.processSearch(testKeyword);

		// DB 확인
		SearchKeyword savedKeyword = searchKeywordRepository
				.findByKeyword(testKeyword)
				.orElse(null);

		assertThat(savedKeyword).isNotNull();
		assertThat(savedKeyword.getKeyword()).isEqualTo(testKeyword);
		assertThat(savedKeyword.getSearchCount()).isEqualTo(2L);

		System.out.println("MariaDB 연동 테스트 완료");
	}

	private void clearAllData() {
		System.out.println("테스트 데이터 초기화");
		searchService.clearAllCacheFast();
	}

	private void validateTestData(String[] popularKeywords, String[] recentKeywords) {
		// 인기 검색어 검증
		List<String> actualPopular = searchService.getPopularKeywords(10);
		assertThat(actualPopular).isNotEmpty();

		// 최근 검색어 검증
		List<String> actualRecent = searchService.getRecentKeywords(10);
		assertThat(actualRecent).isNotEmpty();

		// DB 검증
		List<SearchKeyword> dbKeywords = searchKeywordRepository.findAll();
		assertThat(dbKeywords.size()).isGreaterThan(0);

		System.out.println("테스트 데이터 검증 완료");
		System.out.println("인기 검색어: " + actualPopular);
		System.out.println("최근 검색어: " + actualRecent);
		System.out.println("DB 키워드 수: " + dbKeywords.size());
	}
}