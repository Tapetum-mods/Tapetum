package dev.tapetum.shaders.shaderpack;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/** Author-defined option pages and validated presets, independent of Minecraft widgets. */
public final class ShaderPackMenu {
    public enum Kind { OPTION, PAGE, PROFILE }
    public record Entry(Kind kind, String name) { }
    private final ShaderPackOptions options;
    private final Map<String, List<Entry>> pages;
    private final Map<String, Map<String, String>> profiles;
    private final Properties labels;

    public ShaderPackMenu(ShaderPackOptions options, String properties, String language) {
        this.options = options;
        this.labels = parse(language);
        Properties config = parse(unconditional(properties));
        Set<String> names = new HashSet<>();
        options.entries().forEach(o -> names.add(o.name()));
        Map<String, List<Entry>> pages = new HashMap<>();
        for (String key : config.stringPropertyNames()) {
            if (!key.equals("screen") && (!key.startsWith("screen.") || key.endsWith(".columns"))) continue;
            List<Entry> entries = new ArrayList<>();
            for (String token : config.getProperty(key).trim().split("\\s+")) {
                if (token.equals("<profile>")) entries.add(new Entry(Kind.PROFILE, ""));
                else if (token.startsWith("[") && token.endsWith("]")) {
                    String child = token.substring(1, token.length() - 1);
                    if (config.containsKey("screen." + child)) entries.add(new Entry(Kind.PAGE, child));
                } else if (names.contains(token)) entries.add(new Entry(Kind.OPTION, token));
                else if (token.equals("*")) names.stream().sorted().forEach(name -> entries.add(new Entry(Kind.OPTION, name)));
            }
            pages.put(key.equals("screen") ? "" : key.substring(7), List.copyOf(new LinkedHashSet<>(entries)));
        }
        this.pages = Map.copyOf(pages);
        Map<String, Map<String, String>> profiles = new TreeMap<>();
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith("profile.")) continue;
            Map<String, String> values = resolveProfile(key.substring(8), config, new HashSet<>());
            if (values != null && !values.isEmpty()) profiles.put(key.substring(8), Map.copyOf(values));
        }
        this.profiles = Collections.unmodifiableMap(profiles);
    }

    private Map<String, String> resolveProfile(String name, Properties config, Set<String> visiting) {
        String definition = config.getProperty("profile." + name);
        if (definition == null || visiting.size() >= 32 || !visiting.add(name)) return null;
        try {
            Map<String, String> values = new LinkedHashMap<>();
            for (String token : definition.trim().split("\\s+")) {
                if (token.startsWith("profile.")) {
                    var inherited = resolveProfile(token.substring(8), config, visiting);
                    if (inherited == null) return null;
                    values.putAll(inherited);
                } else {
                    String[] pair = token.split("[:=]", 2);
                    String optionName = pair[0].startsWith("!") ? pair[0].substring(1) : pair[0];
                    String value = pair.length == 2 ? pair[1] : token.startsWith("!") ? "false" : "true";
                    var option = options.entries().stream().filter(o -> o.name().equals(optionName)).findFirst();
                    // Never offer a preset that would only partially apply (e.g. unsupported program switches).
                    if (option.isEmpty() || !option.get().values().contains(value)) return null;
                    values.put(optionName, value);
                }
            }
            return values;
        } finally { visiting.remove(name); }
    }

    public List<Entry> entries(String page) {
        return pages.getOrDefault(page, options.entries().stream().map(o -> new Entry(Kind.OPTION, o.name())).toList());
    }
    public List<String> profiles() { return List.copyOf(profiles.keySet()); }
    public Map<String, String> profile(String name) { return profiles.getOrDefault(name, Map.of()); }
    public String label(String key, String fallback) { return labels.getProperty(key, fallback); }

    private static Properties parse(String text) {
        var properties = new Properties();
        try { properties.load(new StringReader(text)); }
        catch (IOException | IllegalArgumentException error) { return new Properties(); }
        return properties;
    }

    private static String unconditional(String text) {
        // Conditional menus require the property preprocessor; keep the all-options view available meanwhile.
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.stripLeading();
            if (trimmed.matches("#\\s*(if|ifdef|ifndef)\\b.*")) depth++;
            else if (trimmed.matches("#\\s*endif\\b.*")) depth = Math.max(0, depth - 1);
            else if (depth == 0) result.append(line).append('\n');
        }
        return result.toString();
    }
}
