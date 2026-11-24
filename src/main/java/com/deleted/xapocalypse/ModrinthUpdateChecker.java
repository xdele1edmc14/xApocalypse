package com.deleted.xapocalypse;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Fetches Modrinth metadata only; it never downloads or modifies plugin files. */
final class ModrinthUpdateChecker {

    static final String PROJECT_URL = "https://modrinth.com/plugin/xapocalypse";
    private static final URI VERSIONS_ENDPOINT = URI.create(
            "https://api.modrinth.com/v2/project/xapocalypse/version");

    @FunctionalInterface
    interface Client {
        CompletableFuture<String> fetch(String userAgent);
    }

    private final Client client;

    ModrinthUpdateChecker() {
        this(new HttpModrinthClient());
    }

    ModrinthUpdateChecker(Client client) {
        this.client = client;
    }

    CompletableFuture<Optional<String>> check(String currentVersion) {
        String userAgent = "xApocalypse/" + currentVersion + " (" + PROJECT_URL + ")";
        return client.fetch(userAgent)
                .thenApply(response -> findAvailableRelease(response, currentVersion));
    }

    static Optional<String> findAvailableRelease(String responseBody, String currentVersion) {
        SemanticVersion current = SemanticVersion.parse(currentVersion);
        if (current == null || responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }

        try {
            JsonElement root = JsonParser.parseString(responseBody);
            if (!root.isJsonArray()) {
                return Optional.empty();
            }

            String newestText = null;
            SemanticVersion newest = current;
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject version = element.getAsJsonObject();
                if (!"release".equals(stringField(version, "version_type"))
                        || !"listed".equals(stringField(version, "status"))) {
                    continue;
                }

                String candidateText = stringField(version, "version_number");
                SemanticVersion candidate = SemanticVersion.parse(candidateText);
                if (candidate != null && candidate.compareTo(newest) > 0) {
                    newest = candidate;
                    newestText = candidateText;
                }
            }
            return Optional.ofNullable(newestText);
        } catch (JsonParseException | IllegalStateException exception) {
            return Optional.empty();
        }
    }

    static boolean isNewerVersion(String candidate, String current) {
        SemanticVersion candidateVersion = SemanticVersion.parse(candidate);
        SemanticVersion currentVersion = SemanticVersion.parse(current);
        return candidateVersion != null
                && currentVersion != null
                && candidateVersion.compareTo(currentVersion) > 0;
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static final class HttpModrinthClient implements Client {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public CompletableFuture<String> fetch(String userAgent) {
            HttpRequest request = HttpRequest.newBuilder(VERSIONS_ENDPOINT)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            throw new CompletionException(new IOException(
                                    "Modrinth returned HTTP " + response.statusCode()));
                        }
                        return response.body();
                    });
        }
    }

    private record SemanticVersion(List<BigInteger> core, List<String> prerelease)
            implements Comparable<SemanticVersion> {

        static SemanticVersion parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim();
            if (normalized.startsWith("v") || normalized.startsWith("V")) {
                normalized = normalized.substring(1);
            }
            String[] buildParts = normalized.split("\\+", -1);
            if (buildParts.length > 2
                    || (buildParts.length == 2 && !validIdentifiers(buildParts[1], false))) {
                return null;
            }

            String publicVersion = buildParts[0];
            int prereleaseSeparator = publicVersion.indexOf('-');
            String coreText = prereleaseSeparator >= 0
                    ? publicVersion.substring(0, prereleaseSeparator)
                    : publicVersion;
            String prereleaseText = prereleaseSeparator >= 0
                    ? publicVersion.substring(prereleaseSeparator + 1)
                    : null;

            String[] coreParts = coreText.split("\\.", -1);
            if (coreParts.length != 3) {
                return null;
            }

            List<BigInteger> core = new ArrayList<>(3);
            for (String part : coreParts) {
                if (!isNumericIdentifier(part) || hasLeadingZero(part)) {
                    return null;
                }
                core.add(new BigInteger(part));
            }

            if (prereleaseText != null && !validIdentifiers(prereleaseText, true)) {
                return null;
            }

            List<String> prerelease = prereleaseText != null
                    ? List.of(prereleaseText.split("\\.", -1))
                    : List.of();
            return new SemanticVersion(List.copyOf(core), prerelease);
        }

        private static boolean validIdentifiers(String value, boolean enforceNumericLeadingZeros) {
            if (value == null || value.isEmpty()) {
                return false;
            }
            for (String identifier : value.split("\\.", -1)) {
                if (identifier.isEmpty()
                        || !identifier.chars().allMatch(character ->
                        (character >= '0' && character <= '9')
                                || (character >= 'A' && character <= 'Z')
                                || (character >= 'a' && character <= 'z')
                                || character == '-')) {
                    return false;
                }
                if (enforceNumericLeadingZeros
                        && isNumericIdentifier(identifier)
                        && hasLeadingZero(identifier)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isNumericIdentifier(String value) {
            return !value.isEmpty()
                    && value.chars().allMatch(character -> character >= '0' && character <= '9');
        }

        private static boolean hasLeadingZero(String value) {
            return value.length() > 1 && value.charAt(0) == '0';
        }

        @Override
        public int compareTo(SemanticVersion other) {
            int coreLength = Math.max(core.size(), other.core.size());
            for (int index = 0; index < coreLength; index++) {
                BigInteger left = core.get(index);
                BigInteger right = other.core.get(index);
                int comparison = left.compareTo(right);
                if (comparison != 0) {
                    return comparison;
                }
            }

            if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
                return prerelease.isEmpty() == other.prerelease.isEmpty()
                        ? 0
                        : prerelease.isEmpty() ? 1 : -1;
            }

            int prereleaseLength = Math.max(prerelease.size(), other.prerelease.size());
            for (int index = 0; index < prereleaseLength; index++) {
                if (index >= prerelease.size()) return -1;
                if (index >= other.prerelease.size()) return 1;
                int comparison = compareIdentifier(prerelease.get(index), other.prerelease.get(index));
                if (comparison != 0) {
                    return comparison;
                }
            }
            return 0;
        }

        private static int compareIdentifier(String left, String right) {
            boolean leftNumeric = isNumericIdentifier(left);
            boolean rightNumeric = isNumericIdentifier(right);
            if (leftNumeric && rightNumeric) {
                return new BigInteger(left).compareTo(new BigInteger(right));
            }
            if (leftNumeric != rightNumeric) {
                return leftNumeric ? -1 : 1;
            }
            return left.compareTo(right);
        }
    }
}
