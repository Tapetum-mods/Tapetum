package dev.tapetum.shaders.uniform;

/** Updated once per rendered frame; all passes read the same timing values. */
public final class FrameClock {
	private boolean started;
	private long previousNanos;
	private int frame;
	private float seconds;

	public void advance(long nowNanos) {
		seconds = started ? Math.max(0L, nowNanos - previousNanos) / 1.0e9f : 0.0f;
		previousNanos = nowNanos;
		started = true;
		frame = frame == Integer.MAX_VALUE ? 0 : frame + 1;
	}

	public int frame() {
		return frame;
	}

	public float seconds() {
		return seconds;
	}
}
