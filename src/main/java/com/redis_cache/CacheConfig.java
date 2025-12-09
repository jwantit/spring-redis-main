package com.redis_cache;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration //스프링 설정 클래스
@EnableCaching //@Cacheable, @CachePut, @CacheEvict 같은 캐시 시능을 사용할 수 있도록 활성화
public class CacheConfig {

    //RedisTemplate은 Redis와 데이터를 주고받을 때 사용하는 객체
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    //key 와 value 모두 String만 사용할 때 필요한 RedisTemplate
    //직접 문자열 형태로 데이터를 다루고 싶을 때
    //즉, JSON이나 오브젝트 필요없이 문자열만 주고 받을 때 사용하는 템플릿 
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
    
    //Spring Cache(@Cacheable 등)에서 사용할 Redis 캐시 설정
    //cacheManager = Spring의 캐시 기능을 Redis로 저장하도록 해주는 매니저
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10)) //캐시 데이터의 유효시간 10분으로 설정
                .serializeKeysWith( //key 직렬화 StringRedisSerializer
                        org.springframework.data.redis.serializer.RedisSerializationContext
                                .SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith( //value 직렬화 GenericJackson2JsonRedisSerializer
                        org.springframework.data.redis.serializer.RedisSerializationContext
                                .SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }
}