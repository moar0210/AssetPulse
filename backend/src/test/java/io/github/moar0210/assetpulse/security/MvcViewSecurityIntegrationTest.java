package io.github.moar0210.assetpulse.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.alerts.AlertChangeEvent;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.support.HandlerMethodReturnValueHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityReturnValueHandler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;
import org.springframework.web.servlet.view.BeanNameViewResolver;
import org.springframework.web.servlet.view.ContentNegotiatingViewResolver;
import org.springframework.web.servlet.view.FragmentsRendering;
import org.springframework.web.servlet.view.InternalResourceView;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.JstlView;
import org.springframework.web.servlet.view.UrlBasedViewResolver;
import org.springframework.web.servlet.view.ViewResolverComposite;
import org.springframework.web.servlet.view.xslt.XsltView;
import org.springframework.web.servlet.view.xslt.XsltViewResolver;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MvcViewSecurityIntegrationTest {

    private static final String STATIC_ERROR_VIEW_CLASS =
            "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$StaticView";

    @Container
    private static final PostgreSQLContainer<?> POSTGRESQL =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17.10-alpine"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private WebApplicationContext applicationContext;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping requestMappings;

    @Autowired private RequestMappingHandlerAdapter requestAdapter;
    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("AUTH-04: the configured MVC view graph cannot render an XSLT view")
    void configuredViewResolversAndViewBeansExcludeXslt() {
        Set<ViewResolver> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var resolvers = applicationContext.getBeansOfType(ViewResolver.class);
        assertThat(resolvers).isNotEmpty();
        resolvers.values().forEach(resolver -> assertNoXsltResolver(resolver, visited));
        applicationContext.getBeansOfType(View.class).values().forEach(this::assertNoXsltView);
    }

    @Test
    @DisplayName("AUTH-04: application mappings write JSON or SSE instead of implicit view names")
    void everyApplicationHandlerUsesAResponseBodyReturnHandler() {
        assertThat(requestAdapter.getClass()).isEqualTo(RequestMappingHandlerAdapter.class);
        var applicationHandlers =
                requestMappings.getHandlerMethods().entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue()
                                                .getBeanType()
                                                .getPackageName()
                                                .startsWith("io.github.moar0210.assetpulse"))
                        .toList();
        assertThat(applicationHandlers).isNotEmpty();
        List<HandlerMethodReturnValueHandler> returnHandlers =
                requestAdapter.getReturnValueHandlers();
        assertThat(returnHandlers).isNotNull();

        for (var entry : applicationHandlers) {
            var handler = entry.getValue();
            assertThat(
                            AnnotatedElementUtils.hasAnnotation(
                                            handler.getBeanType(), ResponseBody.class)
                                    || handler.hasMethodAnnotation(ResponseBody.class))
                    .as("response body for %s", handler)
                    .isTrue();
            assertThat(entry.getKey().getPatternValues())
                    .as("bounded mapping for %s", handler)
                    .noneMatch(pattern -> pattern.contains("*"));

            var selectedHandler =
                    returnHandlers.stream()
                            .filter(
                                    candidate ->
                                            candidate.supportsReturnType(handler.getReturnType()))
                            .findFirst()
                            .orElseThrow();
            assertThat(selectedHandler.getClass())
                    .as("first MVC return handler for %s", handler)
                    .isIn(
                            RequestResponseBodyMethodProcessor.class,
                            HttpEntityMethodProcessor.class,
                            ResponseEntityReturnValueHandler.class,
                            ResponseBodyEmitterReturnValueHandler.class);
        }
    }

    @Test
    @DisplayName(
            "AUTH-04, ALR-03: the configured SSE endpoint writes typed JSON without view fragments")
    void configuredSseEndpointUsesTheJsonEmitterHandlerAndConverter() {
        var streams =
                requestMappings.getHandlerMethods().entrySet().stream()
                        .filter(
                                entry ->
                                        entry
                                                        .getKey()
                                                        .getProducesCondition()
                                                        .getProducibleMediaTypes()
                                                        .stream()
                                                        .anyMatch(
                                                                MediaType.TEXT_EVENT_STREAM
                                                                        ::isCompatibleWith)
                                                || (entry.getValue()
                                                                .getBeanType()
                                                                .getPackageName()
                                                                .startsWith(
                                                                        "io.github.moar0210.assetpulse")
                                                        && selectedReturnHandler(
                                                                                entry.getValue()
                                                                                        .getReturnType())
                                                                        .getClass()
                                                                == ResponseBodyEmitterReturnValueHandler
                                                                        .class))
                        .toList();
        assertThat(streams).hasSize(1);
        var stream = streams.getFirst();
        assertThat(stream.getKey().getPatternValues()).containsExactly("/api/v1/alerts/stream");
        MethodParameter returnType = stream.getValue().getReturnType();
        assertJsonSseReturnType(returnType);

        var selectedHandler = selectedReturnHandler(returnType);
        assertThat(selectedHandler.getClass())
                .isEqualTo(ResponseBodyEmitterReturnValueHandler.class);
        Object configured = ReflectionTestUtils.getField(selectedHandler, "sseMessageConverters");
        assertThat(configured).isInstanceOf(List.class);
        List<?> converters = (List<?>) configured;
        assertThat(converters).allMatch(HttpMessageConverter.class::isInstance);
        for (Class<?> payloadType : List.of(Map.class, AlertChangeEvent.class)) {
            var selectedConverter =
                    converters.stream()
                            .map(converter -> (HttpMessageConverter<?>) converter)
                            .filter(
                                    converter ->
                                            converter.canWrite(
                                                    payloadType, MediaType.APPLICATION_JSON))
                            .findFirst()
                            .orElseThrow();
            assertThat(selectedConverter.getClass())
                    .as("first SSE JSON converter for %s", payloadType.getName())
                    .isEqualTo(MappingJackson2HttpMessageConverter.class);
        }
    }

    @Test
    void sseReturnTypeGuardRejectsViewAndFragmentSignatures() throws Exception {
        for (String method : List.of("fragments", "modelAndView", "view", "untyped")) {
            MethodParameter returnType =
                    new MethodParameter(UnsafeSseSignatures.class.getDeclaredMethod(method), -1);
            assertThatThrownBy(() -> assertJsonSseReturnType(returnType))
                    .as("SSE return type for %s", method)
                    .isInstanceOf(AssertionError.class);
        }
    }

    @Test
    @DisplayName("AUTH-04: wildcard URL handlers serve resources without implicit view rendering")
    void fallbackUrlMappingsOnlyServeResources() {
        boolean foundCatchAll = false;
        for (var mapping :
                applicationContext.getBeansOfType(AbstractUrlHandlerMapping.class).values()) {
            for (var entry : mapping.getHandlerMap().entrySet()) {
                Object handler = resolveHandler(entry.getValue());
                assertThat(handler)
                        .as("URL handler for %s", entry.getKey())
                        .isInstanceOf(ResourceHttpRequestHandler.class);
                foundCatchAll |= entry.getKey().equals("/**");
            }
            if (mapping.getRootHandler() != null) {
                assertThat(resolveHandler(mapping.getRootHandler()))
                        .isInstanceOf(ResourceHttpRequestHandler.class);
            }
            if (mapping.getDefaultHandler() != null) {
                assertThat(resolveHandler(mapping.getDefaultHandler()))
                        .isInstanceOf(ResourceHttpRequestHandler.class);
            }
        }
        assertThat(foundCatchAll).as("Boot static-resource fallback").isTrue();
    }

    @Test
    @DisplayName(
            "AUTH-04: HTML requests and error dispatches cannot select request-derived XSLT views")
    void htmlAndUnknownPathRequestsKeepTheirExplicitResponseBoundaries() throws Exception {
        MvcResult statusResult =
                mockMvc.perform(get("/api/v1/status").accept(MediaType.APPLICATION_JSON))
                        .andExpect(status().isOk())
                        .andExpect(content().json("{\"status\":\"available\"}"))
                        .andReturn();
        assertThat(statusResult.getModelAndView()).isNull();

        MvcResult htmlStatus =
                mockMvc.perform(get("/api/v1/status").accept(MediaType.TEXT_HTML))
                        .andExpect(status().isNotAcceptable())
                        .andReturn();
        assertThat(htmlStatus.getModelAndView()).isNull();

        for (String path : List.of("/unknown.xsl", "/api/v1/unknown.xsl")) {
            MvcResult denied =
                    mockMvc.perform(
                                    get(path)
                                            .with(user("view-boundary").roles("OPERATIONS_ADMIN"))
                                            .accept(MediaType.TEXT_HTML))
                            .andExpect(status().isForbidden())
                            .andExpect(
                                    content()
                                            .contentTypeCompatibleWith(
                                                    MediaType.APPLICATION_PROBLEM_JSON))
                            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                            .andReturn();
            assertThat(denied.getModelAndView()).isNull();

            MvcResult fallback =
                    mockMvc.perform(
                                    get(path)
                                            .with(
                                                    request -> {
                                                        request.setDispatcherType(
                                                                DispatcherType.ERROR);
                                                        return request;
                                                    })
                                            .accept(MediaType.TEXT_HTML))
                            .andExpect(status().isNotFound())
                            .andReturn();
            assertThat(fallback.getHandler()).isInstanceOf(ResourceHttpRequestHandler.class);
            assertThat(fallback.getModelAndView()).isNull();
            assertThat(fallback.getResponse().getForwardedUrl()).isNull();
        }

        MvcResult error =
                mockMvc.perform(
                                get("/error")
                                        .with(
                                                request -> {
                                                    request.setDispatcherType(DispatcherType.ERROR);
                                                    return request;
                                                })
                                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                                        .requestAttr(
                                                RequestDispatcher.ERROR_REQUEST_URI, "/unknown.xsl")
                                        .accept(MediaType.TEXT_HTML))
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                        .andReturn();
        assertThat(error.getModelAndView()).isNotNull();
        assertThat(error.getModelAndView().getViewName()).isEqualTo("error");
        assertThat(error.getResponse().getForwardedUrl()).isNull();
        View errorView = applicationContext.getBean("error", View.class);
        assertNoXsltView(errorView);
        assertThat(
                        applicationContext
                                .getBean("beanNameViewResolver", ViewResolver.class)
                                .resolveViewName("error", Locale.ROOT))
                .isSameAs(errorView);
    }

    @Test
    void viewGraphCheckRejectsDirectNestedAndGenericXsltConfiguration() {
        assertRejected(new XsltViewResolver());

        UrlBasedViewResolver generic = new UrlBasedViewResolver();
        generic.setViewClass(XsltView.class);
        assertRejected(generic);

        ViewResolverComposite composite = new ViewResolverComposite();
        composite.setViewResolvers(List.of(new XsltViewResolver()));
        assertRejected(composite);

        ContentNegotiatingViewResolver negotiating = new ContentNegotiatingViewResolver();
        negotiating.setDefaultViews(List.of(new XsltView()));
        assertRejected(negotiating);
        assertRejected((name, locale) -> new XsltView());
        assertThatThrownBy(() -> assertNoXsltView(new XsltView()))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertNoXsltView(new InternalResourceView()))
                .isInstanceOf(AssertionError.class);
    }

    private Object resolveHandler(Object handler) {
        return handler instanceof String beanName ? applicationContext.getBean(beanName) : handler;
    }

    private static void assertJsonSseReturnType(MethodParameter returnType) {
        ResolvableType resolved = ResolvableType.forMethodParameter(returnType);
        assertThat(resolved.resolve()).isEqualTo(ResponseEntity.class);
        assertThat(resolved.getGeneric(0).resolve()).isEqualTo(SseEmitter.class);
    }

    private HandlerMethodReturnValueHandler selectedReturnHandler(MethodParameter returnType) {
        return requestAdapter.getReturnValueHandlers().stream()
                .filter(candidate -> candidate.supportsReturnType(returnType))
                .findFirst()
                .orElseThrow();
    }

    private static class UnsafeSseSignatures {
        ResponseEntity<FragmentsRendering> fragments() {
            return null;
        }

        ResponseEntity<ModelAndView> modelAndView() {
            return null;
        }

        ResponseEntity<View> view() {
            return null;
        }

        ResponseEntity<Object> untyped() {
            return null;
        }
    }

    private void assertRejected(ViewResolver resolver) {
        assertThatThrownBy(
                        () ->
                                assertNoXsltResolver(
                                        resolver,
                                        Collections.newSetFromMap(new IdentityHashMap<>())))
                .isInstanceOf(AssertionError.class);
    }

    private void assertNoXsltResolver(ViewResolver resolver, Set<ViewResolver> visited) {
        if (!visited.add(resolver)) {
            return;
        }
        assertThat(resolver).isNotInstanceOf(XsltViewResolver.class);
        assertThat(resolver.getClass())
                .as("audited MVC resolver type")
                .isIn(
                        BeanNameViewResolver.class,
                        ContentNegotiatingViewResolver.class,
                        InternalResourceViewResolver.class,
                        UrlBasedViewResolver.class,
                        ViewResolverComposite.class);
        if (resolver instanceof UrlBasedViewResolver urlResolver) {
            Class<?> viewClass = ReflectionTestUtils.invokeMethod(urlResolver, "getViewClass");
            assertThat(viewClass)
                    .as("view class for %s", resolver.getClass().getName())
                    .isNotNull();
            assertThat(XsltView.class.isAssignableFrom(viewClass))
                    .as("XSLT view class for %s", resolver.getClass().getName())
                    .isFalse();
            assertThat(viewClass)
                    .as("audited URL-based view class")
                    .isIn(InternalResourceView.class, JstlView.class);
        }
        if (resolver instanceof ViewResolverComposite composite) {
            composite.getViewResolvers().forEach(child -> assertNoXsltResolver(child, visited));
        }
        if (resolver instanceof ContentNegotiatingViewResolver negotiating) {
            if (negotiating.getViewResolvers() != null) {
                negotiating
                        .getViewResolvers()
                        .forEach(child -> assertNoXsltResolver(child, visited));
            }
            if (negotiating.getDefaultViews() != null) {
                negotiating.getDefaultViews().forEach(this::assertNoXsltView);
            }
        }
    }

    private void assertNoXsltView(View view) {
        assertThat(view).isNotInstanceOf(XsltView.class);
        assertThat(view.getClass().getName())
                .as("audited MVC view bean type")
                .isEqualTo(STATIC_ERROR_VIEW_CLASS);
    }
}
