package dev.tapetum.shaders.shaderpack.glsl;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pixel format a pack asks for its {@code colortex} buffers, declared in its own source as
 * {@code const int colortex1Format = RGB8_SNORM;}.
 *
 * <p>These are not GLSL — the right-hand side is a format enum name no compiler can resolve, which
 * is why {@link GlslCompatPatcher} strips the declarations. But they are not decoration either, and
 * allocating every buffer as {@code RGBA8} regardless gets two things wrong:</p>
 *
 * <ul>
 *   <li><b>Signed formats.</b> Packs store normals in {@code RGB8_SNORM}, whose range is -1 to 1.
 *       Held in an unsigned buffer every negative component clamps to zero, so half of every
 *       normal is silently lost and all lighting derived from it is wrong.</li>
 *   <li><b>Floating-point formats.</b> {@code colortex0Format = R11F_G11F_B10F} is the HDR scene
 *       buffer. In {@code RGBA8} everything brighter than white clips at 1.0, so the sun, the sky at
 *       sunset and every bloom source flatten before tone mapping ever sees them.</li>
 * </ul>
 *
 * <p>Only formats that are colour-renderable are listed. A pack naming something outside this set
 * gets the default rather than a failed load — an unknown format name is a reason to log and carry
 * on, not to refuse the pack.</p>
 */
public enum ColorTextureFormat {
	// 8-bit normalised
	R8, RG8, RGB8, RGBA8,
	R8_SNORM, RG8_SNORM, RGB8_SNORM, RGBA8_SNORM,
	// 16-bit normalised
	R16, RG16, RGB16, RGBA16,
	R16_SNORM, RG16_SNORM, RGB16_SNORM, RGBA16_SNORM,
	// floating point
	R16F, RG16F, RGB16F, RGBA16F,
	R32F, RG32F, RGB32F, RGBA32F,
	R11F_G11F_B10F, RGB9_E5,
	// packed and sRGB
	RGB565, RGB5_A1, RGBA4, RGB10_A2, SRGB8, SRGB8_ALPHA8,
	// integer
	R8I, R8UI, R16I, R16UI, R32I, R32UI,
	RG8I, RG8UI, RG16I, RG16UI, RG32I, RG32UI,
	RGBA8I, RGBA8UI, RGBA16I, RGBA16UI, RGBA32I, RGBA32UI;

	/** What a buffer gets when the pack names no format, matching OptiFine's own default. */
	public static final ColorTextureFormat DEFAULT = RGBA8;

	/**
	 * A declaration OptiFine reads as configuration. The {@code gaux} spellings are the pre-1.17
	 * names for {@code colortex4} through {@code colortex7}, still shipped by packs derived from
	 * older sources.
	 *
	 * <p>The optional comment prefix matters: {@link GlslCompatPatcher} comments these lines out
	 * before the source is compilable, keeping the original text so the line numbers a driver reports
	 * still line up. Reading the patched source is what the pipeline actually has to hand, so the
	 * commented form has to parse too — otherwise every pack silently reports no formats at all.</p>
	 */
	private static final Pattern DECLARATION = Pattern.compile(
		"^\\s*(?://\\s*\\[tapetum\\][^:]*:\\s*)?"
			+ "const\\s+int\\s+(?:colortex|gaux)(\\d+)Format\\s*=\\s*([A-Za-z0-9_]+)\\s*;");

	/** {@code gaux1} is {@code colortex4}, and so on up to {@code gaux4} being {@code colortex7}. */
	private static final int GAUX_OFFSET = 3;

	/** Whether this format holds signed values, so -1 to 1 survives rather than clamping at zero. */
	public boolean isSigned() {
		return name().endsWith("_SNORM") || name().endsWith("I") && !name().endsWith("UI");
	}

	/** Whether this format holds values outside 0 to 1, which tone mapping depends on. */
	public boolean isHighDynamicRange() {
		return name().endsWith("F") || this == RGB9_E5;
	}

	/** Parses one format name, or empty when the pack names something not renderable here. */
	public static Optional<ColorTextureFormat> parseName(String name) {
		for (ColorTextureFormat format : values()) {
			if (format.name().equalsIgnoreCase(name)) {
				return Optional.of(format);
			}
		}
		return Optional.empty();
	}

	/**
	 * Every format the given (include-expanded) source declares, keyed by {@code colortex} index.
	 *
	 * <p>Declarations inside a disabled {@code #ifdef} are read too. That errs the right way: the
	 * cost of allocating a buffer at a wider format than the active branch needs is memory, whereas
	 * the cost of allocating one too narrow is data the pack cannot get back.</p>
	 */
	public static Map<Integer, ColorTextureFormat> parse(String source) {
		Map<Integer, ColorTextureFormat> formats = new TreeMap<>();

		for (String line : source.split("\n", -1)) {
			Matcher matcher = DECLARATION.matcher(line);
			if (!matcher.find()) {
				continue;
			}

			int declared = Integer.parseInt(matcher.group(1));
			int index = line.contains("gaux") ? declared + GAUX_OFFSET : declared;

			parseName(matcher.group(2)).ifPresent(format -> formats.put(index, format));
		}

		return formats;
	}
}
