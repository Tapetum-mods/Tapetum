package dev.tapetum.shaders.config;

import dev.tapetum.shaders.light.DynamicLightMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the config's failure modes, which reach the game as "the mod did not start". */
class TapetumConfigTest {

	@Test
	void writesDefaultsWhenNoFileExists(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("tapetumshaders.properties");
		TapetumConfig config = new TapetumConfig(file);

		config.load();

		assertTrue(Files.exists(file), "a missing config should be created rather than ignored");
		assertTrue(config.areShadersEnabled());
		assertTrue(config.getShaderPackName().isEmpty());
	}

	@Test
	void roundTripsASelectedPack(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("tapetumshaders.properties");
		TapetumConfig written = new TapetumConfig(file);
		written.setShaderPackName("ComplementaryReimagined_r5.9.zip");
		written.setShadersEnabled(false);
		written.save();

		TapetumConfig read = new TapetumConfig(file);
		read.load();

		assertEquals("ComplementaryReimagined_r5.9.zip", read.getShaderPackName().orElseThrow());
		assertTrue(!read.areShadersEnabled());
	}

	@Test
	void treatsAnEmptyPackNameAsNoSelection(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("tapetumshaders.properties");
		Files.writeString(file, "shaderPack=\nshadersEnabled=true\n");

		TapetumConfig config = new TapetumConfig(file);
		config.load();

		assertTrue(config.getShaderPackName().isEmpty());
	}

	@Test
	void reportsAMalformedConfigAsAnIoExceptionRatherThanCrashing(@TempDir Path dir) throws Exception {
		// Properties.load throws IllegalArgumentException - unchecked - on a bad escape sequence.
		// Left unwrapped it escapes every handler up to mod initialisation and kills the whole mod
		// over a hand-edited config, so it has to surface as the checked failure callers expect.
		Path file = dir.resolve("tapetumshaders.properties");
		Files.writeString(file, "shaderPack=\\uZZZZ\n");

		TapetumConfig config = new TapetumConfig(file);

		IOException thrown = assertThrows(IOException.class, config::load);
		assertTrue(thrown.getMessage().contains("Malformed"), thrown.getMessage());
	}

	@Test
	void defaultsToShadersEnabledWhenTheFlagIsAbsent(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("tapetumshaders.properties");
		Files.writeString(file, "shaderPack=SomePack.zip\n");

		TapetumConfig config = new TapetumConfig(file);
		config.load();

		assertTrue(config.areShadersEnabled());
	}

	@Test
	void persistsAndRestoresTheDynamicLightMode(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("tapetumshaders.properties");
		TapetumConfig config = new TapetumConfig(file);
		config.setDynamicLightMode(DynamicLightMode.FANCY);
		config.save();

		TapetumConfig reloaded = new TapetumConfig(file);
		reloaded.load();

		assertEquals(DynamicLightMode.FANCY, reloaded.getDynamicLightMode());
	}

	@Test
	void defaultsDynamicLightsToOff(@TempDir Path directory) throws IOException {
		// A feature that changes how the world looks and costs chunk rebuilds should not switch itself
		// on behind the player's back.
		TapetumConfig config = new TapetumConfig(directory.resolve("tapetumshaders.properties"));

		assertEquals(DynamicLightMode.OFF, config.getDynamicLightMode());
	}

	@Test
	void fallsBackToOffWhenTheStoredDynamicLightModeIsJunk(@TempDir Path directory) throws IOException {
		Path file = directory.resolve("tapetumshaders.properties");
		Files.writeString(file, "shaderPack=\nshadersEnabled=true\ndynamicLights=BRIGHTEST\n");

		TapetumConfig config = new TapetumConfig(file);
		config.load();

		assertEquals(DynamicLightMode.OFF, config.getDynamicLightMode());
	}
}
