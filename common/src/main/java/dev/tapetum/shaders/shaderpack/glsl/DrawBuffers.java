package dev.tapetum.shaders.shaderpack.glsl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which {@code colortex} buffers a screen-space pass writes to, read from the directive the pass
 * declares in a comment.
 *
 * <p>Every composite/deferred pass announces its outputs to the loader this way, and the loader has
 * to obey: a pass whose {@code DRAWBUFFERS:3} lands in {@code colortex0} instead corrupts whatever
 * the next pass expected to find there, and the damage compounds down a nine-pass chain into an
 * image that is wrong in a way no single pass explains.</p>
 *
 * <p>Two spellings exist and both appear in the wild:</p>
 * <ul>
 *   <li>{@code /* DRAWBUFFERS:031 *&#47;} — one character per buffer. Used by every pack surveyed
 *       here (Complementary, BSL, MakeUp, Mellow).</li>
 *   <li>{@code /* RENDERTARGETS: 0,3,11 *&#47;} — comma-separated, the newer form, which is how a
 *       pack addresses buffers above 9 unambiguously.</li>
 * </ul>
 */
public final class DrawBuffers {
	/** OptiFine's ceiling: {@code colortex0}…{@code colortex15}. */
	public static final int MAX_BUFFER_INDEX = 15;

	/** What a pass writes when it declares nothing: {@code colortex0} alone. */
	public static final List<Integer> DEFAULT = List.of(0);

	private static final Pattern DRAWBUFFERS_DIRECTIVE =
		Pattern.compile("DRAWBUFFERS\\s*:\\s*([0-9a-fA-F]+)");

	private static final Pattern RENDERTARGETS_DIRECTIVE =
		Pattern.compile("RENDERTARGETS\\s*:\\s*([0-9]+(?:\\s*,\\s*[0-9]+)*)");

	private DrawBuffers() {
	}

	/**
	 * The buffers {@code source} writes, in declaration order, or {@link #DEFAULT} if it declares
	 * none.
	 *
	 * <p>Order is meaningful, not incidental: it maps each of the shader's fragment outputs to a
	 * buffer, so {@code DRAWBUFFERS:31} sends output 0 to {@code colortex3} and output 1 to
	 * {@code colortex1}. Sorting or de-ordering the list silently swaps a pass's outputs.</p>
	 *
	 * <p><b>Known limitation:</b> a pack may declare several directives in one file, one per
	 * {@code #ifdef} branch — Complementary's {@code composite.glsl} carries both {@code DRAWBUFFERS:7}
	 * and {@code DRAWBUFFERS:71}. Choosing between them needs the preprocessor evaluation this build
	 * does not do, so the first is taken. A pack whose branches disagree can therefore write to the
	 * wrong targets; that is a real gap, not an accepted approximation.</p>
	 */
	public static List<Integer> parse(String source) {
		Matcher renderTargets = RENDERTARGETS_DIRECTIVE.matcher(source);
		if (renderTargets.find()) {
			List<Integer> buffers = new ArrayList<>();
			for (String part : renderTargets.group(1).split(",")) {
				int index = Integer.parseInt(part.trim());
				if (index >= 0 && index <= MAX_BUFFER_INDEX) {
					buffers.add(index);
				}
			}
			return buffers.isEmpty() ? DEFAULT : List.copyOf(buffers);
		}

		Matcher drawBuffers = DRAWBUFFERS_DIRECTIVE.matcher(source);
		if (drawBuffers.find()) {
			List<Integer> buffers = new ArrayList<>();
			for (char digit : drawBuffers.group(1).toCharArray()) {
				// One character per buffer, hex-style so a pack can address 10-15 in this older form.
				buffers.add(Character.digit(digit, 16));
			}
			return buffers.isEmpty() ? DEFAULT : List.copyOf(buffers);
		}

		return DEFAULT;
	}

	/**
	 * Every buffer index mentioned anywhere in {@code source}, across all directive variants.
	 *
	 * <p>Used to size the buffer pool before any pass runs. Unlike {@link #parse} this deliberately
	 * looks at every occurrence: allocating for only the branch that happens to appear first would
	 * leave a pack writing to an unallocated buffer the moment a different branch is compiled.</p>
	 */
	public static Set<Integer> allReferencedBuffers(String source) {
		Set<Integer> referenced = new LinkedHashSet<>();

		Matcher renderTargets = RENDERTARGETS_DIRECTIVE.matcher(source);
		while (renderTargets.find()) {
			for (String part : renderTargets.group(1).split(",")) {
				int index = Integer.parseInt(part.trim());
				if (index >= 0 && index <= MAX_BUFFER_INDEX) {
					referenced.add(index);
				}
			}
		}

		Matcher drawBuffers = DRAWBUFFERS_DIRECTIVE.matcher(source);
		while (drawBuffers.find()) {
			for (char digit : drawBuffers.group(1).toCharArray()) {
				referenced.add(Character.digit(digit, 16));
			}
		}

		return referenced;
	}

	/**
	 * How many buffers must exist for {@code source} to run — one past its highest referenced index.
	 *
	 * <p>Lets the pool be sized to the pack rather than to OptiFine's maximum: BSL reaches
	 * {@code colortex9} while MakeUp stops at 2, and allocating sixteen double-buffered screen-sized
	 * textures for a pack that touches three is most of a gigabyte wasted.</p>
	 */
	public static int requiredBufferCount(String source) {
		return allReferencedBuffers(source).stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
	}
}
