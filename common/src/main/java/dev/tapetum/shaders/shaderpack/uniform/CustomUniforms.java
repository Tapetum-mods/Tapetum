package dev.tapetum.shaders.shaderpack.uniform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The uniforms a shaderpack defines for itself in {@code shaders.properties}.
 *
 * <p>OptiFine lets a pack compute its own values from the loader's: BSL declares forty-one of them,
 * MakeUp thirty, Mellow thirty-two. The GPU audit found eight of BSL's arriving as zero, including
 * {@code timeBrightness} — a daylight multiplier, so zero means the pack's sun never comes up.</p>
 *
 * <p>Parsed from the raw file text rather than through {@link java.util.Properties}, which would
 * lose both things this needs. <b>Declaration order matters:</b> BSL's {@code timeBrightness} sits
 * at the end of a five-deep chain of intermediates, each defined on the line before the one that
 * reads it. <b>Duplicate keys matter too:</b> BSL declares thirteen names twice — once against
 * named {@code BIOME_*} constants and once against numeric ids — and the later definition is the
 * one that must win.</p>
 */
public final class CustomUniforms {

	/**
	 * One definition. {@code variable.*} entries feed later expressions but are never sent to the
	 * GPU; only {@code uniform.*} entries are.
	 */
	public record Definition(String name, String type, boolean uploaded, Expression expression) {
	}

	private static final Pattern DEFINITION = Pattern.compile(
		"^\\s*(uniform|variable)\\.(\\w+)\\.(\\w+)\\s*=\\s*(.*)$");

	private final List<Definition> definitions;

	private CustomUniforms(List<Definition> definitions) {
		this.definitions = definitions;
	}

	public List<Definition> definitions() {
		return definitions;
	}

	public boolean isEmpty() {
		return definitions.isEmpty();
	}

	/** Only the entries that are actually uploaded, in declaration order. */
	public List<Definition> uploaded() {
		return definitions.stream().filter(Definition::uploaded).toList();
	}

	/**
	 * Parses every definition in the file.
	 *
	 * <p>An expression that fails to parse is dropped with its name recorded nowhere but the caller's
	 * log: one unparseable line is a feature of a newer OptiFine, not a reason to refuse the
	 * pack.</p>
	 */
	public static CustomUniforms parse(String propertiesText, List<String> unparseable) {
		Map<String, Definition> byName = new LinkedHashMap<>();

		for (String line : spliceContinuations(propertiesText)) {
			Matcher matcher = DEFINITION.matcher(line);
			if (!matcher.matches()) {
				continue;
			}

			String name = matcher.group(3);
			String body = stripComment(matcher.group(4));
			try {
				Definition definition = new Definition(name, matcher.group(2),
					"uniform".equals(matcher.group(1)), ExpressionParser.parse(body));
				// Re-inserting moves the entry to the end, which is what "the later one wins" means
				// when a later definition may also depend on values declared in between.
				byName.remove(name);
				byName.put(name, definition);
			} catch (RuntimeException e) {
				unparseable.add(name + " = " + body);
			}
		}

		return new CustomUniforms(List.copyOf(byName.values()));
	}

	/**
	 * Joins lines a backslash continues, the way a properties file does.
	 *
	 * <p>BSL needs this: its biome lists run to several hundred characters and are wrapped across
	 * four or five lines. Reading them line by line would parse each fragment separately and throw
	 * every one of them away.</p>
	 */
	private static List<String> spliceContinuations(String text) {
		List<String> lines = new ArrayList<>();
		StringBuilder current = new StringBuilder();

		for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
			if (raw.endsWith("\\")) {
				current.append(raw, 0, raw.length() - 1).append(' ');
				continue;
			}
			current.append(raw);
			lines.add(current.toString());
			current.setLength(0);
		}

		if (current.length() > 0) {
			lines.add(current.toString());
		}
		return lines;
	}

	/** Drops a trailing {@code #} comment, which packs use to annotate option ranges. */
	private static String stripComment(String body) {
		int comment = body.indexOf('#');
		return (comment < 0 ? body : body.substring(0, comment)).trim();
	}

	/**
	 * Evaluates every definition in order and returns the values to upload.
	 *
	 * <p>Each result becomes visible to the definitions after it, which is how a pack builds a value
	 * up through intermediates. A name the pack defines shadows one the loader supplies — that is
	 * OptiFine's semantics, and BSL relies on it for {@code timeAngle}, {@code shadowFade} and
	 * {@code blindFactor}.</p>
	 */
	public Map<String, Float> evaluate(EvaluationContext base) {
		Map<String, Float> computed = new LinkedHashMap<>();
		EvaluationContext chained = new EvaluationContext() {
			@Override
			public float value(String name) {
				Float own = computed.get(name);
				return own != null ? own : base.value(name);
			}

			@Override
			public float smooth(int id, float target, float fadeUp, float fadeDown) {
				return base.smooth(id, target, fadeUp, fadeDown);
			}
		};

		Map<String, Float> uploads = new LinkedHashMap<>();
		for (Definition definition : definitions) {
			float value = definition.expression().evaluate(chained);
			computed.put(definition.name(), value);
			if (definition.uploaded()) {
				uploads.put(definition.name(), value);
			}
		}
		return uploads;
	}
}
