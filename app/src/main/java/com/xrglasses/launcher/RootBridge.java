package com.xrglasses.launcher;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class RootBridge {
    @FunctionalInterface
    interface CommandExecutor { Result execute(String command, long timeoutMs); }
    private final CommandExecutor backExecutor;
    public RootBridge() { backExecutor = this::execute; }
    RootBridge(CommandExecutor executor) { backExecutor = executor; }

    public enum Status { SUCCESS, ROOT_DENIED, TIMEOUT, COMMAND_UNSUPPORTED, FAILED }

    public static final class Result {
        public final Status status;
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(Status status, int exitCode, String stdout, String stderr) {
            this.status = status;
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean ok() { return status == Status.SUCCESS; }

        @Override public String toString() {
            return status + "(" + exitCode + "): " + stdout + " " + stderr;
        }
    }

    public boolean isRootAvailable() {
        return execute("id", 1500).ok();
    }

    public Result execute(String command, long timeoutMs) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(false)
                    .start();
            // Drain concurrently: waiting before reading can deadlock on a full pipe.
            OutputCollector out = new OutputCollector(process.getInputStream());
            OutputCollector err = new OutputCollector(process.getErrorStream());
            Thread outThread = new Thread(out, "XRRoot-stdout");
            Thread errThread = new Thread(err, "XRRoot-stderr");
            outThread.setDaemon(true); errThread.setDaemon(true);
            outThread.start(); errThread.start();
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(Status.TIMEOUT, -1, "", "timeout");
            }
            outThread.join(500); errThread.join(500);
            String stdout = out.text();
            String stderr = err.text();
            int exitCode = process.exitValue();
            if (exitCode == 0) return new Result(Status.SUCCESS, exitCode, stdout, stderr);

            String lower = (stderr + " " + stdout).toLowerCase(Locale.ROOT);
            if (lower.contains("not found") || lower.contains("unknown command")
                    || lower.contains("invalid option") || lower.contains("usage:")) {
                return new Result(Status.COMMAND_UNSUPPORTED, exitCode, stdout, stderr);
            }
            if (lower.contains("permission denied") || lower.contains("denied")
                    || lower.contains("no root")) {
                return new Result(Status.ROOT_DENIED, exitCode, stdout, stderr);
            }
            return new Result(Status.FAILED, exitCode, stdout, stderr);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(Status.FAILED, -1, "", error.toString());
        } finally {
            if (process != null) {
                process.destroy();
                try { process.getInputStream().close(); } catch (Exception ignored) { }
                try { process.getErrorStream().close(); } catch (Exception ignored) { }
                try { process.getOutputStream().close(); } catch (Exception ignored) { }
            }
        }
    }

    public Result executeInputCommand(String command) {
        return execute("cmd input " + command, 1200);
    }

    /**
     * Send Back to the external display only.
     *
     * On this ColorOS build the display selector must appear before the
     * command name ("input -d 35 keyevent KEYCODE_BACK"). The seemingly
     * equivalent "input keyevent -d 35 ..." form exits with code 0 but is not
     * routed to the external display, which makes it look like Back was sent
     * to the phone. Never fall back to a display-less key event here.
     */
    public Result executeExternalBack(int displayId) {
        if (displayId <= 0) return new Result(Status.FAILED, -1, "", "invalid external display");
        Result last = null;
        String[] commands = externalBackCommands(displayId);
        /* Commands must always include a positive target display. */
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        for (String command : commands) {
            // Root authorization may open a user prompt. Do not kill it after 1.5 seconds.
            last = backExecutor.execute(command, 12000);
            if (stdout.length() > 0) stdout.append('\n');
            if (stderr.length() > 0) stderr.append('\n');
            stdout.append(command).append(" => ").append(last.stdout);
            stderr.append(command).append(" => ").append(last.stderr);
            if (last.ok()) {
                return new Result(Status.SUCCESS, last.exitCode,
                        "executed: " + command + "\n" + stdout,
                        stderr.toString());
            }
            // Retrying authorization failures would reopen dialogs or inject a duplicate Back.
            if (last.status != Status.COMMAND_UNSUPPORTED) break;
        }
        return new Result(last == null ? Status.FAILED : last.status,
                last == null ? -1 : last.exitCode, stdout.toString(), stderr.toString());
    }

    static String[] externalBackCommands(int displayId) {
        if (displayId <= 0) throw new IllegalArgumentException("external display must be positive");
        return new String[] {
                "cmd input -d " + displayId + " keyevent KEYCODE_BACK",
                "cmd input -d " + displayId + " keyevent 4",
                "input -d " + displayId + " keyevent KEYCODE_BACK",
                "input -d " + displayId + " keyevent 4"
        };
    }

    public Result setTemporaryBrightness(int value) {
        return execute("settings put system screen_brightness "
                + Math.max(1, Math.min(255, value)), 1500);
    }

    public Result restoreBrightness(int value) { return setTemporaryBrightness(value); }

    public String getBridgeDiagnostics() {
        return execute("id; cmd input help | head -n 30", 2000).toString();
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder();
        OutputCollector(InputStream stream) { this.stream = stream; }
        public void run() {
            try (InputStreamReader reader = new InputStreamReader(stream)) {
                char[] chars = new char[1024];
                int count;
                while ((count = reader.read(chars)) >= 0) {
                    synchronized (buffer) {
                        int keep = Math.min(count, Math.max(0, 16384 - buffer.length()));
                        buffer.append(chars, 0, keep);
                    }
                }
            } catch (Exception ignored) { /* Closing a timed-out process is expected. */ }
        }
        String text() { synchronized (buffer) { return buffer.toString().trim(); } }
    }
}
