package dev.tapetum.shaders.shaderpack;

import java.util.*;
import java.util.regex.Pattern;

/** Editable source options. Conflicting declarations are excluded, never guessed. */
public final class ShaderPackOptions {
    private static final Pattern DEFINE = Pattern.compile(
        "^\\h*(//\\h*)?#\\h*define\\h+([A-Za-z_][A-Za-z0-9_]*)(?:\\h+([^/\\r\\n]*?))?\\h*(?://(.*))?$");
    private static final Pattern RANGE = Pattern.compile("\\[([^\\[\\]]+)\\]");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_+\\.\\-]+");
    private static final Pattern CONSTANT = Pattern.compile(
        "^\\h*const\\h+(bool|int|float)\\h+([A-Za-z_][A-Za-z0-9_]*)\\h*=\\h*([^;]+);\\h*(?://(.*))?$");
    private static final Pattern CONDITION = Pattern.compile("(?m)^\\h*#\\h*(?:if|elif|ifdef|ifndef)\\b([^\\r\\n]*)");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private final Map<String, Option> options;
    private final Map<String, String> constantTypes;

    public record Option(String name, String defaultValue, List<String> values, boolean toggle, String comment) {
        public Option { values = List.copyOf(values); }
    }

    private ShaderPackOptions(Map<String, Option> options, Map<String, String> constantTypes) {
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        this.constantTypes = Map.copyOf(constantTypes);
    }

    public static ShaderPackOptions parse(Collection<String> sources) {
        Map<String, Option> found = new TreeMap<>();
        Map<String, String> constantTypes = new HashMap<>();
        Set<String> conflicts = new HashSet<>();
        Set<String> references = new HashSet<>();
        var cleaned = sources.stream().map(ShaderPackOptions::withoutBlockComments).toList();
        for (String clean : cleaned) {
            var conditions = CONDITION.matcher(clean.replaceAll("(?m)//[^\\r\\n]*", ""));
            while (conditions.find()) {
                var identifiers = IDENTIFIER.matcher(conditions.group(1));
                while (identifiers.find()) references.add(identifiers.group());
            }
        }
        for (String clean : cleaned) {
            var guard = Pattern.compile("(?s)\\A\\s*#\\h*ifndef\\h+([A-Za-z_][A-Za-z0-9_]*)\\h*\\R\\s*#\\h*define\\h+\\1\\b.*")
                .matcher(clean.replaceAll("(?m)//[^\\r\\n]*", ""));
            String includeGuard = guard.matches() ? guard.group(1) : "";
            for (String line : clean.split("\\R")) {
                var constant = CONSTANT.matcher(line);
                if (constant.matches()) {
                    String type = constant.group(1), name = constant.group(2), value = constant.group(3).trim();
                    String comment = constant.group(4) == null ? "" : constant.group(4).trim();
                    var range = RANGE.matcher(comment);
                    // Only author-marked constants are settings; internal constants must stay private.
                    if (!range.find() || !literal(type, value)) continue;
                    var values = new LinkedHashSet<>(List.of(range.group(1).trim().split("\\s+")));
                    if (values.stream().anyMatch(v -> !literal(type, v))) continue;
                    values.add(value);
                    var option = new Option(name, value, List.copyOf(values), type.equals("bool"), comment);
                    var previous = found.putIfAbsent(name, option);
                    String previousType = constantTypes.putIfAbsent(name, type);
                    if (previous != null && (!sameDeclaration(previous, option) || !type.equals(previousType)))
                        conflicts.add(name);
                    continue;
                }
                var match = DEFINE.matcher(line);
                if (!match.matches()) continue;
                String name = match.group(2);
                if (name.equals(includeGuard) || name.startsWith("MC_") || name.startsWith("GL_")) continue;
                String value = match.group(3) == null ? "" : match.group(3).trim();
                String comment = match.group(4) == null ? "" : match.group(4).trim();
                boolean toggle = value.isEmpty();
                List<String> values;
                if (toggle) {
                    if (!references.contains(name)) continue;
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
                if (constantTypes.containsKey(name) || previous != null && !sameDeclaration(previous, option)) conflicts.add(name);
            }
        }
        conflicts.forEach(found::remove);
        return new ShaderPackOptions(found, constantTypes);
    }

    private static boolean sameDeclaration(Option a, Option b) {
        return a.defaultValue().equals(b.defaultValue()) && a.toggle() == b.toggle() && a.values().equals(b.values());
    }

    private static boolean literal(String type, String value) {
        return switch (type) {
            case "bool" -> value.equals("true") || value.equals("false");
            case "int" -> value.matches("[+-]?[0-9]+");
            case "float" -> value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?[fF]?");
            default -> false;
        };
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
            var constant = CONSTANT.matcher(clean[i]);
            if (constant.matches() && CONSTANT.matcher(lines[i]).matches()) {
                String name = constant.group(2), value = overrides.get(name);
                if (value != null && constant.group(1).equals(constantTypes.get(name))
                        && constant.group(3).trim().equals(options.get(name).defaultValue()))
                    lines[i] = "const " + constant.group(1) + " " + name + " = " + value + ";";
                continue;
            }
            var match = DEFINE.matcher(clean[i]);
            if (!match.matches()) continue;
            if (!DEFINE.matcher(lines[i]).matches()) continue;
            String name = match.group(2);
            if (constantTypes.containsKey(name)) continue;
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
