package top.gregtao.concerto.velocity;

import org.slf4j.Logger;

public final class VelocityLoggerFactory implements top.gregtao.concerto.core.bridge.Logger.Factory {
    private final Logger root;

    public VelocityLoggerFactory(Logger root) {
        this.root = root;
    }

    @Override
    public top.gregtao.concerto.core.bridge.Logger getLogger(String name) {
        Logger logger = this.root;
        return new top.gregtao.concerto.core.bridge.Logger() {
            @Override public void debug(String message) { logger.debug("[{}] {}", name, message); }
            @Override public void debug(String message, Throwable t) { logger.debug("[{}] {}", name, message, t); }
            @Override public void debug(String format, Object... args) { logger.debug("[{}] {}", name, format(format, args)); }
            @Override public void info(String message) { logger.info("[{}] {}", name, message); }
            @Override public void info(String message, Throwable t) { logger.info("[{}] {}", name, message, t); }
            @Override public void info(String format, Object... args) { logger.info("[{}] {}", name, format(format, args)); }
            @Override public void error(String message) { logger.error("[{}] {}", name, message); }
            @Override public void error(String message, Throwable t) { logger.error("[{}] {}", name, message, t); }
            @Override public void error(String format, Object... args) { logger.error("[{}] {}", name, format(format, args)); }
            @Override public void warn(String message) { logger.warn("[{}] {}", name, message); }
            @Override public void warn(String message, Throwable t) { logger.warn("[{}] {}", name, message, t); }
            @Override public void warn(String format, Object... args) { logger.warn("[{}] {}", name, format(format, args)); }
        };
    }

    private static String format(String template, Object... args) {
        if (args.length == 0) return template;
        StringBuilder result = new StringBuilder(template.length() + args.length * 8);
        int cursor = 0;
        int argument = 0;
        while (argument < args.length) {
            int placeholder = template.indexOf("{}", cursor);
            if (placeholder < 0) break;
            result.append(template, cursor, placeholder).append(args[argument++]);
            cursor = placeholder + 2;
        }
        result.append(template, cursor, template.length());
        if (argument < args.length) {
            result.append(' ').append(java.util.Arrays.toString(
                    java.util.Arrays.copyOfRange(args, argument, args.length)));
        }
        return result.toString();
    }
}
