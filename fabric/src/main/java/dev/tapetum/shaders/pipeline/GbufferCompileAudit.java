package dev.tapetum.shaders.pipeline;

import dev.tapetum.shaders.pipeline.backend.gl.GlProgram;
import dev.tapetum.shaders.pipeline.backend.gl.GlShaderCompileException;
import dev.tapetum.shaders.shaderpack.GbufferProgram;
import dev.tapetum.shaders.shaderpack.ShaderDimension;
import dev.tapetum.shaders.shaderpack.ShaderPack;
import dev.tapetum.shaders.shaderpack.glsl.DrawBuffers;
import dev.tapetum.shaders.shaderpack.glsl.GbufferVertexAdapter;
import dev.tapetum.shaders.shaderpack.glsl.GlslCompatPatcher;
import dev.tapetum.shaders.shaderpack.glsl.GlslIncludeException;
import dev.tapetum.shaders.shaderpack.glsl.ShaderMacros;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compiles a pack's {@code gbuffers_*} programs on the real driver and reports what happened.
 *
 * <p>Nothing is rendered with them yet. The point is the feedback loop: {@code glslangValidator}
 * gets all one hundred and twenty-five generated programs across the surveyed packs through, and
 * {@code glslangValidator} is necessary but never sufficient — the Apple M1 driver has rejected
 * sources it accepted. Compiling here is the only way to learn that before the geometry work starts
 * depending on it, and it costs one pack load rather than a render loop.</p>
 *
 * <p>Every program is deleted again immediately. This changes no rendering state and cannot take the
 * frame down: a failure is logged and the composite chain carries on exactly as before.</p>
 */
public final class GbufferCompileAudit {

	private static final Logger LOGGER = LoggerFactory.getLogger("Tapetum Shaders");

	/** How many failures are reported with their full driver log before the rest are summarised. */
	private static final int DETAILED_FAILURES = 4;

	private GbufferCompileAudit() {
	}

	/**
	 * @param dimension the world folder to resolve programs against, so a pack shipping only
	 *                  {@code world0/} copies is read from the same place the chain reads them
	 */
	public static Result run(ShaderPack pack, ShaderMacros macros, ShaderDimension dimension) {
		List<String> compiled = new ArrayList<>();
		List<String> inherited = new ArrayList<>();
		List<String> absent = new ArrayList<>();
		List<String> failed = new ArrayList<>();
		// The union of what the geometry programs actually ask the driver for. This is the work list
		// for binding them: read from GL_ACTIVE_UNIFORMS rather than from the source, because a
		// declaration inside a disabled #ifdef is not an active uniform and counting text over-counts
		// it badly - that mistake produced two wrong diagnoses on the composite chain already.
		Set<String> required = new TreeSet<>();
		int detailed = 0;

		for (GbufferProgram wanted : GbufferProgram.values()) {
			Optional<GbufferProgram> resolved = wanted.resolve(
				candidate -> pack.locateProgram(candidate.fragmentFile(), dimension).isPresent()
					&& pack.locateProgram(candidate.vertexFile(), dimension).isPresent());
			if (resolved.isEmpty()) {
				absent.add(wanted.programName());
				continue;
			}

			GbufferProgram actual = resolved.get();
			if (actual != wanted) {
				inherited.add(wanted.programName() + "<-" + actual.programName());
			}

			Sources sources;
			try {
				sources = read(pack, actual, macros, dimension);
			} catch (IOException | GlslIncludeException e) {
				failed.add(wanted.programName());
				LOGGER.warn("'{}': could not read {} ({})", pack.getName(), wanted.programName(),
					e.getMessage());
				continue;
			}
			if (sources == null) {
				// A fragment stage with no vertex half beside it. Nothing to link, and nothing wrong
				// either - but worth naming, because it is indistinguishable in the count from a
				// program the pack simply does not ship.
				absent.add(wanted.programName());
				LOGGER.debug("'{}': {} ships a fragment stage but no vertex stage", pack.getName(),
					wanted.programName());
				continue;
			}

			try (GlProgram program = GlProgram.link(
					pack.getName() + '/' + wanted.programName(), sources.vertex(), sources.fragment())) {
				compiled.add(wanted.programName());
				required.addAll(program.activeUniforms());
				LOGGER.debug("'{}': {} links, writing to {}", pack.getName(), wanted.programName(),
					DrawBuffers.parse(sources.fragment()));
			} catch (GlShaderCompileException e) {
				failed.add(wanted.programName());
				// The first few carry the driver's own text; beyond that the list of names is the
				// useful signal and the logs stay readable.
				if (detailed++ < DETAILED_FAILURES) {
					LOGGER.error("'{}': {} does not compile on this driver", pack.getName(),
						wanted.programName(), e);
				}
			} catch (RuntimeException e) {
				// An audit must never be the reason the pack fails to load. Anything the driver or
				// the GL binding throws that is not a compile error is still just a finding here.
				failed.add(wanted.programName());
				LOGGER.error("'{}': {} threw while being audited", pack.getName(),
					wanted.programName(), e);
			}
		}

		LOGGER.info("'{}': gbuffers audit - {} of {} compile ({} inherited through the fallback chain, "
				+ "{} not shipped){}", pack.getName(), compiled.size(), compiled.size() + failed.size(),
			inherited.size(), absent.size(),
			failed.isEmpty() ? "" : " - failing: " + failed);
		if (!compiled.isEmpty()) {
			LOGGER.info("'{}': those programs read {} distinct uniforms and samplers between them",
				pack.getName(), required.size());
			LOGGER.debug("'{}': gbuffers uniforms {}", pack.getName(), required);
		}
		if (!inherited.isEmpty()) {
			LOGGER.debug("'{}': inherited programs {}", pack.getName(), inherited);
		}
		return new Result(compiled.size(), List.copyOf(failed));
	}

	public record Result(int compiled, List<String> failed) {}

	/** The adapted pair, or null when the pack ships a fragment stage with no vertex half. */
	private static Sources read(ShaderPack pack, GbufferProgram program, ShaderMacros macros,
			ShaderDimension dimension) throws IOException, GlslIncludeException {
		Optional<String> fragment = pack.readCompilableProgramSource(
			program.fragmentFile(), GlslCompatPatcher.Stage.FRAGMENT, dimension, macros);
		Optional<String> vertex = pack.readCompilableProgramSource(
			program.vertexFile(), GlslCompatPatcher.Stage.VERTEX, dimension, macros);
		if (fragment.isEmpty() || vertex.isEmpty()) {
			return null;
		}

		String adapted = GbufferVertexAdapter.adapt(vertex.get());
		return new Sources(
			PipelineManager.alignVersions(adapted, fragment.get(), program.programName()),
			fragment.get());
	}

	private record Sources(String vertex, String fragment) {
	}
}
