package dev.tapetum.shaders.shaderpack;

/**
 * Which of a shaderpack's per-dimension program folders to read programs from.
 *
 * <p>OptiFine-format packs may ship a program either at the shaders root or inside a
 * {@code world<id>} folder, the dimension-specific copy winning. The folder names come from the
 * vanilla dimension ids the format predates: {@code world0} for the Overworld, {@code world-1} for
 * the Nether, {@code world1} for the End. Packs map their own names in {@code dimension.properties}
 * ({@code dimension.world-1=minecraft:the_nether}); this enum covers the three standard ones, which
 * is what every pack surveyed ships.</p>
 *
 * <p>This lives in {@code common/} deliberately: it must not reference Minecraft's own dimension
 * types, so the version-independent core stays free of a game dependency. Callers on the Minecraft
 * side map the live dimension onto this.</p>
 */
public enum ShaderDimension {
	OVERWORLD("world0"),
	NETHER("world-1"),
	END("world1");

	private final String folderName;

	ShaderDimension(String folderName) {
		this.folderName = folderName;
	}

	/** The pack subfolder holding this dimension's programs, e.g. {@code world0}. */
	public String folderName() {
		return folderName;
	}

	/** Legacy worldN mapping; modded dimensions use world0 unless a pack defines its own mapping. */
	public static ShaderDimension fromDimensionId(String id) {
		if ("minecraft:the_nether".equals(id)) return NETHER;
		if ("minecraft:the_end".equals(id)) return END;
		return OVERWORLD;
	}
}
