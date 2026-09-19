package dev.tapetum.shaders.shaderpack.glsl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The preprocessor macros a shaderpack loader is expected to hand every program, and which packs
 * gate real behaviour on.
 *
 * <p>These are part of the OptiFine shaderpack contract, not an extra: a pack asks
 * {@code #if MC_VERSION >= 260200} to pick a code path, and nothing else defines {@code MC_VERSION}
 * for it. Leaving them out is not merely a lost feature — on a strict driver it fails the whole
 * compile. Apple's OpenGL rejects {@code #if MC_VERSION >= 260200} outright with "syntax error:
 * incorrect preprocessor directive" when the macro is undefined, rather than treating it as 0 the
 * way the C preprocessor specifies. That was a real, observed failure of Complementary Unbound on an
 * Apple M1, and it is the reason this class exists.</p>
 *
 * <p><b>This loader identifies as OptiFine-like, not as Iris.</b> {@code IS_IRIS} is deliberately
 * left undefined so packs take their non-Iris path, since none of Iris' extensions are implemented
 * here. {@code IRIS_VERSION} <em>is</em> defined, as 0: packs only ever compare it numerically
 * ({@code #if IRIS_VERSION >= 10800}), never with {@code #ifdef}, so a value of zero reads as "no
 * Iris" while still giving strict drivers something to evaluate.</p>
 */
public final class ShaderMacros {
	/** Minecraft's own version, encoded the way OptiFine packs expect. */
	private final int minecraftVersion;
	private final OperatingSystem operatingSystem;
	private final GlVendor glVendor;

	public enum OperatingSystem {
		WINDOWS("MC_OS_WINDOWS"),
		MAC("MC_OS_MAC"),
		LINUX("MC_OS_LINUX"),
		OTHER("MC_OS_OTHER");

		private final String macro;

		OperatingSystem(String macro) {
			this.macro = macro;
		}

		public String macro() {
			return macro;
		}

		/** Maps a {@code os.name} system property onto the macro OptiFine packs test for. */
		public static OperatingSystem detect(String osName) {
			if (osName == null) {
				return OTHER;
			}
			String normalized = osName.toLowerCase(Locale.ROOT);

			if (normalized.contains("win")) {
				return WINDOWS;
			}
			if (normalized.contains("mac") || normalized.contains("darwin")) {
				return MAC;
			}
			if (normalized.contains("linux") || normalized.contains("unix")) {
				return LINUX;
			}
			return OTHER;
		}
	}

	public enum GlVendor {
		NVIDIA("MC_GL_VENDOR_NVIDIA"),
		AMD("MC_GL_VENDOR_AMD"),
		ATI("MC_GL_VENDOR_ATI"),
		INTEL("MC_GL_VENDOR_INTEL"),
		APPLE("MC_GL_VENDOR_APPLE"),
		MESA("MC_GL_VENDOR_MESA"),
		OTHER("MC_GL_VENDOR_OTHER");

		private final String macro;

		GlVendor(String macro) {
			this.macro = macro;
		}

		public String macro() {
			return macro;
		}

		/** Maps the driver's {@code GL_VENDOR} string onto the macro OptiFine packs test for. */
		public static GlVendor detect(String vendorString) {
			if (vendorString == null) {
				return OTHER;
			}
			String normalized = vendorString.toLowerCase(Locale.ROOT);

			if (normalized.contains("nvidia")) {
				return NVIDIA;
			}
			// Check ATI before AMD: "ATI Technologies Inc." contains neither the word "amd" nor a
			// conflict, but AMD's own strings sometimes carry both spellings.
			if (normalized.contains("ati ") || normalized.startsWith("ati")) {
				return ATI;
			}
			if (normalized.contains("amd") || normalized.contains("advanced micro devices")) {
				return AMD;
			}
			if (normalized.contains("intel")) {
				return INTEL;
			}
			if (normalized.contains("apple")) {
				return APPLE;
			}
			if (normalized.contains("mesa") || normalized.contains("x.org")) {
				return MESA;
			}
			return OTHER;
		}
	}

	public ShaderMacros(int minecraftVersion, OperatingSystem operatingSystem, GlVendor glVendor) {
		this.minecraftVersion = minecraftVersion;
		this.operatingSystem = operatingSystem;
		this.glVendor = glVendor;
	}

	/**
	 * Encodes a Minecraft version string the way OptiFine packs compare it: major, minor and patch
	 * packed as {@code major * 10000 + minor * 100 + patch}.
	 *
	 * <p>The encoding is not guesswork — it is pinned by the comparisons packs actually ship:
	 * Complementary tests {@code MC_VERSION >= 11605} for 1.16.5, {@code < 12109} for 1.21.9, and
	 * {@code >= 260200} for 26.2, all of which only line up under this formula.</p>
	 *
	 * <p>Returns 0 for a version string that cannot be parsed, which reads to a pack as "older than
	 * anything" — the conservative direction, since it makes version-gated new features stay off
	 * rather than switching on against a version that may not support them.</p>
	 */
	public static int encodeMinecraftVersion(String version) {
		if (version == null || version.isBlank()) {
			return 0;
		}

		// Take only the leading dotted-number run, so a prerelease suffix contributes nothing.
		// Splitting the whole string on non-digits instead would read the "1" of "26.2-rc1" as a
		// patch number, making a release candidate report a different version than its own release.
		java.util.regex.Matcher leadingNumbers =
			java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(version.trim());
		if (!leadingNumbers.find()) {
			return 0;
		}

		String[] parts = leadingNumbers.group(1).split("\\.");
		int major = 0;
		int minor = 0;
		int patch = 0;
		int seen = 0;

		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			int value;
			try {
				value = Integer.parseInt(part);
			} catch (NumberFormatException e) {
				return 0;
			}

			if (seen == 0) {
				major = value;
			} else if (seen == 1) {
				minor = value;
			} else if (seen == 2) {
				patch = value;
			} else {
				break;
			}
			seen++;
		}

		return seen == 0 ? 0 : major * 10000 + minor * 100 + patch;
	}

	/**
	 * The {@code #define} block to place directly after {@code #version}, one directive per line and
	 * terminated by a newline (empty string if there is nothing to define).
	 */
	/**
	 * OptiFine's render-stage identifiers, in its documented order.
	 *
	 * <p>Packs branch on {@code renderStage == MC_RENDER_STAGE_STARS} and friends. The names have to
	 * exist even when the branch is never taken: an undefined identifier inside {@code #if} is a hard
	 * error on Apple's driver rather than the zero a C preprocessor would assume.</p>
	 *
	 * <p>{@code renderStage} itself is not supplied yet, so it reads as 0 — {@code NONE} — and every
	 * stage-specific branch is simply not taken. That is the right behaviour until the geometry
	 * programs actually run per stage.</p>
	 */
	private static final String[] RENDER_STAGES = {
		"#define MC_RENDER_STAGE_NONE 0",
		"#define MC_RENDER_STAGE_SKY 1",
		"#define MC_RENDER_STAGE_SUNSET 2",
		"#define MC_RENDER_STAGE_CUSTOM_SKY 3",
		"#define MC_RENDER_STAGE_SUN 4",
		"#define MC_RENDER_STAGE_MOON 5",
		"#define MC_RENDER_STAGE_STARS 6",
		"#define MC_RENDER_STAGE_VOID 7",
		"#define MC_RENDER_STAGE_TERRAIN_SOLID 8",
		"#define MC_RENDER_STAGE_TERRAIN_CUTOUT_MIPPED 9",
		"#define MC_RENDER_STAGE_TERRAIN_CUTOUT 10",
		"#define MC_RENDER_STAGE_ENTITIES 11",
		"#define MC_RENDER_STAGE_BLOCK_ENTITIES 12",
		"#define MC_RENDER_STAGE_DESTROY 13",
		"#define MC_RENDER_STAGE_OUTLINE 14",
		"#define MC_RENDER_STAGE_DEBUG 15",
		"#define MC_RENDER_STAGE_HAND_SOLID 16",
		"#define MC_RENDER_STAGE_TERRAIN_TRANSLUCENT 17",
		"#define MC_RENDER_STAGE_TRIPWIRE 18",
		"#define MC_RENDER_STAGE_PARTICLES 19",
		"#define MC_RENDER_STAGE_CLOUDS 20",
		"#define MC_RENDER_STAGE_RAIN_SNOW 21",
		"#define MC_RENDER_STAGE_WORLD_BORDER 22",
		"#define MC_RENDER_STAGE_HAND_TRANSLUCENT 23",
	};

	public String toDefineBlock() {
		Map<String, String> defines = new LinkedHashMap<>();

		defines.put("MC_VERSION", Integer.toString(minecraftVersion));
		defines.put(operatingSystem.macro(), "");
		defines.put(glVendor.macro(), "");

		// Not Iris: IS_IRIS stays undefined so packs take their OptiFine path, but the numeric
		// comparisons packs make against IRIS_VERSION still need a value to evaluate. See class docs.
		defines.put("IRIS_VERSION", "0");

		StringBuilder block = new StringBuilder();
		for (Map.Entry<String, String> define : defines.entrySet()) {
			block.append("#define ").append(define.getKey());
			if (!define.getValue().isEmpty()) {
				block.append(' ').append(define.getValue());
			}
			block.append('\n');
		}
		for (String stage : RENDER_STAGES) {
			block.append(stage).append('\n');
		}
		return block.toString();
	}
}
