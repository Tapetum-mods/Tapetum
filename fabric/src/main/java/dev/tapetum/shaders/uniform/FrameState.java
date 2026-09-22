package dev.tapetum.shaders.uniform;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4fc;

/**
 * The per-frame camera and matrix state a shaderpack needs, captured at the start of level rendering
 * and read back when the chain runs.
 *
 * <p>This exists because the values are only available where they are handed to
 * {@code LevelRenderer.renderLevel} — the model-view matrix as a parameter, the projection matrix and
 * camera on {@code CameraRenderState} — and are gone by the time the composite chain runs at the end
 * of the same method. Capturing them at the head is the cheapest correct answer.</p>
 *
 * <p>Without these the chain still runs and still draws; it just draws black. Every screen-space
 * effect a pack performs starts by turning a depth sample back into a position, and that inversion
 * needs {@code gbufferProjectionInverse}. An unset uniform is zero, and a zero matrix maps the whole
 * screen to the origin.</p>
 */
public final class FrameState {
	private static final Matrix4f projection = new Matrix4f();
	private static final Matrix4f projectionInverse = new Matrix4f();
	private static final Matrix4f modelView = new Matrix4f();
	private static final Matrix4f modelViewInverse = new Matrix4f();
	private static final Matrix4f candidateProjectionInverse = new Matrix4f();
	private static final Matrix4f candidateModelViewInverse = new Matrix4f();
	private static boolean hasHistory;

	/** Last frame's matrices, which packs use for motion vectors and temporal accumulation. */
	private static final Matrix4f previousProjection = new Matrix4f();
	private static final Matrix4f previousModelView = new Matrix4f();

	private static Vec3 cameraPosition = Vec3.ZERO;
	private static Vec3 previousCameraPosition = Vec3.ZERO;

	private static float near = 0.05f;
	private static float far = 256.0f;

	/** OptiFine's encoding: 0 none, 1 water, 2 lava, 3 powder snow. */
	private static int eyeInWater;

	/** The colour Minecraft fogs the frame with, straight from what renderLevel was handed. */
	private static final Vector3f fogColor = new Vector3f(0.6f, 0.7f, 0.9f);

	/**
	 * Minecraft's own sky angle, 0-1, read from the camera rather than recomputed.
	 *
	 * <p>Not the same thing as a pack's {@code sunAngle}: this one reads 0 at noon, and it advances
	 * non-linearly so the sun lingers near the horizon. {@link dev.tapetum.shaders.uniform.CelestialAngles}
	 * converts between the two. Deriving it from the game tick instead — as an earlier version did —
	 * gives a linear approximation that is right at sunrise and noon and wrong in between.</p>
	 */
	private static float skyAngle;

	/** Moon phase ordinal, 0-7. */
	private static int moonPhase;

	private FrameState() {
	}

	/** The next valid frame starts a new timeline, without motion from a different world or pack. */
	public static void resetHistory() {
		hasHistory = false;
	}

	/**
	 * Records this frame's state. The previous frame's values are rolled over first, so a pack asking
	 * for {@code gbufferPreviousModelView} gets the frame before this one rather than a copy of it.
	 */
	public static void capture(Matrix4fc frameModelView, Matrix4fc frameProjection, Vec3 camera,
			float nearPlane, float farPlane, int fogType, Vector4fc frameFogColor, float frameSkyAngle, int frameMoonPhase) {
		// Reject a frame whose matrices are not finite rather than propagating it. JOML's invert()
		// does not throw on a singular matrix - it returns NaN - and a single NaN uniform poisons
		// every arithmetic path in every pass downstream, which surfaces as a black or garbage frame
		// with nothing in the log. Keeping the last good frame is visibly better than that, and this
		// can legitimately happen for a frame or two while the camera is still being set up.
		if (!isFinite(frameProjection) || !isFinite(frameModelView)
				|| camera == null || !Double.isFinite(camera.x) || !Double.isFinite(camera.y)
				|| !Double.isFinite(camera.z)) {
			return;
		}
		frameProjection.invert(candidateProjectionInverse);
		frameModelView.invert(candidateModelViewInverse);
		// A finite matrix can still be singular. Validate both inverses before changing any state.
		if (!isFinite(candidateProjectionInverse) || !isFinite(candidateModelViewInverse)) return;

		previousProjection.set(projection);
		previousModelView.set(modelView);
		previousCameraPosition = cameraPosition;

		projection.set(frameProjection);
		modelView.set(frameModelView);
		// Inverted once per frame rather than once per pass: a chain is fifteen passes deep and the
		// matrices do not change between them.
		projectionInverse.set(candidateProjectionInverse);
		modelViewInverse.set(candidateModelViewInverse);

		cameraPosition = camera;
		if (!hasHistory) {
			previousProjection.set(projection);
			previousModelView.set(modelView);
			previousCameraPosition = cameraPosition;
			hasHistory = true;
		}
		// Guarded because packs divide by (far - near) to linearise depth; equal or inverted planes
		// would hand every one of them a division by zero.
		near = Float.isFinite(nearPlane) && nearPlane > 0.0f && nearPlane < Float.MAX_VALUE ? nearPlane : 0.05f;
		far = Float.isFinite(farPlane) && farPlane > near ? farPlane : Math.max(near + 1.0f, Math.nextUp(near));
		eyeInWater = fogType;

		if (frameFogColor != null) {
			fogColor.set(frameFogColor.x(), frameFogColor.y(), frameFogColor.z());
		}
		skyAngle = frameSkyAngle;
		moonPhase = frameMoonPhase;
	}

	/** Minecraft's sky angle, 0-1. Convert with {@link CelestialAngles} before handing it to a pack. */
	public static float skyAngle() {
		return skyAngle;
	}

	public static int moonPhase() {
		return moonPhase;
	}

	/** Whether every element is finite, i.e. the matrix is safe to invert and to upload. */
	private static boolean isFinite(Matrix4fc matrix) {
		for (int column = 0; column < 4; column++) {
			for (int row = 0; row < 4; row++) {
				if (!Float.isFinite(matrix.get(column, row))) {
					return false;
				}
			}
		}
		return true;
	}

	public static Vector3f fogColor() {
		return fogColor;
	}

	public static Matrix4fc projection() {
		return projection;
	}

	public static Matrix4fc projectionInverse() {
		return projectionInverse;
	}

	public static Matrix4fc modelView() {
		return modelView;
	}

	public static Matrix4fc modelViewInverse() {
		return modelViewInverse;
	}

	public static Matrix4fc previousProjection() {
		return previousProjection;
	}

	public static Matrix4fc previousModelView() {
		return previousModelView;
	}

	public static Vec3 cameraPosition() {
		return cameraPosition;
	}

	public static Vec3 previousCameraPosition() {
		return previousCameraPosition;
	}

	public static float near() {
		return near;
	}

	public static float far() {
		return far;
	}

	public static int eyeInWater() {
		return eyeInWater;
	}
}
