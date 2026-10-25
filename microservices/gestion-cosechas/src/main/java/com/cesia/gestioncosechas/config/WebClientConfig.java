package com.cesia.gestioncosechas.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Value("${app.catalogo-cultivos.base-url}")
    private String catalogoCultivosBaseUrl;

    @Value("${app.catalogo-cultivos.timeout-ms}")
    private int timeoutMs;

    @Bean
    public WebClient catalogoCultivosWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .doOnConnected(conn -> conn.addHandlerLast(
                        new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));

        return WebClient.builder()
                .baseUrl(catalogoCultivosBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
