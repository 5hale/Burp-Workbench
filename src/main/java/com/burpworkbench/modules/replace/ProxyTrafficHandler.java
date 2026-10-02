package com.burpworkbench.modules.replace;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.proxy.http.InterceptedRequest;
import burp.api.montoya.proxy.http.InterceptedResponse;
import burp.api.montoya.proxy.http.ProxyRequestHandler;
import burp.api.montoya.proxy.http.ProxyRequestReceivedAction;
import burp.api.montoya.proxy.http.ProxyRequestToBeSentAction;
import burp.api.montoya.proxy.http.ProxyResponseHandler;
import burp.api.montoya.proxy.http.ProxyResponseReceivedAction;
import burp.api.montoya.proxy.http.ProxyResponseToBeSentAction;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Applies rules once, when Proxy is about to send a message, without altering interception decisions. */
final class ProxyTrafficHandler implements ProxyRequestHandler, ProxyResponseHandler, AutoCloseable {
    private static final long DIAGNOSTIC_INTERVAL_NANOS = 5_000_000_000L;
    private static final long SCOPE_PREFLIGHT_NANOS = 250_000_000L;
    private final Supplier<RuleStore.State> states;
    private final Supplier<ForwardSession.State> forwards;
    private final Consumer<String> diagnostics;
    private final Adapter adapter;
    private final LongSupplier clock;
    private final AtomicLong lastDiagnostic = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedDiagnostics = new AtomicLong();
    private volatile boolean closed;
    private final java.util.LinkedHashMap<Integer, OriginalScope> originalScopes = new java.util.LinkedHashMap<>();
    private record OriginalScope(String url,long captured) {}
    private synchronized void remember(int id,String url) {
        if(closed)return;
        if(originalScopes.size()>=2048)originalScopes.remove(originalScopes.keySet().iterator().next());
        originalScopes.put(id,new OriginalScope(url,clock.getAsLong()));
    }
    private synchronized String takeOriginal(int id) {
        OriginalScope scope=originalScopes.remove(id);
        return scope==null||clock.getAsLong()-scope.captured()>300_000_000_000L?null:scope.url();
    }

    ProxyTrafficHandler(Supplier<RuleStore.State> states, Consumer<String> diagnostics) {
        this(states, diagnostics, new NativeAdapter(), System::nanoTime);
    }

    ProxyTrafficHandler(Supplier<RuleStore.State> states, Supplier<ForwardSession.State> forwards, Consumer<String> diagnostics) {
        this(states, forwards, diagnostics, new NativeAdapter(), System::nanoTime);
    }

    ProxyTrafficHandler(Supplier<RuleStore.State> states, Consumer<String> diagnostics,
                        Adapter adapter, LongSupplier clock) {
        this(states, ForwardSession.State::empty, diagnostics, adapter, clock);
    }

