package dev.compileloop.repair;

import dev.compileloop.build.BuildResult;
import dev.compileloop.build.ParsedBuild;
import dev.compileloop.repair.RepairResult.AttemptRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the JSON documents of a repair run. Pure: no I/O. */
final class RepairReport {

    static final int SCHEMA_VERSION = 1;

    private RepairReport() {
    }

    static String resultJson(RepairResult r, Instant startedAt, Map<String, Object> config) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", SCHEMA_VERSION);
        m.put("runId", r.runId());
        m.put("startedAt", startedAt.toString());
        m.put("verdict", r.verdict());
        m.put("humanApprovalRequired", r.verdict() == RepairResult.Verdict.VERIFIED_CANDIDATE);
        m.put("message", r.message());
        m.put("config", config);
        m.put("baseline", r.baseline() == null ? null : buildJson(r.baseline()));
        List<Object> attempts = new ArrayList<>();
        for (AttemptRecord a : r.attempts()) {
            attempts.add(attemptMap(a, false));
        }
        m.put("attempts", attempts);
        m.put("patchFile", r.patchFile() == null ? null : r.patchFile().getFileName().toString());
        m.put("warnings", r.warnings());
        m.put("note", "A passing build is evidence, not proof of correctness. The patch was never applied to your "
                + "working tree. Review it before applying.");
        return JsonWriter.toJson(m);
    }

    static String attemptJson(AttemptRecord a) {
        return JsonWriter.toJson(attemptMap(a, true));
    }

    private static Map<String, Object> attemptMap(AttemptRecord a, boolean full) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("number", a.number());
        m.put("status", a.status());
        m.put("durationMillis", a.durationMillis());
        m.put("detail", a.detail());
        m.put("summary", a.summary());
        m.put("assumptions", a.assumptions());
        m.put("modifiesTests", a.modifiesTests());
        m.put("diffChars", a.diff() == null ? 0 : a.diff().length());
        if (a.aiErrorKind() != null) {
            Map<String, Object> ai = new LinkedHashMap<>();
            ai.put("kind", a.aiErrorKind());
            ai.put("message", a.aiErrorMessage());
            m.put("aiError", ai);
        }
        if (a.validation() != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("accepted", a.validation().accepted());
            List<Object> vs = new ArrayList<>();
            for (ValidationResult.Violation x : a.validation().violations()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("code", x.code());
                o.put("path", x.path());
                o.put("message", x.message());
                vs.add(o);
            }
            v.put("violations", vs);
            m.put("validation", v);
        }
        if (a.apply() != null) {
            Map<String, Object> ap = new LinkedHashMap<>();
            ap.put("applied", a.apply().applied());
            ap.put("errors", a.apply().errors());
            List<Object> cs = new ArrayList<>();
            for (PatchApplier.FileChange c : a.apply().changes()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("path", c.path());
                o.put("kind", c.kind());
                o.put("added", c.added());
                o.put("removed", c.removed());
                cs.add(o);
            }
            ap.put("changes", cs);
            m.put("apply", ap);
        }
        if (a.build() != null) {
            m.put("build", buildJson(a.build()));
        }
        if (full) {
            m.put("rawResponseFile", "response.txt");
            m.put("diffFile", "proposal.diff");
        }
        return m;
    }

    private static Map<String, Object> buildJson(BuildResult b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", b.outcome());
        m.put("exitCode", b.exitCode());
        m.put("durationMillis", b.durationMillis());
        ParsedBuild p = b.parsed();
        m.put("compileErrors", p.compileErrors().size());
        m.put("testFailures", p.testFailures().size());
        p.testSummary().ifPresent(s -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("run", s.run());
            t.put("failures", s.failures());
            t.put("errors", s.errors());
            t.put("skipped", s.skipped());
            m.put("tests", t);
        });
        return m;
    }
}