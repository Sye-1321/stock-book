package io.github.sye1321.stockbook.identity.security;

import io.github.sye1321.stockbook.identity.StockBookUserDetailsService;
import io.github.sye1321.stockbook.identity.UserAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.CookieHttpSessionIdResolver;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.session.web.http.HttpSessionIdResolver;
import tools.jackson.databind.ObjectMapper;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {

    @Bean
    CookieSerializer sessionCookieSerializer(
            @Value("${server.servlet.session.cookie.secure:false}") boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("STOCKBOOK_SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        return serializer;
    }

    @Bean
    HttpSessionIdResolver httpSessionIdResolver(CookieSerializer cookieSerializer) {
        CookieHttpSessionIdResolver delegate = new CookieHttpSessionIdResolver();
        delegate.setCookieSerializer(cookieSerializer);
        return new HealthIndependentSessionIdResolver(delegate);
    }

    @Bean
    AuthenticationManager authenticationManager(
            StockBookUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain healthSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/v1/status", "/api/v1/liveness")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .securityContext(context ->
                        context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(cache -> cache.disable());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            ObjectMapper objectMapper,
            LoginRateLimiter loginRateLimiter,
            HttpSessionCsrfTokenRepository csrfTokenRepository,
            HttpSessionSecurityContextRepository securityContextRepository,
            UserAccountRepository userAccountRepository,
            Clock clock)
            throws Exception {
        JsonLoginAuthenticationFilter loginFilter =
                new JsonLoginAuthenticationFilter(authenticationManager, objectMapper, loginRateLimiter);
        loginFilter.setSecurityContextRepository(securityContextRepository);
        loginFilter.setSessionAuthenticationStrategy(new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new CsrfAuthenticationStrategy(csrfTokenRepository),
                new AuthenticatedSessionStrategy(clock))));

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName("_csrf");

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/status", "/api/v1/liveness", "/api/v1/auth/csrf")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .requestMatchers("/api/v1/staff/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/locations/**", "/api/v1/warehouses/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/locations/**", "/api/v1/warehouses/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfRequestHandler))
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, 401, "unauthenticated", "Authentication is required", objectMapper))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, 403, "forbidden", "The request is not permitted", objectMapper)))
                .logout(logout -> logout
                        .logoutRequestMatcher(PathPatternRequestMatcher.pathPattern(
                                HttpMethod.POST, "/api/v1/auth/logout"))
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("STOCKBOOK_SESSION")
                        .logoutSuccessHandler((request, response, authentication) ->
                                response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .addFilterAt(loginFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(
                        new SessionValidationFilter(userAccountRepository, clock), SecurityContextHolderFilter.class);

        return http.build();
    }

    private static void writeError(
            HttpServletResponse response,
            int status,
            String code,
            String message,
            ObjectMapper objectMapper)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(), new JsonLoginAuthenticationFilter.ErrorResponse(code, message));
    }

    private static final class HealthIndependentSessionIdResolver implements HttpSessionIdResolver {

        private final HttpSessionIdResolver delegate;

        private HealthIndependentSessionIdResolver(HttpSessionIdResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<String> resolveSessionIds(HttpServletRequest request) {
            String requestPath = request.getRequestURI().substring(request.getContextPath().length());
            if ("/api/v1/liveness".equals(requestPath) || "/api/v1/status".equals(requestPath)) {
                return List.of();
            }
            return delegate.resolveSessionIds(request);
        }

        @Override
        public void setSessionId(HttpServletRequest request, HttpServletResponse response, String sessionId) {
            delegate.setSessionId(request, response, sessionId);
        }

        @Override
        public void expireSession(HttpServletRequest request, HttpServletResponse response) {
            delegate.expireSession(request, response);
        }
    }
}
