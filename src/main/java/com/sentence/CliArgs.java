package com.sentence;

import java.util.ArrayList;
import java.util.List;

/** 命令行参数。 */
public final class CliArgs {
    public boolean selfTest;
    public boolean smokeTest;
    public boolean liveTest;
    public boolean debug;
    public boolean noDialogs;
    public String appRoot;
    public String dataDir;
    public String snapshot;
    public String parityFile;
    public String model;

    public static CliArgs parse(String[] args) {
        CliArgs a = new CliArgs();
        List<String> rest = new ArrayList<>();
        for (String raw : args == null ? new String[0] : args) {
            if (raw == null) continue;
            if ("--self-test".equals(raw)) a.selfTest = true;
            else if ("--smoke-test".equals(raw)) { a.smokeTest = true; a.noDialogs = true; }
            else if ("--live-test".equals(raw)) { a.liveTest = true; a.noDialogs = true; }
            else if ("--debug".equals(raw)) a.debug = true;
            else if ("--no-dialogs".equals(raw)) a.noDialogs = true;
            else if (raw.startsWith("--app-root=")) a.appRoot = raw.substring("--app-root=".length());
            else if (raw.startsWith("--data-dir=")) a.dataDir = raw.substring("--data-dir=".length());
            else if (raw.startsWith("--snapshot=")) a.snapshot = raw.substring("--snapshot=".length());
            else if (raw.startsWith("--parity-file=")) a.parityFile = raw.substring("--parity-file=".length());
            else if (raw.startsWith("--model=")) a.model = raw.substring("--model=".length());
            else rest.add(raw);
        }
        if (!rest.isEmpty()) {
            System.err.println("忽略未知参数: " + rest);
        }
        return a;
    }
}
