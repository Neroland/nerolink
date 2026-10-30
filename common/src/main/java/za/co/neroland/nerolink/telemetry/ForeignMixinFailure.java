package za.co.neroland.nerolink.telemetry;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;

/**
 * Drops crashes caused by <b>another mod's mixin</b> failing to apply (MC-NEROPOWER-1, MC-NEROEVENTS-1).
 *
 * <p>Mixins are applied when their target class is first loaded. If a Neroland class happens to be the first
 * to touch that target (for example a method reference whose signature names {@code MinecraftServer}),
 * a Neroland frame sits at the top of the stack, even though the failure belongs to the other mod. A typical
 * case is that mod's jar being built for an older Minecraft version. Nothing in Neroland caused it or can fix it.
 *
 * <p>The rule is narrow. An event is dropped only when its exception chain holds a Mixin failure
 * ({@code org.spongepowered.asm.mixin.*}) whose message names the owning mod(s) or mixin config(s), and
 * <b>none</b> of those names is a Neroland one ({@code nero*}). A failure in one of our own mixin configs, or one
 * that names no owner at all, is still reported.
 */
final class ForeignMixinFailure {

    private static final String MIXIN_PACKAGE = "org.spongepowered.asm.mixin.";
    /** "... from mod modifiedbiomesource->@Inject::..." */
    private static final Pattern FROM_MOD = Pattern.compile("from mod ([A-Za-z0-9_.\\-]+?)(?:->|\\s|$|\\])");
    /** "... on modifiedbiomesource.mixins.json:MinecraftServerLMixin ..." */
    private static final Pattern CONFIG = Pattern.compile("([A-Za-z0-9_.\\-]+)\\.mixins?\\.json");

    private ForeignMixinFailure() {
    }

    /** True when {@code event} is another mod's mixin failing to apply, and should not be reported. */
    static boolean matches(SentryEvent event) {
        boolean foreign = false;
        List<SentryException> exceptions = event.getExceptions();
        if (exceptions != null) {
            for (SentryException ex : exceptions) {
                String module = ex.getModule();
                String type = ex.getType();
                boolean mixin = (module != null && (module + ".").startsWith(MIXIN_PACKAGE))
                        || (type != null && type.startsWith(MIXIN_PACKAGE));
                if (mixin) {
                    int verdict = owners(ex.getValue());
                    if (verdict < 0) {
                        return false; // names one of our own mixin configs: a real Neroland bug
                    }
                    foreign |= verdict > 0;
                }
            }
        }
        for (Throwable t = event.getThrowable(), last = null; t != null && t != last; last = t, t = t.getCause()) {
            if (t.getClass().getName().startsWith(MIXIN_PACKAGE)) {
                int verdict = owners(t.getMessage());
                if (verdict < 0) {
                    return false;
                }
                foreign |= verdict > 0;
            }
        }
        return foreign;
    }

    /** -1 if the message names a Neroland owner, 1 if it names only foreign owners, 0 if it names none. */
    private static int owners(String message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }
        boolean any = false;
        for (Pattern p : new Pattern[] {FROM_MOD, CONFIG}) {
            Matcher m = p.matcher(message);
            while (m.find()) {
                any = true;
                if (m.group(1).toLowerCase(Locale.ROOT).startsWith("nero")) {
                    return -1;
                }
            }
        }
        return any ? 1 : 0;
    }
}