    ProxyTrafficHandler(Supplier<RuleStore.State> states, Supplier<ForwardSession.State> forwards, Consumer<String> diagnostics,
                        Adapter adapter, LongSupplier clock) {
        this.states = Objects.requireNonNull(states);
        this.forwards = Objects.requireNonNull(forwards);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.adapter = Objects.requireNonNull(adapter);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override public ProxyRequestReceivedAction handleRequestReceived(InterceptedRequest request) {
        return adapter.receivedRequest(request, annotations(request::annotations));
    }

    @Override public ProxyResponseReceivedAction handleResponseReceived(InterceptedResponse response) {
        return adapter.receivedResponse(response, annotations(response::annotations));
    }

    @Override public ProxyRequestToBeSentAction handleRequestToBeSent(InterceptedRequest request) {
        Annotations annotations = annotations(request::annotations);
        HttpRequest output = request;
        if (!closed) {
            try {
                RuleStore.State state = states.get();
                ForwardSession.State forward = forwards.get();
                // Read original URL before Replace can rewrite the request line or Host.
                String originalUrl = hasRules(state, true) || forward.enabled() && !forward.rules().isEmpty()
                        ? scopeUrl(request::url, true) : "";
                ForwardEngine.Destination destination = ForwardEngine.select(forward, originalUrl,
                        issue -> report(true, issue, 1));
                if (hasRules(state, true)) {
                    String requestUrl = originalUrl;
                    byte[] bytes = scopeEligible(state, requestUrl, true) && !closed
                            ? boundedBytes(request.toByteArray(), true) : null;
                    if (bytes != null && !closed) {
                        TrafficEngine.Result result = TrafficEngine.transform(state.rules(), requestUrl, true, bytes);
                        reportIssues(true, result);
                        if (!closed && !Arrays.equals(bytes, result.message())) {
                            HttpRequest changed = adapter.request(request.httpService(), result.message());
                            if (!closed) output = Objects.requireNonNull(changed);
                        }
                    }
                }
                if(destination != null && !closed) {
                    try {
                        HttpRequest forwarded = adapter.forward(output, destination);
                        if(!closed) {
                            remember(request.messageId(),originalUrl);
                            output = Objects.requireNonNull(forwarded);
                        }
                    } catch(RuntimeException invalid) {
                        // Preserve a successful Replace result; never commit a partial destination/Host change.
                        report(true, "FORWARD_FAILED", 1);
                    }
                }
            } catch (RuntimeException failure) {
                report(true, "CALLBACK_FAILED", 0);
                output = request;
            }
        }
        return adapter.sentRequest(output, annotations);
    }

    @Override public ProxyResponseToBeSentAction handleResponseToBeSent(InterceptedResponse response) {
        Annotations annotations = annotations(response::annotations);
        HttpResponse output = response;
        if (!closed) {
            try {
                RuleStore.State state = states.get();
                String original = originalScope(response);
                if (hasRules(state, false)) {
                    String requestUrl = original != null ? original : scopeUrl(() -> response.initiatingRequest().url(), false);
                    byte[] bytes = scopeEligible(state, requestUrl, false) && !closed
                            ? boundedBytes(response.toByteArray(), false) : null;
                    if (bytes != null && !closed) {
                        TrafficEngine.Result result = TrafficEngine.transform(state.rules(), requestUrl, false, bytes);
                        reportIssues(false, result);
                        if (!closed && !Arrays.equals(bytes, result.message())) {
                            HttpResponse changed = adapter.response(result.message());
                            if (!closed) output = Objects.requireNonNull(changed);
                        }
                    }
                }
            } catch (RuntimeException failure) {
                report(false, "CALLBACK_FAILED", 0);
                output = response;
            }
        }
        return adapter.sentResponse(output, annotations);
    }

    private synchronized String originalScope(InterceptedResponse response) {
        if(originalScopes.isEmpty())return null;
        return takeOriginal(response.messageId());
    }

    private static boolean hasRules(RuleStore.State state, boolean request) {
        if (state == null || !state.enabled()) return false;
        for (RuleDraft rule : state.rules()) {
            if (rule.enabled() && !rule.match().isEmpty()
                    && relevantType(rule.target(), request)) return true;
        }
        return false;
    }

    private String scopeUrl(Supplier<String> source, boolean request) {
        try {
            String value = source.get();
            return value == null ? "" : value;
        } catch (RuntimeException unavailable) {
            // Unrestricted rules need no URL. Scoped rules reject this missing value individually.
            report(request, "URL_UNAVAILABLE", 0);
            return "";
        }
    }

    private boolean scopeEligible(RuleStore.State state, String requestUrl, boolean request) {
        long deadline = clock.getAsLong() + SCOPE_PREFLIGHT_NANOS;
        for (RuleDraft rule : state.rules()) {
            if (!rule.enabled() || rule.match().isEmpty() || !relevantType(rule.target(), request)) continue;
            if (closed || clock.getAsLong() - deadline >= 0) {
                if (!closed) report(request, "SCOPE_PREFLIGHT_LIMIT", 0);
                return false;
            }
            try {
                if (ScopeMatcher.matches(rule, requestUrl)) return true;
            } catch (RuntimeException malformedScope) {
                report(request, "INVALID_SCOPE", 1);
            }
        }
        return false;
    }

    private static boolean relevantType(String target, boolean request) {
        return switch (target) {
            case RuleTypes.REQUEST_HEADER, RuleTypes.REQUEST_BODY, RuleTypes.REQUEST_FIRST_LINE,
                    RuleTypes.REQUEST_PARAM_NAME, RuleTypes.REQUEST_PARAM_VALUE -> request;
            case RuleTypes.RESPONSE_HEADER, RuleTypes.RESPONSE_BODY, RuleTypes.RESPONSE_FIRST_LINE -> !request;
            default -> false;
        };
    }

    private byte[] boundedBytes(ByteArray data, boolean request) {
        int length = data.length();
        if (length < 0 || length > TrafficEngine.MAX_WIRE_BYTES) {
            report(request, "MESSAGE_TOO_LARGE", 0);
            return null;
        }
        byte[] bytes = data.getBytes();
        if (bytes == null || bytes.length > TrafficEngine.MAX_WIRE_BYTES) {
            report(request, "MESSAGE_TOO_LARGE", 0);
            return null;
        }
        return bytes;
    }

    private Annotations annotations(Supplier<Annotations> source) {
        try {
            return source.get();
        } catch (RuntimeException unavailable) {
            report(false, "ANNOTATIONS_UNAVAILABLE", 0);
            return null;
        }
    }

    private void reportIssues(boolean request, TrafficEngine.Result result) {
        if (!result.issues().isEmpty()) report(request, "RULE_ISSUES", result.issues().size());
    }

    private void report(boolean request, String outcome, int issues) {
        try {
            long now = clock.getAsLong();
            long last = lastDiagnostic.get();
            if ((last != Long.MIN_VALUE && now - last < DIAGNOSTIC_INTERVAL_NANOS)
                    || !lastDiagnostic.compareAndSet(last, now)) {
                suppressedDiagnostics.incrementAndGet();
                return;
            }
            long suppressed = suppressedDiagnostics.getAndSet(0);
            diagnostics.accept("REPLACE_PROXY direction=" + (request ? "REQUEST" : "RESPONSE")
                    + " outcome=" + outcome + " issues=" + issues + " suppressed=" + suppressed);
        } catch (RuntimeException unavailableLogger) {
            // Diagnostics cannot interfere with traffic forwarding.
        }
    }

    @Override public synchronized void close() {
        closed = true;
        originalScopes.clear();
    }

    interface Adapter {
        default HttpRequest forward(HttpRequest request, ForwardEngine.Destination destination) {
            return ForwardEngine.apply(request,destination);
        }
        HttpRequest request(HttpService service, byte[] bytes);
        HttpResponse response(byte[] bytes);
        ProxyRequestReceivedAction receivedRequest(HttpRequest request, Annotations annotations);
        ProxyResponseReceivedAction receivedResponse(HttpResponse response, Annotations annotations);
        ProxyRequestToBeSentAction sentRequest(HttpRequest request, Annotations annotations);
        ProxyResponseToBeSentAction sentResponse(HttpResponse response, Annotations annotations);
    }

    private static final class NativeAdapter implements Adapter {
        @Override public HttpRequest request(HttpService service, byte[] bytes) {
            return HttpRequest.httpRequest(service, ByteArray.byteArray(bytes));
        }
        @Override public HttpResponse response(byte[] bytes) {
            return HttpResponse.httpResponse(ByteArray.byteArray(bytes));
        }
        @Override public ProxyRequestReceivedAction receivedRequest(HttpRequest request, Annotations annotations) {
            return annotations == null ? ProxyRequestReceivedAction.continueWith(request)
                    : ProxyRequestReceivedAction.continueWith(request, annotations);
        }
        @Override public ProxyResponseReceivedAction receivedResponse(HttpResponse response, Annotations annotations) {
            return annotations == null ? ProxyResponseReceivedAction.continueWith(response)
                    : ProxyResponseReceivedAction.continueWith(response, annotations);
        }
        @Override public ProxyRequestToBeSentAction sentRequest(HttpRequest request, Annotations annotations) {
            return annotations == null ? ProxyRequestToBeSentAction.continueWith(request)
                    : ProxyRequestToBeSentAction.continueWith(request, annotations);
        }
        @Override public ProxyResponseToBeSentAction sentResponse(HttpResponse response, Annotations annotations) {
            return annotations == null ? ProxyResponseToBeSentAction.continueWith(response)
                    : ProxyResponseToBeSentAction.continueWith(response, annotations);
        }
    }
}
