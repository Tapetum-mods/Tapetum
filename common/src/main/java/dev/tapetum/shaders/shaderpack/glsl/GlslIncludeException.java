package dev.tapetum.shaders.shaderpack.glsl;

/** A pack's {@code #include} directives could not be expanded — missing file, or a cycle. */
public class GlslIncludeException extends Exception {
	private static final long serialVersionUID = 1L;

	public GlslIncludeException(String message) {
		super(message);
	}
}
