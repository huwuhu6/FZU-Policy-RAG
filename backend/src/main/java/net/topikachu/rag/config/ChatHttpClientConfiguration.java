package net.topikachu.rag.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Supplies the OpenAI-compatible Chat API with its own HTTP client settings.
 *
 * <p>The Spring AI 1.1.2 OpenAI auto-configuration still creates the
 * {@code openAiChatModel}; this bean only replaces its conditional
 * {@code OpenAiApi} dependency so both blocking calls and streaming calls use
 * the same explicit Chat-only timeouts.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "openai")
public class ChatHttpClientConfiguration {

    @Bean(name = "dashScopeOpenAiApi")
    @ConditionalOnMissingBean(OpenAiApi.class)
    OpenAiApi dashScopeOpenAiApi(
            OpenAiConnectionProperties commonProperties,
            OpenAiChatProperties chatProperties,
            @Value("${rag.chat.connect-timeout:5s}") Duration connectTimeout,
            @Value("${rag.chat.read-timeout:60s}") Duration readTimeout) {
        String baseUrl = StringUtils.hasText(chatProperties.getBaseUrl())
                ? chatProperties.getBaseUrl()
                : commonProperties.getBaseUrl();
        String apiKey = StringUtils.hasText(chatProperties.getApiKey())
                ? chatProperties.getApiKey()
                : commonProperties.getApiKey();

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(connectTimeout.toMillis()))
                .responseTimeout(readTimeout)
                .doOnConnected(connection -> connection.addHandlerLast(
                        new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS)));

        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory(httpClient);
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder);
        if (StringUtils.hasText(chatProperties.getCompletionsPath())) {
            apiBuilder.completionsPath(chatProperties.getCompletionsPath());
        }
        return apiBuilder.build();
    }
}
