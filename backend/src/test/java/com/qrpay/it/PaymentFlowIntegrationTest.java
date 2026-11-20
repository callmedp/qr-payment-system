package com.qrpay.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qrpay.auth.AuthDtos.AuthResponse;
import com.qrpay.auth.AuthDtos.RegisterCustomerRequest;
import com.qrpay.auth.AuthDtos.RegisterMerchantRequest;
import com.qrpay.auth.AuthService;
import com.qrpay.auth.Role;
import com.qrpay.common.error.ApiException;
import com.qrpay.common.error.ErrorCode;
import com.qrpay.outbox.OutboxRepository;
import com.qrpay.outbox.OutboxStatus;
import com.qrpay.payment.PaymentDtos.InitiatePaymentRequest;
import com.qrpay.payment.PaymentDtos.PaymentView;
import com.qrpay.payment.PaymentService;
import com.qrpay.payment.PaymentStatus;
import com.qrpay.qr.QrDtos.CreateDynamicQrRequest;
import com.qrpay.qr.QrDtos.QrView;
import com.qrpay.qr.QrDtos.ResolvedQr;
import com.qrpay.qr.QrService;
import com.qrpay.qr.QrStatus;
import com.qrpay.security.AuthUser;
import com.qrpay.wallet.WalletService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end payment flow against real Postgres, Redis and Kafka (Testcontainers).
 * Run with: ./gradlew :backend:test -Pit
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
class PaymentFlowIntegrationTest {

    private static final long SIGNUP_BONUS = 1_000_000; // matches application.properties

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.9.1");

    @Autowired AuthService authService;
    @Autowired QrService qrService;
    @Autowired PaymentService paymentService;
    @Autowired WalletService walletService;
    @Autowired OutboxRepository outbox;

