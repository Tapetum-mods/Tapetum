package dev.tapetum.shaders.shaderpack;

import java.util.ArrayList;
import java.util.List;

/**
 * The ordered list of screen-space programs a pack wants run, discovered from which program files it
 * actually ships.
 *
 * <p>OptiFine numbers these chains {@code deferred}, {@code deferred1}…{@code deferred15} and
 * {@code composite}, {@code composite1}…{@code composite15}, then {@code final} last. A pack ships
 * only the entries it needs, and <b>the numbering is not required to be contiguous</b>: Complementary
 * Reimagined ships {@code composite}, {@code composite1}, {@code composite3}, {@code composite4},
 * {@code composite5}, {@code composite6}, {@code composite7} — with no {@code composite2} — and
 * {@code deferred1} with no {@code deferred}. Stopping at the first gap would silently drop five of
 * its seven composite passes and produce a half-rendered image, which is precisely the failure this
 * class exists to avoid.</p>
 *
 * <p>Only the full-screen chain is modelled here. The {@code gbuffers_*} programs are a different
 * problem: they replace the shaders Minecraft (or Sodium) uses to draw geometry, rather than running
 * over a finished image, so they cannot be scheduled from a list like this one.</p>
 */
public final class ShaderProgramChain {
	/** OptiFine's ceiling for both chains: {@code composite1}…{@code composite15}. */
	public static final int MAX_CHAIN_INDEX = 15;

	private ShaderProgramChain() {
	}

	/**
	 * A single program in the chain, named the way the pack names its files.
	 *
	 * @param name     the program name without extension, e.g. {@code composite3}
	 * @param fragment the fragment file to read, e.g. {@code composite3.fsh}
	 * @param vertex   the vertex file that goes with it, e.g. {@code composite3.vsh}
	 */
	public record Pass(String name, String fragment, String vertex) {
		/** Names the pair from the program name, which is how OptiFine lays every pack out. */
		public Pass(String name) {
			this(name, name + ".fsh", name + ".vsh");
		}
	}

	/**
	 * Returns the passes {@code pack} ships for {@code dimension}, in the order OptiFine runs them:
	 * the whole {@code deferred} chain, then the whole {@code composite} chain, then {@code final}.
	 *
	 * <p>A pack with no screen-space programs at all yields an empty list rather than a list
	 * containing only {@code final} — there is nothing to run.</p>
	 */
	public static List<Pass> discover(ShaderPack pack, ShaderDimension dimension) {
		List<Pass> passes = new ArrayList<>();

		appendChain(passes, pack, dimension, "deferred");
		appendChain(passes, pack, dimension, "composite");

		if (exists(pack, dimension, "final")) {
			passes.add(new Pass("final"));
		}

		return passes;
	}

	/**
	 * Appends every entry of one numbered chain that the pack actually ships.
	 *
	 * <p>The unnumbered entry ({@code composite}) comes first and counts as index 0, then 1 upwards.
	 * Every index is probed to {@link #MAX_CHAIN_INDEX} regardless of gaps — see the class docs for
	 * why the obvious "stop at the first missing one" is wrong.</p>
	 */
	private static void appendChain(List<Pass> passes, ShaderPack pack, ShaderDimension dimension, String base) {
		if (exists(pack, dimension, base)) {
			passes.add(new Pass(base));
		}

		for (int index = 1; index <= MAX_CHAIN_INDEX; index++) {
			String name = base + index;
			if (exists(pack, dimension, name)) {
				passes.add(new Pass(name));
			}
		}
	}

	private static boolean exists(ShaderPack pack, ShaderDimension dimension, String programName) {
		return pack.locateProgram(programName + ".fsh", dimension).isPresent();
	}
}
