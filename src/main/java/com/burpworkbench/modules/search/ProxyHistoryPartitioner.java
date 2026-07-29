package com.burpworkbench.modules.search;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.proxy.ProxyHttpRequestResponse;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Selects one stable Proxy history partition key strategy for the extension
 * class loader.
 *
 * <p>The minimum supported Montoya API does not declare {@code id()}. The
 * method is therefore detected by name and invoked only through a cached
 * {@link MethodHandle}. This keeps the product bytecode free of a direct
 * reference that older Burp versions cannot link.</p>
 */
final class ProxyHistoryPartitioner {
    enum Mode {
        HISTORY_ID,
        LEGACY_METADATA
    }

    private static final String PROXY_ITEM_CLASS_NAME =
            "burp.api.montoya.proxy.ProxyHttpRequestResponse";
    private static final String HISTORY_ID_METHOD_NAME = "id";
    private static final ProxyHistoryPartitioner RUNTIME = detectRuntime();

    private final Mode mode;
    private final ToLongFunction<ProxyHttpRequestResponse> stableKeyReader;

    private ProxyHistoryPartitioner(
            Mode mode,
            ToLongFunction<ProxyHttpRequestResponse> stableKeyReader
    ) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.stableKeyReader = Objects.requireNonNull(stableKeyReader, "stableKeyReader");
    }

    static ProxyHistoryPartitioner runtime() {
        return RUNTIME;
    }

    static ProxyHistoryPartitioner legacyMetadata() {
        return new ProxyHistoryPartitioner(
                Mode.LEGACY_METADATA,
                ProxyHistoryPartitioner::legacyMetadataKey
        );
    }

    static ProxyHistoryPartitioner historyIdForTesting(
            ToLongFunction<ProxyHttpRequestResponse> historyIdReader
    ) {
        return new ProxyHistoryPartitioner(
                Mode.HISTORY_ID,
                Objects.requireNonNull(historyIdReader, "historyIdReader")
        );
    }

    Mode mode() {
        return mode;
    }

    long stableKey(ProxyHttpRequestResponse item) {
        return stableKeyReader.applyAsLong(item);
    }

    int partition(ProxyHttpRequestResponse item, int partitionCount) {
        if (partitionCount <= 0) {
            throw new IllegalArgumentException("partitionCount must be positive");
        }
        return Math.floorMod(stableKey(item), partitionCount);
    }

    private static ProxyHistoryPartitioner detectRuntime() {
        try {
            ClassLoader loader = ProxyHistoryPartitioner.class.getClassLoader();
            Class<?> proxyItemType = Class.forName(
                    PROXY_ITEM_CLASS_NAME,
                    false,
                    loader
            );
            Method idMethod = proxyItemType.getMethod(HISTORY_ID_METHOD_NAME);
            if (idMethod.getParameterCount() != 0
                    || !isSupportedHistoryIdType(idMethod.getReturnType())) {
                return legacyMetadata();
            }
            MethodHandle idHandle = MethodHandles.publicLookup().unreflect(idMethod);
            return new ProxyHistoryPartitioner(
                    Mode.HISTORY_ID,
                    item -> invokeHistoryId(idHandle, item)
            );
        } catch (ClassNotFoundException
                 | NoSuchMethodException
                 | IllegalAccessException exception) {
            return legacyMetadata();
        } catch (RuntimeException exception) {
            return legacyMetadata();
        } catch (LinkageError error) {
            return legacyMetadata();
        }
    }

    private static boolean isSupportedHistoryIdType(Class<?> returnType) {
        return returnType == byte.class
                || returnType == short.class
                || returnType == int.class
                || returnType == long.class
                || Number.class.isAssignableFrom(returnType);
    }

    private static long invokeHistoryId(
            MethodHandle idHandle,
            ProxyHttpRequestResponse item
    ) {
        try {
            Object value = idHandle.invoke(item);
            if (value instanceof Number number) {
                return number.longValue();
            }
            throw new IllegalStateException(
                    "Proxy history id did not return a numeric value"
            );
        } catch (RuntimeException exception) {
            throw exception;
        } catch (LinkageError error) {
            throw new IllegalStateException("Unable to link Proxy history id", error);
        } catch (Error error) {
            throw error;
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to read Proxy history id", exception);
        }
    }

    private static long legacyMetadataKey(ProxyHttpRequestResponse item) {
        ZonedDateTime time = safeTime(item);
        long epochSecond = time == null ? 0L : time.toEpochSecond();
        long nano = time == null ? 0L : time.getNano();
        long listenerPort = safeListenerPort(item);
        HttpRequest request = safeRequest(item);
        String method = safeMethod(request);
        String url = safeUrl(request);

        long hash = 1L;
        hash = mixNumber(hash, epochSecond);
        hash = mixNumber(hash, nano);
        hash = mixNumber(hash, listenerPort);
        hash = mixString(hash, method);
        return mixString(hash, url);
    }

    private static ZonedDateTime safeTime(ProxyHttpRequestResponse item) {
        if (item == null) {
            return null;
        }
        try {
            return item.time();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static int safeListenerPort(ProxyHttpRequestResponse item) {
        if (item == null) {
            return 0;
        }
        try {
            return item.listenerPort();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static HttpRequest safeRequest(ProxyHttpRequestResponse item) {
        if (item == null) {
            return null;
        }

        HttpRequest request;
        try {
            request = item.finalRequest();
        } catch (RuntimeException exception) {
            request = null;
        }
        if (request != null) {
            return request;
        }

        try {
            return item.request();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String safeMethod(HttpRequest request) {
        if (request == null) {
            return "";
        }
        try {
            String method = request.method();
            return method == null ? "" : method;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static String safeUrl(HttpRequest request) {
        if (request == null) {
            return "";
        }
        try {
            String url = request.url();
            return url == null ? "" : url;
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private static long mixNumber(long hash, long value) {
        return 31L * hash + value;
    }

    private static long mixString(long hash, String value) {
        String safeValue = value == null ? "" : value;
        long mixed = mixNumber(hash, safeValue.length());
        for (int index = 0; index < safeValue.length(); index++) {
            mixed = mixNumber(mixed, safeValue.charAt(index));
        }
        return mixed;
    }
}
