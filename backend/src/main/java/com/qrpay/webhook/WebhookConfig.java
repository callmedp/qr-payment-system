package com.qrpay.webhook;

import com.qrpay.config.QrPayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
class WebhookConfig {

    /** Tight timeouts: a slow merchant endpoint must not tie up dispatcher threads. */
    @Bean
    RestClient webhookRestClient(QrPayProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.webhook().connectTimeout());
        factory.setReadTimeout(props.webhook().readTimeout());
        return RestClient.builder().requestFactory(factory).defaultHeader("User-Agent", "QrPay-Webhooks/1.0").build();
    }
}
