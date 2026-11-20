package com.qrpay.common.persistence;

import com.qrpay.common.id.UuidV7;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Ids are assigned by the application (UUIDv7), so Spring Data cannot use "id == null"
 * to detect new entities. Implementing {@link Persistable} avoids a wasted SELECT before
 * every INSERT (which {@code merge()} would otherwise do).
 */
@MappedSuperclass
public abstract class BaseEntity implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    protected UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    protected Instant createdAt;

    @Transient
    private boolean fresh = true;

    protected BaseEntity() {
    }

    protected BaseEntity(Instant now) {
        this.id = UuidV7.next();
        this.createdAt = now;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.fresh = false;
    }
}
