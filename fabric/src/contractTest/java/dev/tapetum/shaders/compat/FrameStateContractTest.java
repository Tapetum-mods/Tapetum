package dev.tapetum.shaders.compat;

import dev.tapetum.shaders.uniform.FrameState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Pure camera/matrix checks: no client, render device or window is initialized. */
final class FrameStateContractTest {
    private static int checks;

    static void run() {
        FrameState.resetHistory();
        Matrix4f first = new Matrix4f().translation(1, 2, 3);
        capture(first, new Vec3(10, 20, 30));
        require(FrameState.previousModelView().equals(first), "First frame has no identity-matrix motion");
        require(FrameState.previousCameraPosition().equals(FrameState.cameraPosition()), "First camera has no stale motion");

        Matrix4f second = new Matrix4f().translation(4, 5, 6);
        capture(second, new Vec3(40, 50, 60));
        require(FrameState.previousModelView().equals(first), "Subsequent frame keeps real previous transform");
        require(FrameState.previousCameraPosition().equals(new Vec3(10, 20, 30)), "Subsequent frame keeps previous camera");

        Matrix4f inverse = new Matrix4f(FrameState.modelViewInverse());
        capture(new Matrix4f().zero(), new Vec3(99, 99, 99));
        require(FrameState.modelView().equals(second), "Singular transform is rejected atomically");
        require(FrameState.modelViewInverse().equals(inverse), "Singular inverse never reaches uniforms");
        FrameState.capture(second, new Matrix4f().zero(), Vec3.ZERO, 0.05f, 256, 0, null, 0, 0);
        require(FrameState.cameraPosition().equals(new Vec3(40, 50, 60)), "Singular projection does not advance camera");
        capture(new Matrix4f().m00(Float.NaN), Vec3.ZERO);
        require(FrameState.modelView().equals(second), "NaN matrix is rejected");
        capture(new Matrix4f(), new Vec3(Double.NaN, 0, 0));
        require(FrameState.modelView().equals(second), "NaN camera is rejected");

        FrameState.resetHistory();
        capture(new Matrix4f().zero(), Vec3.ZERO);
        Matrix4f newWorld = new Matrix4f().translation(7, 8, 9);
        capture(newWorld, new Vec3(1000, 64, 2000));
        require(FrameState.previousModelView().equals(newWorld), "Reset waits for the next valid transform");
        require(FrameState.previousCameraPosition().equals(FrameState.cameraPosition()), "No cross-world camera motion");
        FrameState.capture(newWorld, new Matrix4f(), Vec3.ZERO, Float.POSITIVE_INFINITY,
            Float.NaN, 0, null, 0, 0);
        require(Float.isFinite(FrameState.near()) && Float.isFinite(FrameState.far())
            && FrameState.far() > FrameState.near(), "Invalid clip planes have finite ordered defaults");
        FrameState.capture(newWorld, new Matrix4f(), Vec3.ZERO, 1.0e20f, 0, 0, null, 0, 0);
        require(FrameState.far() > FrameState.near() && Float.isFinite(FrameState.far()),
            "Large near planes cannot round the fallback far plane back to near");
        System.out.println("Frame-state contract: " + checks + " checks passed (headless)");
    }

    private static void capture(Matrix4f view, Vec3 camera) {
        FrameState.capture(view, new Matrix4f(), camera, 0.05f, 256, 0, new Vector4f(), 0, 0);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}
