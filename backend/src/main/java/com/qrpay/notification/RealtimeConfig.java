package com.qrpay.notification;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
class RealtimeConfig {

    @Bean
    RedisMessageListenerContainer realtimeListenerContainer(RedisConnectionFactory connectionFactory,
                                                            RealtimeBroadcaster broadcaster) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new PatternTopic(RealtimeBroadcaster.CHANNEL_PATTERN));
        return container;
    }
}
