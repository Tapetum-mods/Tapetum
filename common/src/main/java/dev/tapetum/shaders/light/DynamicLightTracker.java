package dev.tapetum.shaders.light;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tracks which entities are currently emitting light and works out what that changes.
 *
 * <p>Deliberately free of Minecraft types so it can be unit-tested without a game, in the same way
 * the GLSL layer is. Everything version-specific — deciding that a held torch is luminance 14, or
 * asking the renderer to rebuild a section — lives on the other side of this boundary.</p>
 *
 * <p>The falloff matches Minecraft's own block light: a source of luminance {@code L} contributes
 * {@code L - d} at distance {@code d}, so a torch (14) reaches fourteen blocks and fades one level
 * per block. Using Minecraft's rule rather than an invented one is what makes a dynamically lit
 * torch look like a placed one.</p>
 */
public final class DynamicLightTracker {
	/** The side of a chunk section, in blocks. Sections are the unit the renderer rebuilds. */
	public static final int SECTION_SIZE = 16;

	/** Minecraft's maximum block light level; nothing may contribute more than a placed torch could. */
	public static final int MAX_LUMINANCE = 15;

	/**
	 * One light-emitting entity at a moment in time.
	 *
	 * @param id        the entity's identity, stable across ticks so movement can be detected
	 * @param luminance 0-15, already resolved from whatever the entity is holding or is
	 */
	public record Source(int id, int blockX, int blockY, int blockZ, int luminance) {
		public Source {
			luminance = Math.clamp(luminance, 0, MAX_LUMINANCE);
		}
	}

	/** A chunk section in section coordinates, i.e. block coordinates divided by 16. */
	public record Section(int x, int y, int z) {
	}

	private final Map<Integer, Source> tracked = new HashMap<>();

	/**
	 * Replaces the tracked set with {@code current}.
	 *
	 * @return every section whose lighting this changes — the ones the renderer must rebuild. A
	 *         source that moved dirties both where it was and where it now is; a source that stopped
	 *         emitting dirties only where it was.
	 */
	public Set<Section> update(Collection<Source> current) {
		Set<Section> dirty = new HashSet<>();
		Map<Integer, Source> next = new HashMap<>();

		for (Source source : current) {
			if (source.luminance() <= 0) {
				continue;
			}
			next.put(source.id(), source);

			Source previous = tracked.get(source.id());
			if (previous == null || !sameLight(previous, source)) {
				// New, moved or changed brightness: the new neighbourhood needs relighting, and the
				// old one needs it too or the light it used to cast would be left burnt in.
				addSectionsAround(dirty, source);
				if (previous != null) {
					addSectionsAround(dirty, previous);
				}
			}
		}

		for (Source previous : tracked.values()) {
			if (!next.containsKey(previous.id())) {
				// Gone: dropped, despawned, or the item was put away.
				addSectionsAround(dirty, previous);
			}
		}

		tracked.clear();
		tracked.putAll(next);
		return dirty;
	}

	private static boolean sameLight(Source a, Source b) {
		return a.blockX() == b.blockX() && a.blockY() == b.blockY() && a.blockZ() == b.blockZ()
			&& a.luminance() == b.luminance();
	}

	/**
	 * The dynamic block light at a position, 0-15, or 0 where nothing reaches.
	 *
	 * <p>Sources do not add up: two torches side by side light a spot as one torch would, exactly as
	 * vanilla block light behaves. Summing them would blow past 15 and look nothing like Minecraft.</p>
	 */
	public int luminanceAt(int blockX, int blockY, int blockZ) {
		int best = 0;
		for (Source source : tracked.values()) {
			int contribution = contributionOf(source, blockX, blockY, blockZ);
			if (contribution > best) {
				best = contribution;
			}
		}
		return best;
	}

	private static int contributionOf(Source source, int blockX, int blockY, int blockZ) {
		int dx = source.blockX() - blockX;
		int dy = source.blockY() - blockY;
		int dz = source.blockZ() - blockZ;
		// Rounded up, so a source is never brighter than a placed block of the same luminance would be
		// at the same distance.
		int distance = (int) Math.ceil(Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
		return Math.max(0, source.luminance() - distance);
	}

	/** Every section the source's light can reach, which is a cube of radius {@code luminance}. */
	private static void addSectionsAround(Set<Section> dirty, Source source) {
		int radius = source.luminance();
		int minX = Math.floorDiv(source.blockX() - radius, SECTION_SIZE);
		int maxX = Math.floorDiv(source.blockX() + radius, SECTION_SIZE);
		int minY = Math.floorDiv(source.blockY() - radius, SECTION_SIZE);
		int maxY = Math.floorDiv(source.blockY() + radius, SECTION_SIZE);
		int minZ = Math.floorDiv(source.blockZ() - radius, SECTION_SIZE);
		int maxZ = Math.floorDiv(source.blockZ() + radius, SECTION_SIZE);

		for (int x = minX; x <= maxX; x++) {
			for (int y = minY; y <= maxY; y++) {
				for (int z = minZ; z <= maxZ; z++) {
					dirty.add(new Section(x, y, z));
				}
			}
		}
	}

	/** The sources currently emitting, for the renderer to consult while meshing. */
	public List<Source> activeSources() {
		return new ArrayList<>(tracked.values());
	}

	/** Forgets everything — on world change, or when the feature is switched off. */
	public Set<Section> clear() {
		Set<Section> dirty = new HashSet<>();
		for (Source source : tracked.values()) {
			addSectionsAround(dirty, source);
		}
		tracked.clear();
		return dirty;
	}
}
