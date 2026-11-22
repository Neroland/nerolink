package za.co.neroland.nerolink.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.sentry.Hint;
import io.sentry.IScopes;
import io.sentry.RequestDetails;
import io.sentry.Sentry;
import io.sentry.SentryEnvelope;
import io.sentry.SentryOptions;
import io.sentry.transport.ITransport;
import io.sentry.transport.RateLimiter;

/**
 * Proves the isolated-client pattern: the mod's scopes deliver through their own client, never through
 * (or into) the global {@code Sentry} hub, so a later {@code Sentry.init} by another mod cannot replace
 * or silence them.
 */
class IsolatedScopesTest {

    private final List<IScopes> opened = new ArrayList<>();

    @AfterEach
    void close() {
        for (IScopes s : opened) {
            s.close();
        }
        Sentry.close();
    }

    /** A transport that only records envelopes; nothing leaves the test. */
    private static final class Recording implements ITransport {
        final List<SentryEnvelope> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(SentryEnvelope envelope, Hint hint) {
            sent.add(envelope);
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public void flush(long timeoutMillis) {
        }

        @Override
        public RateLimiter getRateLimiter() {
            return null;
        }

        @Override
        public void close(boolean isRestarting) {
        }

        @Override
        public void close() {
        }
    }

    private IScopes isolated(Recording transport, String creator) {
        SentryOptions options = NeroLinkTelemetry.baseOptions("0.0.0-test", "test");
        options.setEnableUncaughtExceptionHandler(false);
        options.setTransportFactory((SentryOptions o, RequestDetails d) -> transport);
        IScopes s = NeroLinkTelemetry.isolatedScopes(options, creator);
        opened.add(s);
        return s;
    }

    @Test
    void isolatedScopesDeliverWithoutTouchingTheGlobalHub() {
        Recording mine = new Recording();
        IScopes scopes = isolated(mine, "test-nerolink");

        scopes.captureException(new IllegalStateException("isolated"));

        assertEquals(1, mine.sent.size());
        assertFalse(Sentry.isEnabled(), "the global hub must stay uninitialised");
    }

    @Test
    void anotherModsGlobalInitDoesNotReplaceTheIsolatedClient() {
        Recording mine = new Recording();
        IScopes scopes = isolated(mine, "test-nerolink");

        Recording global = new Recording();
        Sentry.init(o -> {
            o.setDsn("https://key@o0.ingest.sentry.io/0");
            o.setEnableUncaughtExceptionHandler(false);
            o.setEnableShutdownHook(false);
            o.setTransportFactory((SentryOptions opts, RequestDetails d) -> global);
        });

        scopes.captureException(new IllegalStateException("after someone else's init"));
        Sentry.captureException(new IllegalStateException("global"));

        assertEquals(1, mine.sent.size(), "the mod still reports after another mod's Sentry.init");
        assertEquals(1, global.sent.size(), "the other mod's event stays in the other mod's client");
    }

    @Test
    void twoIsolatedClientsKeepTheirOwnEvents() {
        Recording a = new Recording();
        Recording b = new Recording();
        IScopes first = isolated(a, "mod-a");
        IScopes second = isolated(b, "mod-b");

        first.captureException(new IllegalStateException("a"));
        second.captureException(new IllegalStateException("b"));
        second.captureException(new IllegalArgumentException("b2"));

        assertEquals(1, a.sent.size());
        assertEquals(2, b.sent.size());
    }
}
