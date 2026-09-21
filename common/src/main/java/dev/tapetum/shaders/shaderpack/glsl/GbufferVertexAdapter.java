package dev.tapetum.shaders.shaderpack.glsl;

/**
 * Makes a shaderpack's geometry vertex shader compile in a core profile.
 *
 * <p>The {@code gbuffers_*} programs replace the shaders Minecraft draws the world with, and they are
 * written against the fixed-function pipeline that core profile removed. Unlike the composite passes
 * — where a full-screen triangle means the matrices genuinely are identity, see
 * {@link FullScreenVertexAdapter} — these run on real geometry, so every removed name needs a real
 * attribute or uniform behind it.</p>
 *
 * <p>Two families of names are rewritten: the {@code gl_} fixed-function builtins, and the
 * core-profile spellings Iris introduced ({@code vaPosition}, {@code projectionMatrix} and friends),
 * which packs use for the few programs the fixed-function names cannot express.</p>
 *
 * <p><b>Pack-declared attributes are not touched.</b> Packs declare
 * {@code mc_Entity}, {@code mc_midTexCoord}, {@code at_tangent} and {@code at_midBlock} themselves
 * (as {@code attribute}, which {@link GlslCompatPatcher} already rewrites to {@code in}) — measured
 * across the five surveyed packs: 24, 28, 22 and 4 files respectively. Declaring them again here
 * would be a duplicate declaration and a compile error.</p>
 *
 * <p>The attribute and uniform values are bound when the terrain program is substituted; at this
 * stage the declarations exist so the source compiles and its errors can be read.</p>
 */
public final class GbufferVertexAdapter {

	private GbufferVertexAdapter() {
	}

	/** Whether {@code source} uses any fixed-function name this adapter stands in for. */
	public static boolean isNeeded(String source) {
		FixedFunctionRewriter probe = new FixedFunctionRewriter(source);
		for (String name : new String[] { "gl_Vertex", "gl_Color", "gl_Normal", "gl_MultiTexCoord0",
				"gl_MultiTexCoord1", "gl_ModelViewMatrix", "gl_ProjectionMatrix",
				"gl_ModelViewProjectionMatrix", "gl_NormalMatrix", "gl_TextureMatrix", "ftransform",
				"vaPosition", "vaNormal", "vaColor", "vaUV0",
				"modelViewMatrix", "projectionMatrix", "normalMatrix", "textureMatrix" }) {
			if (probe.contains(name)) {
				return true;
			}
		}
		return false;
	}

	/** Rewrites the fixed-function names and prepends the declarations they need. */
	public static String adapt(String source) {
		FixedFunctionRewriter rewriter = new FixedFunctionRewriter(source);
		String position = "tapetum_Position";
		String uv = "tapetum_UV0";
		String light = "tapetum_UV1";
		String positionDeclaration = "in vec3 tapetum_Position;";
		String uvDeclaration = "in vec2 tapetum_UV0;";
		String lightDeclaration = "in vec2 tapetum_UV1;";
		String colorDeclaration = "in vec4 tapetum_Color;";

		// ftransform() is the full transform chain. It expands to the gl_ names rather than straight
		// to the replacements, so that the rewrites below see them and emit their declarations: a
		// shader whose only use of the projection matrix is through ftransform() would otherwise be
		// left referring to a uniform nothing declared.
		rewriter.rewriteCall("ftransform",
			"(gl_ProjectionMatrix * gl_ModelViewMatrix * gl_Vertex)");

		// Longest first: gl_ModelViewMatrix is a prefix of gl_ModelViewProjectionMatrix, and
		// gl_Normal of gl_NormalMatrix.
		rewriter
			.rewrite("gl_ModelViewProjectionMatrix", "tapetum_ModelViewProjectionMatrix",
				"uniform mat4 tapetum_ModelViewProjectionMatrix;")
			.rewrite("gl_ModelViewMatrix", "tapetum_ModelViewMatrix",
				"uniform mat4 tapetum_ModelViewMatrix;")
			.rewrite("gl_ProjectionMatrix", "tapetum_ProjectionMatrix",
				"uniform mat4 tapetum_ProjectionMatrix;")
			.rewrite("gl_NormalMatrix", "tapetum_NormalMatrix", "uniform mat3 tapetum_NormalMatrix;")
			// Only [0] and [1] are ever indexed, measured across the five packs.
			.rewrite("gl_TextureMatrix", "tapetum_TextureMatrix", "uniform mat4 tapetum_TextureMatrix[2];")
			.rewrite("gl_MultiTexCoord0", "vec4(" + uv + ", 0.0, 1.0)", uvDeclaration)
			.rewrite("gl_MultiTexCoord1", "vec4(" + light + ", 0.0, 1.0)", lightDeclaration)
			.rewrite("gl_Vertex", "vec4(" + position + ", 1.0)", positionDeclaration)
			.rewrite("gl_Normal", "tapetum_Normal", "in vec3 tapetum_Normal;")
			.rewrite("gl_Color", "tapetum_Color", colorDeclaration)
			// The core-profile spellings Iris introduced, onto the same attributes and uniforms. A
			// pack reaches for them where the fixed-function names cannot do the job: Complementary's
			// gbuffers_basic widens lines itself, which needs gl_VertexID and the untransformed
			// position, so its GBUFFERS_LINE branch is written entirely in these names. Four programs
			// across the surveyed packs use them, and none declares them itself - so unlike
			// mc_Entity and friends, these do need declaring here.
			//
			// Only the names that map onto a declaration of matching type are listed. vaUV1 and vaUV2
			// are ivec2 in Iris while the lightmap attribute here is a vec2; no surveyed pack uses
			// them, and guessing the conversion would be worse than leaving them undeclared, where
			// the error at least names the missing attribute.
			.rewriteUnlessDeclared("modelViewMatrix", "tapetum_ModelViewMatrix", "uniform mat4 tapetum_ModelViewMatrix;")
			.rewriteUnlessDeclared("projectionMatrix", "tapetum_ProjectionMatrix", "uniform mat4 tapetum_ProjectionMatrix;")
			.rewriteUnlessDeclared("normalMatrix", "tapetum_NormalMatrix", "uniform mat3 tapetum_NormalMatrix;")
			.rewriteUnlessDeclared("textureMatrix", "tapetum_TextureMatrix[0]", "uniform mat4 tapetum_TextureMatrix[2];")
			.rewriteUnlessDeclared("vaPosition", position, positionDeclaration)
			.rewriteUnlessDeclared("vaNormal", "tapetum_Normal", "in vec3 tapetum_Normal;")
			.rewriteUnlessDeclared("vaColor", "tapetum_Color", colorDeclaration)
			.rewriteUnlessDeclared("vaUV0", uv, uvDeclaration)
			.apply();

		return rewriter.finish("gbuffers vertex adapter");
	}
}
