package dev.tapetum.shaders.shaderpack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * A thin wrapper around a shaderpack's {@code shaders.properties} file.
 *
 * <p>OptiFine/Iris shaderpacks use a superset of the Java properties format (profiles, conditional
 * defines, indexed keys like {@code composite.1}, texture remaps, etc.). This wrapper only does the
 * plain key/value parsing for now; interpreting those directives is part of actually building a
 * rendering pipeline from a pack, which is out of scope for this skeleton.</p>
 */
public class ShaderProperties {
	private final Properties properties;

	private ShaderProperties(Properties properties) {
		this.properties = properties;
	}

	static ShaderProperties parse(Path propertiesFile) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = Files.newInputStream(propertiesFile)) {
			properties.load(in);
		}
		return new ShaderProperties(properties);
	}

	static ShaderProperties empty() {
		return new ShaderProperties(new Properties());
	}

	public String getOrDefault(String key, String fallback) {
		return properties.getProperty(key, fallback);
	}

	public boolean getBoolean(String key, boolean fallback) {
		String value = properties.getProperty(key);
		if (value == null) {
			return fallback;
		}
		return Boolean.parseBoolean(value);
	}

	public int size() {
		return properties.size();
	}
}
