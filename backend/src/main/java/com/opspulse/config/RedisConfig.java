package com.opspulse.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RedisConfig {

    /**
     * Everything this app puts in Redis is a short string (a dedup pointer, a
     * rate-limit counter, a JSON event payload), so StringRedisTemplate is all
     * that's needed. Avoiding JdkSerializationRedisSerializer also means keys
     * stay readable in redis-cli while debugging.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }

    /**
     * Subscriber side of the SSE fan-out.
     *
     * SseEmitter objects live in the heap of ONE JVM. With two backend replicas,
     * a browser connected to replica A would never see an incident created on
     * replica B. Every domain event is therefore published to a Redis channel;
     * each replica subscribes here and pushes to its own local emitters.
     * Listeners are registered by IncidentEventPublisher in a later phase.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory cf) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(cf);
        return container;
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
