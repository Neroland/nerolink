package za.co.neroland.nerolink.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;

import io.sentry.Breadcrumb;
import io.sentry.IScopes;
import io.sentry.Scope;
import io.sentry.Scopes;
import io.sentry.SentryClient;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.SentryOptions;
import io.sentry.UncaughtExceptionHandlerIntegration;
import io.sentry.protocol.Message;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.SentryStackFrame;
import io.sentry.protocol.SentryStackTrace;

import za.co.neroland.nerolink.NeroLinkCommon;
import za.co.neroland.nerolink.config.NeroLinkConfig;
import za.co.neroland.nerolink.platform.InstalledMods;

/**
 * Crash/error reporting for NeroLink via Sentry (EU ingest), matching the rest of the Neroland family
 * (cf. {@code NeroTechTelemetry} / {@code NerolandCoreTelemetry}). Built for the CurseForge disclosure
 * rule and POPIA/GDPR data-minimisation:
 *
 * <ul>
 *   <li><b>Opt-out:</b> gated on {@code telemetryEnabled} in {@link NeroLinkConfig} (default ON,
 *       client-local, disclosed). Set it false to stop reporting (takes effect on restart).</li>
 *   <li><b>NeroLink errors only:</b> {@code beforeSend} drops any event whose stack trace does not
 *       touch {@code za.co.neroland.nerolink}.</li>
 *   <li><b>No personal data:</b> no IP, no hostname, no user identity; OS-account names are scrubbed
 *       from file paths. The bridge's own secrets never reach a log line, so they never reach Sentry:
 *       device tokens are hashed, pairing codes and relay keys are never logged. Payload: stack trace
 *       + mod/MC/loader/OS/Java versions.</li>
 *   <li><b>Bounded volume:</b> per-session de-duplication + a hard cap of
 *       {@value #MAX_EVENTS_PER_SESSION} events per session.</li>
 * </ul>
 *
 * <p>{@link #init(boolean, boolean, String)} is called once per loader at bootstrap, after the loader
 * entry point has populated {@link InstalledMods}. Full disclosure text: {@code PRIVACY.md}.
 */
public final class NeroLinkTelemetry {

    /**
     * Sentry DSN — a public client key (write-only ingest), safe to ship in the jar. This is
     * NeroLink's own Sentry project (EU region, {@code de.sentry.io}). If blanked, telemetry
     * initialises to a no-op so events are never sent to the wrong project.
     */
    private static final String DSN =
            "https://107562dbe61c1e6fa0f6b4d3eadfbc87@o4511183823241216.ingest.de.sentry.io/4511696070574160";

    private static final String PACKAGE_MARKER = "za.co.neroland.nerolink";
    private static final int MAX_EVENTS_PER_SESSION = 10;
    private static final int MAX_MODS_REPORTED = 300;
    private static final Pattern USER_PATH =
            Pattern.compile("(?i)(?:[A-Z]:)?[/\\\\](?:Users|home)[/\\\\][^/\\\\\\s:;,'\"]+");

    private static volatile boolean active;
    /** NeroLink's own scopes; {@code null} until {@link #start} succeeds. Never the global hub. */
    private static volatile IScopes scopes;
    /** Process-lifetime uncaught-exception hook bound to {@link #scopes}; kept so it is never collected. */
    private static UncaughtExceptionHandlerIntegration uncaughtHandler;
    private static final AtomicInteger eventsSent = new AtomicInteger();
    private static final Set<String> seenFingerprints = ConcurrentHashMap.newKeySet();
    private static SentryLogAppender appender;

    private NeroLinkTelemetry() {
    }

    /**
     * Called once per loader at bootstrap (after {@link InstalledMods} is populated). Starts
     * reporting unless the player opted out.
     *
     * @param devEnvironment true in an IDE/dev run (loader-detected), tags the event environment
     * @param client         true on a physical client (integrated server), false on a dedicated server
     * @param mcVersion      the running Minecraft version, or {@code "unknown"}
     */
    public static void init(boolean devEnvironment, boolean client, String mcVersion) {
        NeroLinkConfig.register();
        if (!NeroLinkConfig.telemetryEnabled()) {
            return;
        }
        start(devEnvironment, client, mcVersion);
    }

