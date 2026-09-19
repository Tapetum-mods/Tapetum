package dev.tapetum.shaders.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import dev.tapetum.shaders.light.DynamicLightMode;

/**
 * Persists the small set of settings this build actually acts on: which shaderpack is selected
 * and whether shader-based rendering is enabled at all. Modeled after Iris' {@code IrisConfig},
 * trimmed down to what the current pipeline skeleton understands.
 */
public class TapetumConfig {
	private static final String COMMENT = "Tapetum Shaders configuration";

	private final Path propertiesPath;

	/** Null when no pack is selected, i.e. vanilla rendering. */
	private String shaderPackName;
	private boolean shadersEnabled;
	private DynamicLightMode dynamicLightMode;
	private boolean diagnosticTrace;

	public TapetumConfig(Path propertiesPath) {
		this.propertiesPath = propertiesPath;
		this.shaderPackName = null;
		this.shadersEnabled = true;
		// Off by default: it costs chunk rebuilds, and a feature that changes how the world looks
		// should be the player's choice rather than something they discover already switched on.
		this.dynamicLightMode = DynamicLightMode.OFF;
		// Off by default: each sample stalls the GPU, and it exists for diagnosing a broken image
		// rather than for playing.
		this.diagnosticTrace = false;
	}

	public void load() throws IOException {
		if (!Files.exists(propertiesPath)) {
			save();
			return;
		}

		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(propertiesPath)) {
			properties.load(in);
		} catch (IllegalArgumentException e) {
			// Properties.load throws this - unchecked - on a malformed Unicode escape sequence, so it
			// slips past every IOException handler between here and mod initialisation and takes the
			// whole mod down over a hand-edited or truncated config. Defaults beat a crash.
			throw new IOException("Malformed configuration at " + propertiesPath
				+ "; it will be rewritten with defaults", e);
		}

		String storedPack = properties.getProperty("shaderPack", "");
		this.shaderPackName = storedPack.isEmpty() ? null : storedPack;
		this.shadersEnabled = !"false".equals(properties.getProperty("shadersEnabled"));
		this.dynamicLightMode = DynamicLightMode.parse(
			properties.getProperty("dynamicLights"), DynamicLightMode.OFF);
		this.diagnosticTrace = "true".equalsIgnoreCase(properties.getProperty("diagnosticTrace"));
	}

	public void save() throws IOException {
		Properties properties = new Properties();
		properties.setProperty("shaderPack", shaderPackName == null ? "" : shaderPackName);
		properties.setProperty("shadersEnabled", Boolean.toString(shadersEnabled));
		properties.setProperty("dynamicLights", dynamicLightMode.name());
		properties.setProperty("diagnosticTrace", Boolean.toString(diagnosticTrace));

		Files.createDirectories(propertiesPath.getParent());
		try (OutputStream out = Files.newOutputStream(propertiesPath)) {
			properties.store(out, COMMENT);
		}
	}

	public Optional<String> getShaderPackName() {
		return Optional.ofNullable(shaderPackName);
	}

	public void setShaderPackName(String name) {
		this.shaderPackName = (name == null || name.isEmpty()) ? null : name;
	}

	public boolean areShadersEnabled() {
		return shadersEnabled;
	}

	public void setShadersEnabled(boolean enabled) {
		this.shadersEnabled = enabled;
	}

	/**
	 * Whether to log the per-pass pixel trace when a pack is activated.
	 *
	 * <p>Editable here as well as through {@code -Dtapetum.trace=true} because a JVM argument means
	 * finding the right field in a launcher, and getting it silently wrong is easy — the flag either
	 * arrives or it does not, with nothing in between to tell you which.</p>
	 */
	public boolean isDiagnosticTrace() {
		return diagnosticTrace;
	}

	public void setDiagnosticTrace(boolean enabled) {
		this.diagnosticTrace = enabled;
	}

	public DynamicLightMode getDynamicLightMode() {
		return dynamicLightMode;
	}

	public void setDynamicLightMode(DynamicLightMode mode) {
		this.dynamicLightMode = mode == null ? DynamicLightMode.OFF : mode;
	}
}
