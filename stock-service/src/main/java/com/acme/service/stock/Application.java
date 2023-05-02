package com.acme.service.stock;

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
import java.util.List;
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
    static class StockController {
        private final Supplier<Long> latency = () -> new Random().nextLong(500);

        private final ObservationRegistry registry;

        @PostMapping("/stocks")
        @ResponseStatus(HttpStatus.CREATED)
        public Mono<Stock> allocateStock(@RequestBody Stock stock) {
            var lat = latency.get();
            return Mono.just(stock)
                    .doOnSuccess(o -> log.info("received allocate stock request => {}", stock))
                    .delayElement(Duration.ofMillis(lat))
                    .name("service.stock.allocate")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry));
        }

        @DeleteMapping("/stocks/{id}")
        public Mono<String> cancelStock(@PathVariable("id") String id) {
            var lat = latency.get();
            return Mono.just(id)
                    .doOnSuccess(o -> log.info("received cancel stock request => {}", id))
                    .delayElement(Duration.ofMillis(lat))
                    .name("service.stock.cancel")
                    .tag("latency", lat > 250 ? "high" : "low")
                    .tap(Micrometer.observation(registry));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    static class Stock {
        private String id;
        private String orderNo;
        private Date orderDate;
        private String customerCode;
        private List<StockItem> items;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    static class StockItem {
        private String sku;
        private String barcode;
        private Integer qty;
    }
}