    private static synchronized void start(boolean dev, boolean client, String mcVersion) {
        if (active) {
            return;
        }
        if (DSN.isBlank()) {
            NeroLinkCommon.LOGGER.info("[NeroLink] Telemetry is enabled but no Sentry DSN is configured; "
                    + "error reporting is inactive.");
            return;
        }
        String version = NeroLinkCommon.bridgeVersion();
        SentryOptions options = baseOptions(version, dev ? "development" : environmentOf(version));
        options.setBeforeSend((event, hint) -> ForeignMixinFailure.matches(event) ? null : filterAndScrub(event));
        // Own client and scopes, never the global hub: every Nero mod bundles the same Sentry jar, so
        // a global Sentry.init would replace (and silence) every other mod's client.
        IScopes own = isolatedScopes(options, "nerolink");
        scopes = own;
        own.configureScope(scope -> {
            scope.setTag("loader", InstalledMods.loader().toLowerCase(Locale.ROOT));
            scope.setTag("dist", client ? "client" : "dedicated_server");
            scope.setTag("runtime", dev ? "development" : "production");
            scope.setTag("mc_version", mcVersion == null || mcVersion.isBlank() ? "unknown" : mcVersion);
            scope.setTag("cfg_read_only", Boolean.toString(NeroLinkConfig.READ_ONLY.get()));
            scope.setTag("cfg_allow_offline_actions", Boolean.toString(NeroLinkConfig.ALLOW_OFFLINE_OVERRIDE.get()));
            try {
                List<InstalledMods.Entry> mods = InstalledMods.entries();
                if (mods != null && !mods.isEmpty()) {
                    if (mods.size() > MAX_MODS_REPORTED) {
                        mods = mods.subList(0, MAX_MODS_REPORTED);
                    }
                    List<String> ids = new ArrayList<>(mods.size());
                    for (InstalledMods.Entry entry : mods) {
                        ids.add(entry.id() + "@" + entry.version());
                    }
                    scope.setTag("nero_mod_count", Integer.toString(ids.size()));
                    java.util.Map<String, Object> modContext = new java.util.HashMap<>();
                    modContext.put("count", ids.size());
                    modContext.put("ids", ids);
                    scope.setContexts("nero_mods", modContext);
                }
            } catch (RuntimeException | LinkageError e) {
                // mod list not available this early — skip it
            }
        });
        own.startSession();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                own.endSession();
                own.flush(2000L);
            } catch (RuntimeException ignored) {
                // best-effort flush on shutdown
            }
        }, "nerolink-sentry-shutdown"));
        if (appender == null) {
            appender = new SentryLogAppender();
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
        }
        active = true;
        NeroLinkCommon.LOGGER.info("[NeroLink] Telemetry enabled (anonymous error reports, EU servers; "
                + "opt out via telemetryEnabled=false in config/nerolink.properties).");
    }

    /** Options for NeroLink's own client: same privacy settings as before, never the global hub's. */
    static SentryOptions baseOptions(String modVersion, String environment) {
        SentryOptions options = new SentryOptions();
        options.setDsn(DSN);
        options.setRelease("nerolink@" + modVersion);
        options.setEnvironment(environment);
        options.setSendDefaultPii(false);          // POPIA/GDPR: never the sender's IP/identity
        options.setAttachServerName(false);        // hostname is identifying
        options.setEnableUncaughtExceptionHandler(true);
        options.setEnableShutdownHook(false);      // flushed by our own hook; never close the global hub
        options.setEnableAutoSessionTracking(false);
        return options;
    }

    /**
     * Builds isolated scopes bound to a client of our own: what {@code Sentry.init} does, minus the global
     * hub. The uncaught-exception handler chains to the previous one, so every mod still sees crashes and
     * keeps only its own through {@code beforeSend}.
     */
    static IScopes isolatedScopes(SentryOptions options, String creator) {
        options.activate();                        // start Sentry's executors, as the global init would
        Scopes own = new Scopes(new Scope(options), new Scope(options), new Scope(options), creator);
        own.bindClient(new SentryClient(options));
        if (options.isEnableUncaughtExceptionHandler()) {
            uncaughtHandler = new UncaughtExceptionHandlerIntegration();
            uncaughtHandler.register(own, options);
        }
        return own;
    }

    /** A non-identifying breadcrumb that rides along with the next error report. No-op when off. */
    public static void breadcrumb(String category, String message) {
        if (!active) {
            return;
        }
        Breadcrumb crumb = new Breadcrumb();
        crumb.setType("default");
        crumb.setCategory(category);
        crumb.setLevel(SentryLevel.INFO);
        crumb.setMessage(scrub(message));
        scopes.addBreadcrumb(crumb);
    }

    private static String environmentOf(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        if (v.contains("-alpha")) {
            return "alpha";
        }
        if (v.contains("-beta")) {
            return "beta";
        }
        return "production";
    }

    static boolean isActive() {
        return active;
    }

    /** True if any frame of the throwable (or its causes/suppressed) is NeroLink code. */
    static boolean touchesNeroLink(Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 16) {
            for (StackTraceElement el : t.getStackTrace()) {
                if (el.getClassName().startsWith(PACKAGE_MARKER)) {
                    return true;
                }
            }
            for (Throwable s : t.getSuppressed()) {
                if (touchesNeroLink(s)) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    static void capture(Throwable t) {
        if (!active || t == null) {
            return;
        }
        scopes.captureException(t);
    }

    /** Capture a handled exception if it is still clearly from NeroLink code. */
    public static void captureHandledException(Throwable t) {
        if (t != null && touchesNeroLink(t)) {
            capture(t);
        }
    }

    /** Capture a handled exception with a non-identifying source label for triage. */
    public static void captureHandledException(Throwable t, String source, String operation) {
        if (!active || t == null || !touchesNeroLink(t)) {
            return;
        }
        scopes.captureException(t, scope -> {
            scope.setTag("handled", "true");
            scope.setTag("source", source);
            scope.setExtra("operation", operation);
        });
    }

    static void captureMessage(String message) {
        if (!active) {
            return;
        }
        String scrubbed = scrub(message);
        if (scrubbed.length() > 4000) {
            scrubbed = scrubbed.substring(0, 4000) + "…[truncated]";
        }
        SentryEvent event = new SentryEvent();
        event.setLevel(SentryLevel.FATAL);
        Message msg = new Message();
        msg.setFormatted(scrubbed);
        event.setMessage(msg);
        scopes.captureEvent(event);
    }

    private static SentryEvent filterAndScrub(SentryEvent event) {
        if (!isNeroLinkRelated(event)) {
            return null;
        }
        String fingerprint = fingerprintOf(event);
        if (!seenFingerprints.add(fingerprint)) {
            return null;
        }
        if (eventsSent.incrementAndGet() > MAX_EVENTS_PER_SESSION) {
            return null;
        }
        event.setUser(null);
        event.setServerName(null);
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                String value = ex.getValue();
                if (value != null) {
                    ex.setValue(scrub(value));
                }
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames != null) {
                    for (SentryStackFrame frame : frames) {
                        frame.setAbsPath(null);
                    }
                }
            }
        }
        Message message = event.getMessage();
        if (message != null && message.getFormatted() != null) {
            message.setFormatted(scrub(message.getFormatted()));
        }
        return event;
    }

    private static boolean isNeroLinkRelated(SentryEvent event) {
        Throwable t = event.getThrowable();
        if (t != null && touchesNeroLink(t)) {
            return true;
        }
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames == null) {
                    continue;
                }
                for (SentryStackFrame frame : frames) {
                    String module = frame.getModule();
                    if (module != null && module.startsWith(PACKAGE_MARKER)) {
                        return true;
                    }
                }
            }
        }
        Message message = event.getMessage();
        String formatted = message == null ? null : message.getFormatted();
        return formatted != null && formatted.contains(PACKAGE_MARKER);
    }

    private static String fingerprintOf(SentryEvent event) {
        StringBuilder sb = new StringBuilder();
        List<SentryException> exceptions = event.getExceptions();
        Message message = event.getMessage();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                sb.append(ex.getType()).append('|');
                SentryStackTrace st = ex.getStacktrace();
                List<SentryStackFrame> frames = st == null ? null : st.getFrames();
                if (frames != null) {
                    for (SentryStackFrame frame : frames) {
                        String module = frame.getModule();
                        if (module != null && module.startsWith(PACKAGE_MARKER)) {
                            sb.append(module).append(':').append(frame.getLineno()).append('|');
                        }
                    }
                }
            }
        } else if (message != null) {
            String formatted = message.getFormatted();
            if (formatted != null) {
                sb.append(formatted, 0, Math.min(200, formatted.length()));
            }
        }
        return sb.toString();
    }

    /** Replaces home-directory paths (which contain the OS account name) with a neutral marker. */
    static String scrub(String text) {
        return USER_PATH.matcher(text).replaceAll("/~");
    }
}
