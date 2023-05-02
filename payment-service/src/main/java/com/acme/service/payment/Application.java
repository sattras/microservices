package com.acme.service.payment;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.ObservationTextPublisher;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Date;
import java.util.Random;
import java.util.function.Supplier;


@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Configuration
    class ObservationConfig {
        @Bean
        public ObservationTextPublisher printingObservationHandler() {
            return new ObservationTextPublisher();
        }
    }

    @RestController
    @RequiredArgsConstructor
    @Slf4j
    static class PaymentController {
        private final Supplier<Long> latency = () -> new Random().nextLong(500);

        private final ObservationRegistry registry;

        @PostMapping("/payments")
        @ResponseStatus(HttpStatus.CREATED)
        public Mono<Payment> createPayment(@RequestBody Payment payment) {
            var lat = latency.get();
            return Mono.just(payment)
                    .doOnSuccess(o -> log.info("received create payment request => {}", payment))
                    .delayElement(Duration.ofMillis(lat))
                    .name("service.payment.create")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry));
        }

        @DeleteMapping("/payments/{id}")
        public Mono<String> cancelPayment(@PathVariable("id") String id) {
            var lat = latency.get();
            return Mono.just(id)
                    .doOnSuccess(o -> log.info("received cancel payment request => {}", id))
                    .delayElement(Duration.ofMillis(lat))
                    .name("service.payment.cancel")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    static class Payment {
        private String id;
        private String paymentNo;
        private Date paymentDate;
        private String customerCode;
        private String refNo;
        private Double amount;
    }
}
