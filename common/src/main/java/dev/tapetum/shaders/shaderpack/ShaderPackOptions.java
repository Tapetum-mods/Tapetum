package dev.tapetum.shaders.shaderpack;

import java.util.*;
import java.util.regex.Pattern;

/** Editable define options. Conflicting declarations are excluded, never guessed. */
public final class ShaderPackOptions {
    private static final Pattern DEFINE = Pattern.compile(
        "^\\h*(//\\h*)?#\\h*define\\h+([A-Za-z_][A-Za-z0-9_]*)(?:\\h+([^/\\r\\n]*?))?\\h*(?://(.*))?$");
    private static final Pattern RANGE = Pattern.compile("\\[([^\\[\\]]+)\\]");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_+\\.\\-]+");
    private final Map<String, Option> options;

    public record Option(String name, String defaultValue, List<String> values, boolean toggle, String comment) {
        public Option { values = List.copyOf(values); }
    }

    private ShaderPackOptions(Map<String, Option> options) {
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
    }

    public static ShaderPackOptions parse(Collection<String> sources) {
        Map<String, Option> found = new TreeMap<>();
        Set<String> conflicts = new HashSet<>();
        for (String source : sources) {
            String clean = withoutBlockComments(source);
            var guard = Pattern.compile("(?s)\\A\\s*#\\h*ifndef\\h+([A-Za-z_][A-Za-z0-9_]*)\\h*\\R\\s*#\\h*define\\h+\\1\\b.*")
                .matcher(clean.replaceAll("(?m)//[^\\r\\n]*", ""));
            String includeGuard = guard.matches() ? guard.group(1) : "";
            for (String line : clean.split("\\R")) {
                var match = DEFINE.matcher(line);
                if (!match.matches()) continue;
                String name = match.group(2);
                if (name.equals(includeGuard) || name.startsWith("MC_") || name.startsWith("GL_")) continue;
                String value = match.group(3) == null ? "" : match.group(3).trim();
                String comment = match.group(4) == null ? "" : match.group(4).trim();
                boolean toggle = value.isEmpty();
                List<String> values;
                if (toggle) {
                    if (!Pattern.compile("(?m)^\\h*#\\h*ifn?def\\h+" + Pattern.quote(name) + "\\b").matcher(clean).find())
                        continue;
                    value = match.group(1) == null ? "true" : "false";
                    values = List.of("false", "true");
                } else {
                    if (match.group(1) != null || !TOKEN.matcher(value).matches()) continue;
                    var range = RANGE.matcher(comment);
                    if (!range.find()) continue;
                    var allowed = new LinkedHashSet<>(List.of(range.group(1).trim().split("\\s+")));
                    if (allowed.stream().anyMatch(v -> !TOKEN.matcher(v).matches())) continue;
                    allowed.add(value);
                    values = List.copyOf(allowed);
                }
                var option = new Option(name, value, values, toggle, comment);
                var previous = found.putIfAbsent(name, option);
                if (previous != null && (!previous.defaultValue().equals(value)
                        || previous.toggle() != toggle || !previous.values().equals(values))) conflicts.add(name);
            }
        }
        conflicts.forEach(found::remove);
        return new ShaderPackOptions(found);
    }

    public List<Option> entries() { return List.copyOf(options.values()); }

    public Map<String, String> validate(Map<String, String> supplied) {
        Map<String, String> valid = new LinkedHashMap<>();
        supplied.forEach((name, value) -> {
            Option option = options.get(name);
            if (option != null && option.values().contains(value) && !option.defaultValue().equals(value))
                valid.put(name, value);
        });
        return Map.copyOf(valid);
    }

    /** Replaces declarations only, retaining conditions and line count for compiler diagnostics. */
    public String apply(String source, Map<String, String> supplied) {
        Map<String, String> overrides = validate(supplied);
        if (overrides.isEmpty()) return source;
        String[] lines = source.split("\n", -1);
        String[] clean = withoutBlockComments(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var match = DEFINE.matcher(clean[i]);
            if (!match.matches()) continue;
            if (!DEFINE.matcher(lines[i]).matches()) continue;
            String name = match.group(2);
            String value = overrides.get(name);
            if (value == null) continue;
            Option option = options.get(name);
            String declared = match.group(3) == null ? "" : match.group(3).trim();
            if (option.toggle() != declared.isEmpty()) continue;
            // Leave conditional redefinitions and derived macros untouched.
            if (!option.toggle() && (!declared.equals(option.defaultValue()) || match.group(1) != null)) continue;
            lines[i] = option.toggle()
                ? ("true".equals(value) ? "#define " : "// #define ") + name
                : "#define " + name + " " + value;
        }
        return String.join("\n", lines);
    }

    private static String withoutBlockComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean block = false, line = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (c == '\n' || c == '\r') { out.append(c); line = false; continue; }
            if (block) {
                if (c == '*' && next == '/') { block = false; out.append("  "); i++; }
                else out.append(' ');
            } else if (!line && c == '/' && next == '*') {
                block = true; out.append("  "); i++;
            } else {
                if (c == '/' && next == '/') line = true;
                out.append(c);
            }
        }
        return out.toString();
    }
}
