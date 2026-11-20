package com.qrpay.outbox;

import com.qrpay.config.QrPayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls PENDING outbox rows and publishes them to Kafka (at-least-once). A row is marked PUBLISHED only
 * after the broker acknowledged it (acks=all). If the app crashes after the send but before the commit,
 * the event is sent again on the next tick - consumers deduplicate by eventId.
 *
 * <p>Production alternative: Debezium CDC tailing the Postgres WAL, which removes the polling latency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final QrPayProperties props;

    @Scheduled(fixedDelayString = "${qrpay.outbox.poll-interval-ms}", initialDelay = 2000)
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outbox.lockPendingBatch(props.outbox().batchSize());
        if (batch.isEmpty()) {
            return;
        }
        // send the whole batch asynchronously, then wait - the producer preserves per-partition order
        List<CompletableFuture<SendResult<String, String>>> sends = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.getTopic(), event.getPartitionKey(), event.getPayload());
            record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
            record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
            sends.add(kafka.send(record));
        }
        Instant now = Instant.now();
        int published = 0;
        for (int i = 0; i < batch.size(); i++) {
            OutboxEvent event = batch.get(i);
            try {
                sends.get(i).get(10, TimeUnit.SECONDS);
                event.markPublished(now);
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                event.recordFailure("interrupted");
                break;
            } catch (Exception e) {
                event.recordFailure(e.getMessage());
                log.warn("Outbox publish failed for event {} (attempt {}): {}", event.getId(), event.getAttempts(),
                        e.getMessage());
            }
        }
        log.debug("Outbox relay published {}/{} events", published, batch.size());
    }

    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgePublished() {
        int deleted = outbox.deletePublishedBefore(Instant.now().minus(props.outbox().retention()));
        log.info("Purged {} published outbox events", deleted);
    }
}