    @Test
    void customerPaysDynamicQrEndToEnd() throws Exception {
        AuthUser merchant = merchant();
        AuthUser customer = customer();

        QrView qr = qrService.createDynamic(merchant, new CreateDynamicQrRequest(25_000, "order-" + UUID.randomUUID(), 300));
        ResolvedQr resolved = qrService.resolve(qr.payload());
        assertThat(resolved.amount()).isEqualTo(25_000);
        assertThat(resolved.merchantName()).isEqualTo("Chai Point");

        PaymentView created = paymentService.initiate(customer, new InitiatePaymentRequest(qr.id(), null, "tea"), "k-" + UUID.randomUUID());
        assertThat(created.status()).isEqualTo(PaymentStatus.CREATED);

        PaymentView done = paymentService.confirm(customer, created.id(), "1234");

        assertThat(done.status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(walletService.userWallet(customer.userId()).getBalance()).isEqualTo(SIGNUP_BONUS - 25_000);
        assertThat(walletService.merchantWallet(merchant.merchantId()).getBalance()).isEqualTo(25_000);
        assertThat(qrService.get(merchant, qr.id()).status()).isEqualTo(QrStatus.PAID);
        assertCode(() -> qrService.resolve(qr.payload()), ErrorCode.QR_ALREADY_PAID);

        // confirming again is a no-op that returns the same result
        assertThat(paymentService.confirm(customer, created.id(), "1234").status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(walletService.merchantWallet(merchant.merchantId()).getBalance()).isEqualTo(25_000);

        awaitOutboxDrained();
    }

    @Test
    void dynamicQrIsPaidExactlyOnceUnderConcurrency() throws Exception {
        AuthUser merchant = merchant();
        QrView qr = qrService.createDynamic(merchant, new CreateDynamicQrRequest(10_000, null, 300));

        int payers = 8;
        List<AuthUser> customers = new ArrayList<>();
        List<UUID> paymentIds = new ArrayList<>();
        for (int i = 0; i < payers; i++) {
            AuthUser c = customer();
            customers.add(c);
            paymentIds.add(paymentService.initiate(c, new InitiatePaymentRequest(qr.id(), null, null), "k-" + i).id());
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(payers);
        List<Future<String>> outcomes = new ArrayList<>();
        for (int i = 0; i < payers; i++) {
            AuthUser c = customers.get(i);
            UUID paymentId = paymentIds.get(i);
            Callable<String> attempt = () -> {
                start.await();
                try {
                    return paymentService.confirm(c, paymentId, "1234").status().name();
                } catch (ApiException e) {
                    return e.code().name();
                }
            };
            outcomes.add(pool.submit(attempt));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : outcomes) {
            results.add(f.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn("SUCCESS"::equals).hasSize(1);
        assertThat(results).allMatch(r -> r.equals("SUCCESS") || r.equals("FAILED") || r.equals("CONCURRENT_MODIFICATION"));
        assertThat(walletService.merchantWallet(merchant.merchantId()).getBalance()).isEqualTo(10_000);
        long totalCustomerBalance = customers.stream().mapToLong(c -> walletService.userWallet(c.userId()).getBalance()).sum();
        assertThat(totalCustomerBalance).isEqualTo(payers * SIGNUP_BONUS - 10_000);
    }

    @Test
    void insufficientBalanceFailsPaymentAndLeavesQrPayable() {
        AuthUser merchant = merchant();
        AuthUser customer = customer();
        QrView qr = qrService.createDynamic(merchant, new CreateDynamicQrRequest(SIGNUP_BONUS + 1, null, 300));
        PaymentView created = paymentService.initiate(customer, new InitiatePaymentRequest(qr.id(), null, null), "k-1");

        PaymentView result = paymentService.confirm(customer, created.id(), "1234");

        assertThat(result.status()).isEqualTo(PaymentStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(walletService.userWallet(customer.userId()).getBalance()).isEqualTo(SIGNUP_BONUS);
        assertThat(qrService.get(merchant, qr.id()).status()).isEqualTo(QrStatus.ACTIVE); // claim rolled back
    }

    @Test
    void wrongPinDoesNotMoveMoneyAndPaymentStaysPayable() {
        AuthUser merchant = merchant();
        AuthUser customer = customer();
        QrView qr = qrService.createDynamic(merchant, new CreateDynamicQrRequest(5_000, null, 300));
        PaymentView created = paymentService.initiate(customer, new InitiatePaymentRequest(qr.id(), null, null), "k-1");

        assertCode(() -> paymentService.confirm(customer, created.id(), "9999"), ErrorCode.INVALID_PIN);
        assertThat(paymentService.get(customer, created.id()).status()).isEqualTo(PaymentStatus.CREATED);

        assertThat(paymentService.confirm(customer, created.id(), "1234").status()).isEqualTo(PaymentStatus.SUCCESS);
    }

    @Test
    void staticQrRequiresAmountAndAcceptsManyPayments() {
        AuthUser merchant = merchant();
        QrView staticQr = qrService.getOrCreateStatic(merchant);
        AuthUser c1 = customer();
        AuthUser c2 = customer();

        assertCode(() -> paymentService.initiate(c1, new InitiatePaymentRequest(staticQr.id(), null, null), "k"),
                ErrorCode.AMOUNT_REQUIRED);

        paymentService.confirm(c1, paymentService.initiate(c1, new InitiatePaymentRequest(staticQr.id(), 1_000L, null), "a").id(), "1234");
        paymentService.confirm(c2, paymentService.initiate(c2, new InitiatePaymentRequest(staticQr.id(), 2_000L, null), "b").id(), "1234");

        assertThat(walletService.merchantWallet(merchant.merchantId()).getBalance()).isEqualTo(3_000);
    }

    // ------------------------------------------------------------------ helpers

    private AuthUser merchant() {
        AuthResponse r = authService.registerMerchant(new RegisterMerchantRequest("Mira Shah",
                "m-" + UUID.randomUUID() + "@test.dev", "password123", "Chai Point", "5812"));
        return new AuthUser(r.user().id(), r.user().email(), Role.MERCHANT, r.user().merchantId());
    }

    private AuthUser customer() {
        AuthResponse r = authService.registerCustomer(new RegisterCustomerRequest("Ravi Kumar",
                "c-" + UUID.randomUUID() + "@test.dev", "password123", "1234"));
        return new AuthUser(r.user().id(), r.user().email(), Role.CUSTOMER, null);
    }

    private void awaitOutboxDrained() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (outbox.findAll().stream().allMatch(e -> e.getStatus() == OutboxStatus.PUBLISHED)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Outbox events were not published to Kafka");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(expected));
    }
}
