package com.qrpay.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
public class KafkaConfig {

    @Bean
    NewTopic paymentEventsTopic(QrPayProperties props) {
        return TopicBuilder.name(props.kafka().paymentEventsTopic())
                .partitions(props.kafka().partitions())
                .replicas(props.kafka().replicationFactor())
                .build();
    }

    /**
     * Default for listeners without their own retry policy (e.g. real-time notifications): three quick
     * in-place retries, then log and move on - a missed toast is not worth blocking the partition for.
     * The webhook dispatcher overrides this with non-blocking retry topics + DLT.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, ex) -> log.error("Dropping record {}-{}@{} after retries: {}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage()),
                new FixedBackOff(1000L, 3));
        handler.addNotRetryableExceptions(JsonProcessingException.class);
        return handler;
    }
}
