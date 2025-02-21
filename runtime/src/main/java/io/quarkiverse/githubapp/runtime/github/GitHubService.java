package io.quarkiverse.githubapp.runtime.github;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;

import io.quarkiverse.githubapp.GitHubClientProvider;
import io.quarkiverse.githubapp.runtime.config.GitHubAppRuntimeConfig;
import io.quarkiverse.githubapp.runtime.signing.JwtTokenCreator;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigurationException;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClient;
import io.smallrye.graphql.client.dynamic.api.DynamicGraphQLClientBuilder;

@ApplicationScoped
public class GitHubService implements GitHubClientProvider {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String AUTHORIZATION_HEADER_BEARER = "Bearer %s";

    private final GitHubAppRuntimeConfig gitHubAppRuntimeConfig;

    private final JwtTokenProvider jwtTokenProvider;

    private final LoadingCache<Long, CachedInstallationToken> installationTokenCache;

    @Inject
    LaunchMode launchMode;

    @Inject
    public GitHubService(GitHubAppRuntimeConfig gitHubAppRuntimeConfig, JwtTokenCreator jwtTokenCreator) {
        this.gitHubAppRuntimeConfig = gitHubAppRuntimeConfig;
        this.jwtTokenProvider = new JwtTokenProvider(gitHubAppRuntimeConfig, jwtTokenCreator);
        this.installationTokenCache = Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfter(new Expiry<Long, CachedInstallationToken>() {
                    @Override
                    public long expireAfterCreate(Long installationId, CachedInstallationToken cachedInstallationGitHub,
                            long currentTime) {
                        long millis = cachedInstallationGitHub.getExpiresAt()
                                .minus(System.currentTimeMillis(), ChronoUnit.MILLIS)
                                .minus(10, ChronoUnit.MINUTES)
                                .toEpochMilli();
                        return TimeUnit.MILLISECONDS.toNanos(millis);
                    }

                    @Override
                    public long expireAfterUpdate(Long installationId, CachedInstallationToken cachedInstallationGitHub,
                            long currentTime, long currentDuration) {
                        // TODO, should we implement that too?
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(Long installationId, CachedInstallationToken cachedInstallationGitHub,
                            long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build(new CreateInstallationToken());
    }

    public void init(@Observes StartupEvent startupEvent) throws IOException {
        switch (launchMode) {
            case TEST:
                // If some client config is missing, we'll fail later, and only if tests attempt to retrieve the client.
                break;
            case NORMAL:
            case DEVELOPMENT:
            default:
                // If some client config is missing, we'll fail on startup.
                jwtTokenProvider.checkConfig();
                break;
        }
    }

    @Override
    public GitHub getInstallationClient(long installationId) {
        try {
            return createInstallationClient(installationId);
        } catch (IOException e1) {
            synchronized (this) {
                try {
                    // retry in a synchronized in case the token is invalidated in another thread
                    return createInstallationClient(installationId);
                } catch (IOException e2) {
                    try {
                        // this time we invalidate the token entirely and go for a new token
                        installationTokenCache.invalidate(installationId);
                        return createInstallationClient(installationId);
                    } catch (IOException e3) {
                        throw new IllegalStateException(
                                "Unable to create a GitHub client for the installation " + installationId, e3);
                    }
                }
            }
        }
    }

    @Override
    public DynamicGraphQLClient getInstallationGraphQLClient(long installationId) {
        try {
            return createInstallationGraphQLClient(installationId);
        } catch (IOException | ExecutionException | InterruptedException e1) {
            synchronized (this) {
                try {
                    // retry in a synchronized in case the token is invalidated in another thread
                    return createInstallationGraphQLClient(installationId);
                } catch (IOException | ExecutionException | InterruptedException e2) {
                    try {
                        // this time we invalidate the token entirely and go for a new token
                        installationTokenCache.invalidate(installationId);
                        return createInstallationGraphQLClient(installationId);
                    } catch (IOException | ExecutionException | InterruptedException e3) {
                        throw new IllegalStateException(
                                "Unable to create a GitHub GraphQL client for the installation " + installationId, e3);
                    }
                }
            }
        }
    }

    private GitHub createInstallationClient(long installationId) throws IOException {
        CachedInstallationToken installationToken = installationTokenCache.get(installationId);

        final GitHubBuilder gitHubBuilder = new GitHubBuilder()
                .withAppInstallationToken(installationToken.getToken())
                .withEndpoint(gitHubAppRuntimeConfig.instanceEndpoint);

        GitHub gitHub = gitHubBuilder.build();

        // this call is not counted in the rate limit
        gitHub.getRateLimit();

        return gitHub;
    }

    private DynamicGraphQLClient createInstallationGraphQLClient(long installationId)
            throws IOException, ExecutionException, InterruptedException {
        CachedInstallationToken installationToken = installationTokenCache.get(installationId);

        DynamicGraphQLClient graphQLClient = DynamicGraphQLClientBuilder.newBuilder()
                .url(gitHubAppRuntimeConfig.instanceEndpoint + "/graphql")
                .header(AUTHORIZATION_HEADER, String.format(AUTHORIZATION_HEADER_BEARER, installationToken.getToken()))
                .build();

        // this call is probably - it's not documented - not counted in the rate limit
        graphQLClient.executeSync("query {\n" +
                "rateLimit {\n" +
                "    limit\n" +
                "    cost\n" +
                "    remaining\n" +
                "    resetAt\n" +
                "  }\n" +
                "}");

        return graphQLClient;
    }

    // Using a lambda leads to a warning
    private class CreateInstallationToken implements CacheLoader<Long, CachedInstallationToken> {

        @Override
        public CachedInstallationToken load(Long installationId) throws Exception {
            try {
                GHAppInstallationToken installationToken = createApplicationGitHub().getApp()
                        .getInstallationById(installationId)
                        .createToken().create();

                return new CachedInstallationToken(installationToken.getToken(), installationToken.getExpiresAt());
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create a GitHub token for the installation " + installationId, e);
            }
        }
    }

    // TODO even if we have a cache for the other one, we should probably also keep this one around for a few minutes
    @Override
    public GitHub getApplicationClient() {
        return createApplicationGitHub();
    }

    private GitHub createApplicationGitHub() {
        String jwtToken;

        try {
            jwtToken = jwtTokenProvider.createJwtToken();
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Unable to generate the JWT token", e);
        }

        try {
            final GitHubBuilder gitHubBuilder = new GitHubBuilder()
                    .withJwtToken(jwtToken)
                    .withEndpoint(gitHubAppRuntimeConfig.instanceEndpoint);

            return gitHubBuilder.build();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create a GitHub client for the application", e);
        }
    }

    private static class JwtTokenProvider {
        private final String appId;
        private final PrivateKey privateKey;
        private final Set<String> missingPropertyKeys = new HashSet<>();
        private final JwtTokenCreator jwtTokenCreator;

        public JwtTokenProvider(GitHubAppRuntimeConfig gitHubAppRuntimeConfig, JwtTokenCreator jwtTokenCreator) {
            this.jwtTokenCreator = jwtTokenCreator;
            if (gitHubAppRuntimeConfig.appId.isPresent()) {
                appId = gitHubAppRuntimeConfig.appId.get();
            } else {
                appId = null;
                missingPropertyKeys.add("quarkus.github-app.app-id");
            }
            if (gitHubAppRuntimeConfig.privateKey.isPresent()) {
                privateKey = gitHubAppRuntimeConfig.privateKey.get();
            } else {
                privateKey = null;
                missingPropertyKeys.add("quarkus.github-app.private-key");
            }
        }

        public String createJwtToken() throws GeneralSecurityException, IOException {
            checkConfig();
            // maximum TTL is 10 minutes
            return jwtTokenCreator.createJwtToken(appId, privateKey, 540);
        }

        public void checkConfig() {
            if (!missingPropertyKeys.isEmpty()) {
                throw new ConfigurationException(
                        "Missing value for configuration properties " + missingPropertyKeys + "."
                                + " This configuration is necessary to create the GitHub clients."
                                + " It is optional only in tests, if GitHub clients are being mocked using quarkus-github-app-testing"
                                + " (see https://quarkiverse.github.io/quarkiverse-docs/quarkus-github-app/dev/testing.html).",
                        missingPropertyKeys);
            }
        }

    }
}
