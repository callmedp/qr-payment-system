package com.qrpay.wallet;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByOwnerTypeAndOwnerIdAndCurrency(OwnerType ownerType, UUID ownerId, String currency);

    /**
     * {@code SELECT ... ORDER BY id FOR UPDATE}. Every transfer locks its wallets in the same (id) order,
     * so two concurrent transfers touching the same pair of wallets can never deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.id in :ids order by w.id")
    List<Wallet> lockByIdsInOrder(@Param("ids") Collection<UUID> ids);
}
