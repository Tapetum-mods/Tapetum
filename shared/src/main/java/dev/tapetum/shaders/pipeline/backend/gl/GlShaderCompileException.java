package dev.tapetum.shaders.pipeline.backend.gl;

import java.io.IOException;

/**
 * A GLSL shader or program failed to compile/link. Carries the driver's info log so the caller can
 * report something actionable instead of just "it didn't work".
 */
public class GlShaderCompileException extends IOException {
	private static final long serialVersionUID = 1L;

	public GlShaderCompileException(String message) {
		super(message);
	}
}
