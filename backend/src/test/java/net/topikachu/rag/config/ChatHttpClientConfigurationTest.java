package net.topikachu.rag.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ChatHttpClientConfigurationTest {

    @Test
    void appliesConfiguredTimeoutsToOpenAiApiRestClient() throws Exception {
        OpenAiConnectionProperties commonProperties = new OpenAiConnectionProperties();
        commonProperties.setBaseUrl("https://example.invalid");
        commonProperties.setApiKey("test-key");

        OpenAiChatProperties chatProperties = new OpenAiChatProperties();
        chatProperties.setCompletionsPath("/v1/chat/completions");

        OpenAiApi api = new ChatHttpClientConfiguration().dashScopeOpenAiApi(
                commonProperties,
                chatProperties,
                Duration.ofSeconds(5),
                Duration.ofSeconds(60));

        RestClient restClient = (RestClient) field(OpenAiApi.class, "restClient").get(api);
        Object requestFactory = field(restClient, "clientRequestFactory").get(restClient);
        ReactorClientHttpRequestFactory reactorFactory = assertInstanceOf(
                ReactorClientHttpRequestFactory.class, requestFactory);

        assertEquals(5000, field(ReactorClientHttpRequestFactory.class, "connectTimeout")
                .get(reactorFactory));
        assertEquals(Duration.ofSeconds(60), field(ReactorClientHttpRequestFactory.class, "readTimeout")
                .get(reactorFactory));
    }

    private Field field(Object targetOrType, String name) throws NoSuchFieldException {
        Class<?> type = targetOrType instanceof Class<?> clazz
                ? clazz
                : targetOrType.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
